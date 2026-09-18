package dev.appatlas.sdk.crash;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * The ANR detector for API < 30, where the OS keeps no exit record to read.
 * A background thread posts a token to the main looper every {@link #POLL_MS}
 * and checks that the previous one came back; a token still pending after
 * {@link #TIMEOUT_MS} is the main thread wedged.
 *
 * The traps a naive watchdog falls into, closed the way Crashlytics and
 * bugsnag close them: a debugger pause is not an ANR, a background app is not
 * an ANR, and the reading is cross-checked against the system's own
 * error-state list before it is believed. One report per freeze.
 */
final class Watchdog implements Runnable {

    static final long POLL_MS = 500L;
    static final long TIMEOUT_MS = 5000L;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CrashReporter reporter;
    private final Thread thread;

    // Written on the main thread, read on the watchdog: the last tick the
    // main looper acknowledged. `volatile` is the whole synchronisation.
    private volatile long lastAck;
    private volatile boolean stop;
    private boolean firedForThisFreeze;

    Watchdog(Context app, CrashReporter reporter) {
        this.app = app;
        this.reporter = reporter;
        this.thread = new Thread(this, "atlas-crash-watchdog");
        this.thread.setDaemon(true);
    }

    void start() {
        lastAck = SystemClock.uptimeMillis();
        thread.start();
    }

    void stop() {
        stop = true;
        thread.interrupt();
    }

    @Override
    public void run() {
        while (!stop) {
            long posted = SystemClock.uptimeMillis();
            main.postAtFrontOfQueue(new Runnable() {
                @Override
                public void run() {
                    lastAck = SystemClock.uptimeMillis();
                }
            });

            try {
                Thread.sleep(TIMEOUT_MS);
            } catch (InterruptedException woken) {
                return;
            }

            // The main thread answered within the window: no freeze, and any
            // earlier freeze has cleared, so the next one may fire again.
            if (lastAck >= posted) {
                firedForThisFreeze = false;
                continue;
            }

            // A debugger paused the main thread: not a freeze the user feels.
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                continue;
            }

            if (!firedForThisFreeze && confirmedBySystem()) {
                firedForThisFreeze = true;
                reporter.reportAnr(System.currentTimeMillis(), reporter.sessionId(),
                        "Application not responding: the main thread did not answer within "
                                + TIMEOUT_MS + "ms", mainThreadFrames());
            }
        }
    }

    /**
     * The system's own view, the false-positive filter every shipping
     * watchdog uses: a real ANR shows up in getProcessesInErrorState with
     * NOT_RESPONDING. Absent (a ROM that returns null, or a freeze the system
     * has not escalated yet), we do not cry wolf.
     */
    private boolean confirmedBySystem() {
        try {
            ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.ProcessErrorStateInfo> errors = manager.getProcessesInErrorState();

            if (errors == null) {
                return false;
            }

            int pid = android.os.Process.myPid();

            for (ActivityManager.ProcessErrorStateInfo error : errors) {
                if (error.pid == pid && error.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING) {
                    return true;
                }
            }
        } catch (Throwable moody) {
            // A device that will not answer is not a device we accuse.
        }

        return false;
    }

    private List<Object> mainThreadFrames() {
        try {
            return CrashReport.frames(Looper.getMainLooper().getThread().getStackTrace());
        } catch (Throwable gone) {
            return new java.util.ArrayList<Object>();
        }
    }
}
