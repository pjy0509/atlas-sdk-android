package dev.appatlas.sdk.crash;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.appatlas.sdk.core.AtlasCore;

/**
 * The platform-free half of the crash module: one session per process, the
 * uncaught-exception hook, and handled errors. A crash is written to disk on
 * the dying thread and sent by the next start — the network is not trusted
 * at the moment of a crash.
 */
final class CrashReporter implements Thread.UncaughtExceptionHandler {

    // Crashlytics's ceilings: a handler that blocks the main thread longer
    // turns a crash into an ANR on top of it.
    static final long MAIN_BUDGET_MS = 3000L;
    static final long OTHER_BUDGET_MS = 4000L;
    // A crash this soon after start is a launch crash: the next start sends
    // it before anything else can crash the same way.
    static final long LAUNCH_WINDOW_MS = 5000L;
    static final long LAUNCH_FLUSH_MS = 2000L;

    private static final String MARKER = "last-crash";

    private final AtlasCore core;
    private final String mappingId;
    private final CrashScope scope;
    private final CrashPlatform platform;
    private final File stateDir;
    private final String sessionId = AtlasCore.newEventId();
    private final long startedAtMs = System.currentTimeMillis();
    private final AtomicInteger errors = new AtomicInteger();
    // One fatal per session: a second thread dying behind the first adds nothing.
    private final AtomicBoolean crashed = new AtomicBoolean();

    private volatile boolean enabled = true;
    private volatile boolean crashedLastRun;
    private boolean installed;
    private Thread.UncaughtExceptionHandler previous;

    CrashReporter(AtlasCore core, String mappingId, CrashScope scope, CrashPlatform platform, File stateDir) {
        this.core = core;
        this.mappingId = mappingId;
        this.scope = scope;
        this.platform = platform;
        this.stateDir = stateDir;
    }

    String sessionId() {
        return sessionId;
    }

    boolean crashedLastRun() {
        return crashedLastRun;
    }

    /** A native report is waiting from the previous run: that run crashed too. */
    void noteNativeCrashPending() {
        crashedLastRun = true;
    }

    /** Off, nothing is written or sent; the hook stays in the chain and only forwards. */
    void setEnabled(boolean on) {
        enabled = on;
    }

    /** Opens the session and takes the process-wide hook, chaining whoever held it. Once. */
    synchronized void install() {
        if (installed) {
            return;
        }

        installed = true;

        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();

        // Installed twice, we would chain to ourselves and never reach the system's.
        if (!(current instanceof CrashReporter)) {
            previous = current;
            Thread.setDefaultUncaughtExceptionHandler(this);
        }

        long lastTimeToCrash = takeMarker();
        crashedLastRun = lastTimeToCrash >= 0;

        if (!enabled) {
            return;
        }

        core.enqueue("session", SessionItems.started(AtlasCore.newEventId(), sessionId, AtlasCore.iso(startedAtMs)));

        if (crashedLastRun && lastTimeToCrash < LAUNCH_WINDOW_MS) {
            core.flushWithin(LAUNCH_FLUSH_MS - (System.currentTimeMillis() - startedAtMs));
        }
    }

    @Override
    public void uncaughtException(final Thread thread, final Throwable error) {
        // The instant first: everything after this line costs time.
        final long now = System.currentTimeMillis();

        try {
            if (enabled && crashed.compareAndSet(false, true)) {
                platform.beforeDiskWrite();

                // On its own thread so the budget can be enforced; if even a
                // thread cannot be had (out of memory), written right here.
                Thread writer = null;

                try {
                    writer = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            write(thread, error, now);
                        }
                    }, "atlas-crash-write");
                    writer.setDaemon(true);
                    writer.start();
                } catch (Throwable exhausted) {
                    writer = null;
                    write(thread, error, now);
                }

                if (writer != null) {
                    writer.join(thread == platform.mainThread() ? MAIN_BUDGET_MS : OTHER_BUDGET_MS);
                }
            }
        } catch (Throwable ours) {
            // A reporter that fails must still let the app die its own death.
        } finally {
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                System.exit(1);
            }
        }
    }

    private void write(Thread thread, Throwable error, long now) {
        Map<String, Object> report;

        try {
            report = CrashReport.payload(AtlasCore.newEventId(), AtlasCore.iso(now), sessionId, mappingId,
                    CrashReport.MECHANISM_UNCAUGHT, false, error, Thread.getAllStackTraces(), thread);
            scope.writeTo(report);
            report.put("context", context(now));
        } catch (Throwable starved) {
            // The full report did not fit in what is left of the heap: the
            // exception alone still names the file and the line.
            report = CrashReport.payload(AtlasCore.newEventId(), AtlasCore.iso(now), sessionId, mappingId,
                    CrashReport.MECHANISM_UNCAUGHT, false, error, null, null);
        }

        // Together, so crash-free never sees a crash without its session's end.
        core.batch()
                .add("crash", report)
                .add("session", SessionItems.ended(AtlasCore.newEventId(), sessionId, "crashed",
                        AtlasCore.iso(startedAtMs), errors.get(), now - startedAtMs))
                .persistNow();
        leaveMarker(now - startedAtMs);
    }

    /** A handled error: reported, grouped apart from crashes, never fatal. */
    void recordError(Throwable error) {
        if (error == null || !enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        Map<String, Object> report = CrashReport.payload(AtlasCore.newEventId(), AtlasCore.iso(now),
                sessionId, mappingId, CrashReport.MECHANISM_RECORDED, true, error, null, null);
        scope.writeTo(report);
        report.put("context", context(now));

        AtlasCore.Batch batch = core.batch().add("error", report);

        if (errors.getAndIncrement() == 0) {
            batch.add("session", SessionItems.errored(AtlasCore.newEventId(), sessionId, AtlasCore.iso(startedAtMs)));
        }

        batch.enqueue();
    }

    /** A death the OS recorded, or the watchdog saw: an ANR, an OOM kill, a
     * signal from outside, a native crash. `sessionStatus` is the end to fold
     * into that session's day. */
    void reportExit(long atMs, String exitSessionId, String mechanismType, String type, String message,
                    List<Object> frames, String sessionStatus) {
        if (!enabled) {
            return;
        }

        Map<String, Object> report = CrashReport.fromExit(AtlasCore.newEventId(), AtlasCore.iso(atMs),
                exitSessionId, mappingId, mechanismType, type, message, frames);

        AtlasCore.Batch batch = core.batch().add("crash", report);

        if (exitSessionId != null) {
            batch.add("session", SessionItems.ended(AtlasCore.newEventId(), exitSessionId, sessionStatus, null, 0, -1));
        }

        batch.enqueue();
    }

    /** The ANR watchdog's convenience wrapper. */
    void reportAnr(long atMs, String anrSessionId, String description, List<Object> mainFrames) {
        reportExit(atMs, anrSessionId, CrashReport.MECHANISM_ANR, "ANR", description, mainFrames, "abnormal");
    }

    /**
     * A native crash the C handler wrote to `path` in an earlier run. Read at
     * the next start, off the main thread, then the file is removed.
     */
    void reportPendingNative(File path, File scopeSnapshot, String crashedSession) {
        if (!enabled || !path.exists()) {
            return;
        }

        Map<String, Object> report = NativeReport.read(path, mappingId);
        path.delete();

        if (report == null) {
            return;
        }

        // The dead process's keys, breadcrumbs and log, as it last wrote them.
        if (scopeSnapshot != null && scopeSnapshot.exists()) {
            try {
                byte[] bytes = new byte[(int) Math.min(scopeSnapshot.length(), 256L * 1024L)];
                FileInputStream stream = new FileInputStream(scopeSnapshot);

                try {
                    int got = stream.read(bytes);
                    Map<String, Object> saved = dev.appatlas.sdk.core.JsonReader.object(new String(bytes, 0, Math.max(got, 0), "UTF-8"));

                    for (Map.Entry<String, Object> entry : saved.entrySet()) {
                        report.put(entry.getKey(), entry.getValue());
                    }
                } finally {
                    stream.close();
                }
            } catch (Throwable unreadable) {
                // The report goes without it.
            }
        }

        AtlasCore.Batch batch = core.batch().add("crash", report);

        if (crashedSession != null) {
            batch.add("session", SessionItems.ended(AtlasCore.newEventId(), crashedSession, "crashed", null, 0, -1));
        }

        batch.enqueue();
    }

    private Map<String, Object> context(long now) {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("startedAt", AtlasCore.iso(startedAtMs));
        context.put("timeToCrashMs", now - startedAtMs);

        Runtime runtime = Runtime.getRuntime();
        context.put("heapFreeBytes", runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory());

        try {
            Map<String, Object> platformFacts = platform.context();

            if (platformFacts != null) {
                context.putAll(platformFacts);
            }
        } catch (Throwable moody) {
            // The platform's facts are a bonus; the report goes without them.
        }

        return context;
    }

    private void leaveMarker(long timeToCrashMs) {
        try {
            stateDir.mkdirs();
            FileOutputStream stream = new FileOutputStream(new File(stateDir, MARKER));

            try {
                stream.write(String.valueOf(timeToCrashMs).getBytes("UTF-8"));
            } finally {
                stream.close();
            }
        } catch (Throwable lost) {
            // The report itself is already on disk.
        }
    }

    /** The previous run's time-to-crash, or -1 when it did not crash. Read once. */
    private long takeMarker() {
        File marker = new File(stateDir, MARKER);

        if (!marker.exists()) {
            return -1L;
        }

        long timeToCrash = Long.MAX_VALUE;

        try {
            byte[] bytes = new byte[32];
            FileInputStream stream = new FileInputStream(marker);

            try {
                int got = stream.read(bytes);
                timeToCrash = Long.parseLong(new String(bytes, 0, Math.max(got, 0), "UTF-8").trim());
            } finally {
                stream.close();
            }
        } catch (Throwable unreadable) {
            // It existed: the run crashed, only how soon is unknown.
        }

        marker.delete();

        return timeToCrash;
    }
}
