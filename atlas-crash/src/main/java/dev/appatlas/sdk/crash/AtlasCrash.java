package dev.appatlas.sdk.crash;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;

import dev.appatlas.sdk.Atlas;
import dev.appatlas.sdk.core.AtlasCore;

/**
 * The crash module: uncaught exceptions and recorded ANRs on their own,
 * handled errors and context when the app offers them. Nothing to wire
 * beyond Atlas.start:
 *
 * <pre>
 * Atlas.start(this, "sdk_…");
 * AtlasCrash.setUserId("u-123");
 * AtlasCrash.setKey("screen", "checkout");
 * AtlasCrash.leaveBreadcrumb("cart", "add");
 * AtlasCrash.log("cart total recomputed");
 * AtlasCrash.recordError(error);
 * </pre>
 */
public final class AtlasCrash {

    /** Manifest meta-data the build injects: which mapping.txt reads this binary. */
    private static final String META_MAPPING_ID = "dev.appatlas.sdk.mappingId";
    private static final String PREFS = "dev.appatlas.sdk";
    private static final String KEY_ENABLED = "crash.enabled";
    private static final String KEY_NATIVE_SESSION = "crash.nativeSession";
    // The C handler writes here; read and cleared at the next start.
    private static final String NATIVE_REPORT = "atlas/native-crash.txt";
    // The scope as last written, for a native crash to carry at the next start.
    private static final String SCOPE_SNAPSHOT = "atlas/crash-scope.json";
    private static final long SNAPSHOT_DEBOUNCE_MS = 1000L;

    // Context set before start is kept: an app may name its user first.
    private static final CrashScope SCOPE = new CrashScope();
    private static volatile CrashReporter reporter;
    private static volatile Context appContext;
    private static volatile File scopeFile;
    private static final java.util.concurrent.ScheduledExecutorService SNAPSHOTS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "atlas-crash-scope");
                    thread.setDaemon(true);

                    return thread;
                }
            });
    private static java.util.concurrent.ScheduledFuture<?> pendingSnapshot;

    private AtlasCrash() {
    }

    /** Called by Atlas.start through reflection; not application API. */
    public static synchronized void boot(Context context) {
        AtlasCore core = Atlas.core();

        if (reporter != null || core == null) {
            return;
        }

        final Context app = context.getApplicationContext();
        appContext = app;

        boolean enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
        final CrashReporter started = new CrashReporter(core, mappingId(app), SCOPE, new AndroidPlatform(app),
                new File(app.getFilesDir(), "atlas/crash"));
        started.setEnabled(enabled);
        started.install();
        reporter = started;

        if (!enabled) {
            return;
        }

        final File nativeReport = new File(app.getFilesDir(), NATIVE_REPORT);
        final File snapshot = new File(app.getFilesDir(), SCOPE_SNAPSHOT);
        final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // The session that was running when a pending native report was written.
        final String crashedSession = prefs.getString(KEY_NATIVE_SESSION, null);
        scopeFile = snapshot;

        if (nativeReport.exists()) {
            started.noteNativeCrashPending();
        }

        // Install the native signal handlers, and remember this session so a
        // native crash now can name it at the next start.
        boolean nativeActive = installNative(nativeReport);

        if (nativeActive) {
            prefs.edit().putString(KEY_NATIVE_SESSION, started.sessionId()).apply();
        }

        // API 30+: the OS keeps the truth about how the last process died —
        // an ANR, a low-memory kill, a SIGKILL. Below that, a main-looper
        // watchdog stands in for ANR only.
        if (Build.VERSION.SDK_INT >= 30) {
            ExitReader.stampSession(app, started.sessionId());
        } else {
            new Watchdog(app, started).start();
        }

        final boolean skipNativeExit = nativeActive;
        Thread deaths = new Thread(new Runnable() {
            @Override
            public void run() {
                started.reportPendingNative(nativeReport, snapshot, crashedSession);
                // This run's scope starts fresh on disk.
                SCOPE.persistTo(snapshot);

                if (Build.VERSION.SDK_INT >= 30) {
                    ExitReader.report(app, started, skipNativeExit);
                }
            }
        }, "atlas-crash-exits");
        deaths.setDaemon(true);
        deaths.start();
    }

    /**
     * atlas-crash-ndk, when the app ships it: found by name, the way Atlas
     * finds this module, so the native artifact stays optional and its floor
     * (21) stays its own.
     */
    private static boolean installNative(File reportPath) {
        try {
            reportPath.getParentFile().mkdirs();
            Object result = Class.forName("dev.appatlas.sdk.crash.ndk.NativeBridge")
                    .getMethod("install", String.class).invoke(null, reportPath.getAbsolutePath());

            return Integer.valueOf(0).equals(result);
        } catch (Throwable absent) {
            // Not shipped, or an ABI without the .so: the JVM crash and the
            // exit-record paths still stand.
            return false;
        }
    }

    /** Your own id for the signed-in user; null clears it. Never required. */
    public static void setUserId(String id) {
        SCOPE.setUserId(id);
        snapshotSoon();
    }

    /** Up to 64 keys ride every report; a null value removes the key. */
    public static void setKey(String name, String value) {
        SCOPE.setKey(name, value);
        snapshotSoon();
    }

    /** The last 100 are kept and attached to the next report. */
    public static void leaveBreadcrumb(String category, String message) {
        SCOPE.leaveBreadcrumb(category, message, null, System.currentTimeMillis());
        snapshotSoon();
    }

    /** A line in the rolling log: the newest 64 KB ride the next report. */
    public static void log(String line) {
        SCOPE.log(line, System.currentTimeMillis());
        snapshotSoon();
    }

    /**
     * The scope reaches disk within a second of a change, coalesced: a
     * native crash is read at the next start, from a process that is gone,
     * and this file is what it remembers by. Never on the caller's thread.
     */
    private static synchronized void snapshotSoon() {
        final File file = scopeFile;

        if (file == null || (pendingSnapshot != null && !pendingSnapshot.isDone())) {
            return;
        }

        pendingSnapshot = SNAPSHOTS.schedule(new Runnable() {
            @Override
            public void run() {
                SCOPE.persistTo(file);
            }
        }, SNAPSHOT_DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Consent: off, nothing is collected or sent, and the choice outlives the
     * process. On by default. Takes effect at once for reports; sessions and
     * ANR reading follow from the next start.
     */
    public static void setEnabled(boolean enabled) {
        Context app = appContext;

        if (app != null) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
        }

        CrashReporter current = reporter;

        if (current != null) {
            current.setEnabled(enabled);
        }
    }

    /** Whether the previous run ended in a crash this SDK recorded. */
    public static boolean crashedLastRun() {
        CrashReporter current = reporter;

        return current != null && current.crashedLastRun();
    }

    /** A caught exception worth knowing about. Quiet before Atlas.start. */
    public static void recordError(Throwable error) {
        CrashReporter current = reporter;

        if (current != null) {
            current.recordError(error);
        }
    }

    private static String mappingId(Context app) {
        try {
            ApplicationInfo info = app.getPackageManager()
                    .getApplicationInfo(app.getPackageName(), PackageManager.GET_META_DATA);
            Object value = info.metaData == null ? null : info.metaData.get(META_MAPPING_ID);

            return value instanceof String && ((String) value).length() > 0 ? (String) value : null;
        } catch (Exception unreadable) {
            // Unminified or unconfigured: frames are already readable.
            return null;
        }
    }
}
