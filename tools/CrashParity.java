package dev.appatlas.sdk.crash;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.appatlas.sdk.core.AtlasCore;
import dev.appatlas.sdk.core.DiskQueue;
import dev.appatlas.sdk.core.EnvelopeWriter;

/**
 * The crash half of the parity gate: the report and session payloads, the
 * scope's bounds, the ANR trace reader, and the hook itself — a real uncaught
 * exception must reach the disk and still reach the handler it displaced.
 * Lives in the module's package: the pieces are package-private by design,
 * and a gate class ships in no artifact.
 */
public final class CrashParity {

    private CrashParity() {
    }

    public static void run(File outDir) throws Exception {
        writeSample(outDir);
        checkScopeBounds();
        checkCauseCycle();
        checkAnrTrace();
        checkNativeReport(new File(outDir, "native-report"));
        checkExits(new File(outDir, "exits"));
        checkHook(new File(outDir, "crash-hook"));

        System.out.println("parity: crash report, scope, anr trace, native report, exits and hook hold");
    }

    private static Throwable sample() {
        Throwable root = new NullPointerException("price was null");
        root.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example.checkout.CartActivity", "onCreate", "CartActivity.java", 42),
                new StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 8000),
                new StackTraceElement("java.lang.reflect.Method", "invoke", null, -2),
        });

        Throwable outer = new RuntimeException("Unable to start activity", root);
        outer.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3449),
        });

        return outer;
    }

    /** A crash and its session's end in one envelope, byte-pinned by the golden. */
    private static void writeSample(File outDir) throws IOException {
        CrashScope scope = new CrashScope();
        scope.setUserId("u-123");
        scope.setKey("screen", "checkout");
        scope.leaveBreadcrumb("cart", "add \"socks\"", null, 1789722000000L);

        Map<String, Object> report = CrashReport.payload("44444444-0000-0000-0000-000000000001",
                "2026-09-18T01:00:00Z", "55555555-0000-0000-0000-000000000001",
                "5db7294d-87fc-4726-a5c0-4a90679657b5", CrashReport.MECHANISM_UNCAUGHT, false, sample(), null, null);
        scope.writeTo(report);

        Map<String, Object> device = new LinkedHashMap<String, Object>();
        device.put("os", "android");
        device.put("osVersion", "14");
        device.put("model", "Pixel 8");
        Map<String, Object> app = new LinkedHashMap<String, Object>();
        app.put("version", "3.2.1");
        app.put("build", 151);
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("device", device);
        context.put("app", app);

        byte[] envelope = new EnvelopeWriter("atlas-android", "0.1.0", "2026-09-18T01:00:05Z", "c1a2b3d4e5f60718", context)
                .add("crash", report)
                .add("session", SessionItems.ended("44444444-0000-0000-0000-000000000002",
                        "55555555-0000-0000-0000-000000000001", "crashed", "2026-09-18T00:58:00Z", 0, 120000L))
                .bytes();

        FileOutputStream stream = new FileOutputStream(new File(outDir, "crash.envelope"));

        try {
            stream.write(envelope);
        } finally {
            stream.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkScopeBounds() {
        CrashScope scope = new CrashScope();

        for (int i = 0; i < CrashScope.MAX_KEYS + 10; i++) {
            scope.setKey("k" + i, "v");
        }
        for (int i = 0; i < CrashScope.MAX_BREADCRUMBS + 25; i++) {
            scope.leaveBreadcrumb("c", "m" + i, null, 0L);
        }

        scope.setKey("k0", null);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        scope.writeTo(payload);

        require(((Map<String, Object>) payload.get("keys")).size() == CrashScope.MAX_KEYS - 1, "key cap not held");
        List<Object> crumbs = (List<Object>) payload.get("breadcrumbs");
        require(crumbs.size() == CrashScope.MAX_BREADCRUMBS, "breadcrumb cap not held");
        require("m25".equals(((Map<String, Object>) crumbs.get(0)).get("message")), "the ring must drop the oldest");
        require(!payload.containsKey("user"), "an unset user must be absent");
    }

    @SuppressWarnings("unchecked")
    private static void checkCauseCycle() {
        Throwable a = new IllegalStateException("a");
        Throwable b = new IllegalStateException("b", a);
        a.initCause(b);

        Map<String, Object> payload = CrashReport.payload("e", "t", null, null, CrashReport.MECHANISM_RECORDED, true, a, null, null);

        require(((List<Object>) payload.get("exceptions")).size() == 2, "a cause cycle must end");
        require(!payload.containsKey("sessionId") && !payload.containsKey("mappingId"), "absent ids must be left out");
    }

    @SuppressWarnings("unchecked")
    private static void checkAnrTrace() {
        String trace = "----- pid 123 at 2026-09-18 -----\n"
                + "\"Signal Catcher\" daemon prio=10 tid=6 Runnable\n"
                + "  at com.example.Wrong.thread(Wrong.java:1)\n"
                + "\n"
                + "\"main\" prio=5 tid=1 Blocked\n"
                + "  | group=\"main\" sCount=1\n"
                + "  at com.example.checkout.Cart.total(Cart.java:88)\n"
                + "  - waiting to lock <0x0a1b> (a java.lang.Object) held by thread 12\n"
                + "  at android.os.MessageQueue.nativePollOnce(Native method)\n"
                + "\n"
                + "\"worker\" prio=5 tid=12 Sleeping\n"
                + "  at java.lang.Thread.sleep(Native method)\n";

        List<Object> frames = AnrTrace.mainFrames(trace);
        require(frames.size() == 2, "main has two java frames, got " + frames.size());

        Map<String, Object> top = (Map<String, Object>) frames.get(0);
        require("com.example.checkout.Cart".equals(top.get("module")) && "total".equals(top.get("function"))
                && "Cart.java".equals(top.get("file")) && Integer.valueOf(88).equals(top.get("line")), "top frame misread");
        require(!((Map<String, Object>) frames.get(1)).containsKey("line"), "a native frame has no line");
        require(AnrTrace.mainFrames("\"main\" prio=5\n  native: #00 pc 000\n").isEmpty(), "a native-only main is not reportable");
        require(AnrTrace.mainFrames(null).isEmpty(), "null must read as empty");
    }

    // --- the hook, against real dying threads ----------------------------------------

    private static final class Probe implements CrashPlatform {
        long contextDelayMs;
        boolean relaxed;
        Thread main;

        @Override
        public Map<String, Object> context() {
            if (contextDelayMs > 0) {
                try {
                    Thread.sleep(contextDelayMs);
                } catch (InterruptedException woken) {
                    Thread.currentThread().interrupt();
                }
            }

            Map<String, Object> facts = new LinkedHashMap<String, Object>();
            facts.put("foreground", Boolean.TRUE);

            return facts;
        }

        @Override
        public void beforeDiskWrite() {
            relaxed = true;
        }

        @Override
        public Thread mainThread() {
            return main;
        }
    }

    private interface Doom {
        void run() throws Throwable;
    }

    /** The C handler's file, as the host gate produces it, read back into a payload. */
    @SuppressWarnings("unchecked")
    private static void checkNativeReport(File dir) throws Exception {
        dir.mkdirs();
        File report = new File(dir, "native-crash.txt");
        String text = "atlas-native-crash 1\n"
                + "time 1789693018\n"
                + "signal 11 1 0\n"
                + "pid 540 tid 569\n"
                + "thread worker-7\n"
                + "reg pc 7f5560eee140 sp 7ffc09572408 fp 7ffc09572410 lr 0\n"
                + "unwind fp\n"
                + "frame 7f5560eee140 1140 90f2555a4ce511c64762df137bf2377cd733caca /data/app/x/lib/arm64/libboom.so\n"
                + "frame 7f5560c2a1ca 2a1ca 8e9fd827446c24067541ac5390e6f527fb5947bb /apex/com.android.runtime/lib64/bionic/libc.so\n"
                + "end fp\n"
                + "unwind cfi\n"
                + "frame 7f5560eee140 1140 90f2555a4ce511c64762df137bf2377cd733caca /data/app/x/lib/arm64/libboom.so\n"
                + "frame 7f5560eee150 1150 90f2555a4ce511c64762df137bf2377cd733caca /data/app/x/lib/arm64/libboom.so\n"
                + "frame 7f5560c2a1ca 2a1ca 8e9fd827446c24067541ac5390e6f527fb5947bb /apex/com.android.runtime/lib64/bionic/libc.so\n"
                + "end\n";
        java.nio.file.Files.write(report.toPath(), text.getBytes("UTF-8"));

        Map<String, Object> payload = NativeReport.read(report, "mapping-1");
        require(payload != null, "a whole report must read");
        List<Object> exceptions = (List<Object>) payload.get("exceptions");
        Map<String, Object> raised = (Map<String, Object>) exceptions.get(0);
        require("SIGSEGV".equals(raised.get("type")), "the signal names the exception");
        List<Object> frames = (List<Object>) raised.get("frames");
        require(frames.size() == 3, "the CFI walk must win over the frame-pointer walk, got " + frames.size());
        Map<String, Object> top = (Map<String, Object>) frames.get(0);
        require("libboom.so".equals(top.get("module")) && "0x1140".equals(top.get("relativeAddr"))
                && "90f2555a4ce511c64762df137bf2377cd733caca".equals(top.get("buildId")), "the top frame carries module, offset and build id");
        require("mapping-1".equals(payload.get("mappingId")), "the mapping id rides for the JVM frames a native crash may also carry");
        require("2026-09-18T00:56:58Z".equals(payload.get("crashedAt")), "the crash time comes from the file");
        Map<String, Object> thread = (Map<String, Object>) ((List<Object>) payload.get("threads")).get(0);
        require("worker-7".equals(thread.get("name")) && Boolean.TRUE.equals(thread.get("crashed")), "the crashed thread is named");

        // The dead process's scope rides in from its snapshot, and the main thread is "main".
        CrashScope gone = new CrashScope();
        gone.setKey("screen", "checkout");
        gone.leaveBreadcrumb("cart", "add", null, 1789722000000L);
        File snapshot = new File(dir, "scope.json");
        gone.persistTo(snapshot);
        java.nio.file.Files.write(report.toPath(), text.replace("pid 540 tid 569", "pid 540 tid 540").getBytes("UTF-8"));
        AtlasCore core = new AtlasCore("atlas-android", "http://127.0.0.1:9", "sdk_test", new File(dir, "queue"), "c1a2b3d4e5f60718");
        CrashReporter reader = new CrashReporter(core, "mapping-1", new CrashScope(), new Probe(), new File(dir, "state"));
        reader.reportPendingNative(report, snapshot, "55555555-0000-0000-0000-000000000007");
        require(!report.exists(), "a read report is consumed");
        require(core.awaitIdle(5000L), "the worker must settle");

        boolean queued = false;

        for (File file : new DiskQueue(new File(dir, "queue")).list()) {
            String queuedText = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");

            if (queuedText.contains("\"type\":\"crash\"")) {
                queued = true;
                require(queuedText.contains("\"keys\":{\"screen\":\"checkout\"}"), "the snapshot's keys must ride the native crash");
                require(queuedText.contains("\"name\":\"main\",\"crashed\":true"), "tid == pid is the main thread");
                require(queuedText.contains("\"sid\":\"55555555-0000-0000-0000-000000000007\",\"status\":\"crashed\""),
                        "the dead session must end as crashed beside it");
            }
        }

        require(queued, "the native crash must be queued at the next start");
        java.nio.file.Files.write(report.toPath(), text.getBytes("UTF-8"));

        // A file cut off mid-write (no "end") is a report of nothing.
        java.nio.file.Files.write(report.toPath(), text.substring(0, text.length() - 4).getBytes("UTF-8"));
        require(NativeReport.read(report, null) == null, "a truncated report must be dropped");
    }

    /** Deaths without a throwable: an OOM kill groups by its type alone, an ANR by its main frames. */
    @SuppressWarnings("unchecked")
    private static void checkExits(File dir) throws Exception {
        Map<String, Object> oom = CrashReport.fromExit("e", "t", "s", null, CrashReport.MECHANISM_EXIT_INFO, "OutOfMemory",
                "The system killed the app while low on memory", null);
        Map<String, Object> raised = (Map<String, Object>) ((List<Object>) oom.get("exceptions")).get(0);
        require("OutOfMemory".equals(raised.get("type")) && ((List<Object>) raised.get("frames")).isEmpty(),
                "an exit without a trace still names its type");
        require("exitInfo".equals(((Map<String, Object>) oom.get("mechanism")).get("type")), "the mechanism is the frozen string");

        // The reporter writes the crash and the session end together, even
        // for a death it only heard about at the next start.
        AtlasCore core = new AtlasCore("atlas-android", "http://127.0.0.1:9", "sdk_test", new File(dir, "queue"), "c1a2b3d4e5f60718");
        CrashReporter reporter = new CrashReporter(core, null, new CrashScope(), new Probe(), new File(dir, "state"));
        reporter.reportExit(1789693018000L, "55555555-0000-0000-0000-000000000009", CrashReport.MECHANISM_EXIT_INFO, "Killed",
                "Killed by signal 9", null, "abnormal");
        require(core.awaitIdle(5000L), "the worker must settle");

        boolean found = false;

        for (File file : new DiskQueue(new File(dir, "queue")).list()) {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");

            if (text.contains("\"type\":\"Killed\"")) {
                found = true;
                require(text.contains("\"sid\":\"55555555-0000-0000-0000-000000000009\",\"status\":\"abnormal\""),
                        "the session the OS named must end as abnormal in the same envelope");
            }
        }

        require(found, "the exit must be queued");
    }

    /** Runs `doom` on a fresh thread under a fresh reporter; returns what reached the displaced handler. */
    private static Throwable die(File dir, Probe probe, CrashScope scope, boolean enabled, final Doom doom,
                                 boolean asMain) throws Exception {
        AtlasCore core = new AtlasCore("atlas-android", "http://127.0.0.1:9", "sdk_test", new File(dir, "queue"), "c1a2b3d4e5f60718");
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        final Throwable[] chained = {null};

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                chained[0] = error;
            }
        });

        try {
            CrashReporter reporter = new CrashReporter(core, null, scope, probe, new File(dir, "state"));
            reporter.setEnabled(enabled);
            reporter.install();
            // A second install must neither chain the reporter to itself nor open a second session.
            reporter.install();

            Thread doomed = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        doom.run();
                    } catch (RuntimeException unchecked) {
                        throw unchecked;
                    } catch (Error fatal) {
                        throw fatal;
                    } catch (Throwable checked) {
                        throw new IllegalStateException(checked);
                    }
                }
            }, "doomed");

            if (asMain) {
                probe.main = doomed;
            }

            doomed.start();
            doomed.join();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }

        return chained[0];
    }

    /**
     * The crash envelope in the queue, waited for briefly: a pool reports its
     * worker gone before the JVM runs that worker's uncaught handler, so the
     * write may still be in flight when the test thread gets here.
     */
    private static String crashOnDisk(File dir) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            for (File file : new DiskQueue(new File(dir, "queue")).list()) {
                String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");

                if (text.contains("\"type\":\"crash\"")) {
                    require(file.getName().contains("_keep"), "a crash envelope must be marked kept");

                    return text;
                }
            }

            Thread.sleep(100L);
        }

        return null;
    }

    private static int core_sessions(File dir) throws Exception {
        int starts = 0;

        for (File file : new DiskQueue(new File(dir, "queue")).list()) {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");

            if (text.contains("\"init\":true")) {
                starts++;
            }
        }

        return starts;
    }

    private static void recurse(int depth) {
        recurse(depth + 1);
    }

    private static void checkHook(File root) throws Exception {
        // An ordinary exception on a background thread nobody wrapped.
        File dir = new File(root, "plain");
        Probe probe = new Probe();
        CrashScope scope = new CrashScope();
        scope.log("about to fail", 1789722000000L);

        Throwable chained = die(dir, probe, scope, true, new Doom() {
            @Override
            public void run() {
                throw new IllegalStateException("boom");
            }
        }, false);
        String crash = crashOnDisk(dir);

        require(chained instanceof IllegalStateException, "the displaced handler must still be called");
        require(crash != null, "the crash must be on disk before the process dies");
        require(probe.relaxed, "the platform must be told before the disk write");
        require(crash.contains("\"status\":\"crashed\""), "the session's end must ride with the crash");
        require(crash.contains("\"name\":\"doomed\",\"crashed\":true"), "the crashed thread must be flagged");
        // File, function and line of the throw site, not of the handler.
        require(crash.contains("\"module\":\"dev.appatlas.sdk.crash.CrashParity$3\",\"function\":\"run\",\"file\":\"CrashParity.java\",\"line\":"),
                "the throw site must carry class, method, file and line");
        require(!crash.contains("\"function\":\"uncaughtException\""), "the dying thread must show where it died, not the handler");
        require(crash.contains("\"foreground\":true") && crash.contains("\"timeToCrashMs\":"), "the context must ride");
        require(crash.contains("about to fail"), "the rolling log must ride");
        require(core_sessions(dir) == 1, "installing twice must open one session, not two");

        // A task handed to an executor with execute(): nobody holds a Future.
        dir = new File(root, "executor");
        die(dir, new Probe(), new CrashScope(), true, new Doom() {
            @Override
            public void run() throws Throwable {
                // The pool reports its worker gone before the JVM runs that
                // worker's uncaught handler: hold the thread itself and join it.
                final Thread[] worker = {null};
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(
                        new java.util.concurrent.ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable task) {
                                worker[0] = new Thread(task, "pool-worker");

                                return worker[0];
                            }
                        });
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        throw new UnsupportedOperationException("in a pool");
                    }
                });
                pool.shutdown();
                pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
                worker[0].join(5000L);
            }
        }, false);
        require(crashOnDisk(dir) != null && crashOnDisk(dir).contains("in a pool"), "a pool thread's death must be reported");

        // An Error, not an Exception: a blown stack, with thousands of frames.
        dir = new File(root, "overflow");
        chained = die(dir, new Probe(), new CrashScope(), true, new Doom() {
            @Override
            public void run() {
                recurse(0);
            }
        }, false);
        crash = crashOnDisk(dir);
        require(chained instanceof StackOverflowError, "an Error must chain like anything else");
        require(crash != null && crash.contains("java.lang.StackOverflowError") && crash.contains("\"function\":\"recurse\""),
                "a stack overflow must be reported with its frames");

        // A throwable whose own getMessage() throws while the app is dying.
        dir = new File(root, "hostile");
        die(dir, new Probe(), new CrashScope(), true, new Doom() {
            @Override
            public void run() {
                throw new RuntimeException() {
                    @Override
                    public String getMessage() {
                        throw new IllegalArgumentException("even this");
                    }
                };
            }
        }, false);
        require(crashOnDisk(dir) != null, "a hostile throwable must still be reported");

        // A platform probe that hangs on the UI thread: the app must still
        // die inside the budget, and the report must still land afterwards.
        dir = new File(root, "slow");
        probe = new Probe();
        probe.contextDelayMs = CrashReporter.MAIN_BUDGET_MS + 1500L;
        long began = System.currentTimeMillis();
        chained = die(dir, probe, new CrashScope(), true, new Doom() {
            @Override
            public void run() {
                throw new IllegalStateException("slow device");
            }
        }, true);
        long took = System.currentTimeMillis() - began;
        require(chained != null && took < CrashReporter.MAIN_BUDGET_MS + 1000L, "the main thread must be released within its budget, took " + took);

        // Collection switched off: nothing is written, the chain still holds.
        dir = new File(root, "disabled");
        chained = die(dir, new Probe(), new CrashScope(), false, new Doom() {
            @Override
            public void run() {
                throw new IllegalStateException("private");
            }
        }, false);
        require(chained != null && new DiskQueue(new File(dir, "queue")).list().length == 0,
                "a disabled reporter must write nothing and still chain");

        // The next start knows the last one crashed, once.
        AtlasCore core = new AtlasCore("atlas-android", "http://127.0.0.1:9", "sdk_test",
                new File(root, "plain/queue"), "c1a2b3d4e5f60718");
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();

        try {
            CrashReporter next = new CrashReporter(core, null, new CrashScope(), new Probe(), new File(root, "plain/state"));
            next.install();
            require(next.crashedLastRun(), "the run after a crash must know it");

            Thread.setDefaultUncaughtExceptionHandler(before);
            CrashReporter later = new CrashReporter(core, null, new CrashScope(), new Probe(), new File(root, "plain/state"));
            later.install();
            require(!later.crashedLastRun(), "the marker must be read once");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }

        // Thirty offline launches later the crash is still queued.
        DiskQueue queue = new DiskQueue(new File(root, "plain/queue"));

        for (int i = 0; i < DiskQueue.MAX_FILES + 10; i++) {
            queue.offer(new EnvelopeWriter("t", "0", "now", "id").bytes());
        }

        require(crashOnDisk(new File(root, "plain")) != null, "the cap must evict ordinary envelopes before a crash");
        require(queue.list().length == DiskQueue.MAX_FILES, "the cap must still hold");
    }

    private static void require(boolean held, String complaint) {
        if (!held) {
            throw new AssertionError(complaint);
        }
    }
}
