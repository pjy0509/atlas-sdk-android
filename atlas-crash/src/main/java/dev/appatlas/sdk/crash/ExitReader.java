package dev.appatlas.sdk.crash;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * How the previous process actually died, read from the OS's own record at
 * the next start (API 30+ ApplicationExitInfo). This is what catches the
 * deaths an in-process handler never sees: an ANR, a low-memory kill, a
 * SIGKILL from outside, a native crash on a device where the signal handler
 * did not run. It is the Android peer of iOS MetricKit — the ground truth
 * for the ends no live code can report on itself.
 *
 * Every abnormal reason becomes an issue; a normal exit (the user left, an
 * update, a self-requested stop) is silent. One report per exit, tracked by
 * the newest timestamp already seen, so nothing is reported twice.
 */
@TargetApi(30)
final class ExitReader {

    private static final String PREFS = "dev.appatlas.sdk";
    private static final String KEY_LAST_EXIT = "crash.lastExit";
    private static final int MAX_TRACE_BYTES = 2 * 1024 * 1024;
    // Older than this, the build it names is long gone.
    private static final long MAX_AGE_MS = 91L * 24 * 60 * 60 * 1000;

    private ExitReader() {
    }

    /** Stamps this process so a later exit record can name its session. */
    static void stampSession(Context app, String sessionId) {
        try {
            manager(app).setProcessStateSummary(sessionId.getBytes(Charset.forName("UTF-8")));
        } catch (Exception limited) {
            // Rate-limited by the OS: the exit still reports, without a session.
        }
    }

    /** Off the main thread: it reads trace files. */
    static void report(Context app, CrashReporter reporter, boolean nativeHandledElsewhere) {
        try {
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long reportedUntil = prefs.getLong(KEY_LAST_EXIT, 0L);
            long newest = reportedUntil;
            long oldest = System.currentTimeMillis() - MAX_AGE_MS;

            List<ApplicationExitInfo> exits = manager(app).getHistoricalProcessExitReasons(null, 0, 16);

            for (ApplicationExitInfo exit : exits) {
                if (exit.getTimestamp() <= reportedUntil || exit.getTimestamp() < oldest) {
                    continue;
                }

                newest = Math.max(newest, exit.getTimestamp());
                reportOne(reporter, exit, nativeHandledElsewhere);
            }

            // Skipped exits count as handled too, or they return every start.
            if (newest > reportedUntil) {
                prefs.edit().putLong(KEY_LAST_EXIT, newest).apply();
            }
        } catch (Exception moody) {
            // An OEM ActivityManager that throws costs the records, not the app.
        }
    }

    private static void reportOne(CrashReporter reporter, ApplicationExitInfo exit, boolean nativeHandledElsewhere) {
        int reason = exit.getReason();
        String session = session(exit);
        String description = exit.getDescription();

        switch (reason) {
            case ApplicationExitInfo.REASON_ANR: {
                List<Object> frames = AnrTrace.mainFrames(trace(exit));

                // A non-actionable ANR (no Java frames on main) is one Play
                // Console does not report either.
                if (!frames.isEmpty()) {
                    reporter.reportExit(exit.getTimestamp(), session, CrashReport.MECHANISM_ANR, "ANR",
                            description == null ? "Application not responding" : description, frames, "abnormal");
                }

                break;
            }

            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                // Our own signal handler wrote a far richer report (full
                // unwind, build ids); the tombstone is only the fallback for
                // a device where the handler did not run.
                if (!nativeHandledElsewhere) {
                    reporter.reportExit(exit.getTimestamp(), session, CrashReport.MECHANISM_EXIT_INFO, "NativeCrash",
                            description == null ? "Native crash" : description,
                            AnrTrace.mainFrames(trace(exit)), "crashed");
                }

                break;

            case ApplicationExitInfo.REASON_LOW_MEMORY:
                reporter.reportExit(exit.getTimestamp(), session, CrashReport.MECHANISM_EXIT_INFO, "OutOfMemory",
                        "The system killed the app while low on memory", null, "abnormal");
                break;

            case ApplicationExitInfo.REASON_SIGNALED:
                // A signal from outside — the low-memory killer's SIGKILL is
                // the common one, and the in-process handler can never see it.
                reporter.reportExit(exit.getTimestamp(), session, CrashReport.MECHANISM_EXIT_INFO, "Killed",
                        "Killed by signal " + exit.getStatus() + (description == null ? "" : ": " + description),
                        null, "abnormal");
                break;

            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                reporter.reportExit(exit.getTimestamp(), session, CrashReport.MECHANISM_EXIT_INFO, "ExcessiveResourceUsage",
                        description == null ? "Killed for excessive resource use" : description, null, "abnormal");
                break;

            default:
                // Normal ends (the user left, an update, a self-requested
                // exit, a permission change) say nothing.
                break;
        }
    }

    private static ActivityManager manager(Context app) {
        return (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
    }

    private static String session(ApplicationExitInfo exit) {
        byte[] summary = exit.getProcessStateSummary();

        return summary == null || summary.length == 0 || summary.length > 36
                ? null : new String(summary, Charset.forName("UTF-8"));
    }

    private static String trace(ApplicationExitInfo exit) {
        try {
            InputStream stream = exit.getTraceInputStream();

            if (stream == null) {
                return null;
            }

            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int got;

                while ((got = stream.read(chunk)) != -1 && out.size() < MAX_TRACE_BYTES) {
                    out.write(chunk, 0, got);
                }

                return out.toString("UTF-8");
            } finally {
                stream.close();
            }
        } catch (Exception unreadable) {
            return null;
        }
    }
}
