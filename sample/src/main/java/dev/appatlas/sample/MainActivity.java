package dev.appatlas.sample;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.appatlas.sdk.crash.AtlasCrash;

/**
 * MainActivity.java: the launcher. `adb shell am start -n dev.appatlas.sample/.MainActivity --es case X`
 * dies the way X names, a second after the screen is up.
 */
public class MainActivity extends Activity {

    static {
        System.loadLibrary("boom");
    }

    private static native void nativeBoom();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        final String which = getIntent().getStringExtra("case");
        view.setText("Atlas sample: " + which);
        setContentView(view);

        AtlasCrash.leaveBreadcrumb("ui", "MainActivity " + which);
        AtlasCrash.log("case " + which);
        Log.i(SampleApplication.TAG, "case=" + which);

        if (which == null || "none".equals(which)) {
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                die(which);
            }
        }, 1500L);
    }

    private void die(String which) {
        if ("jvm-main".equals(which)) {
            throw new IllegalStateException("sample: uncaught on the main thread");
        }

        if ("jvm-thread".equals(which)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    throw new IllegalArgumentException("sample: uncaught on a worker thread");
                }
            }, "sample-worker").start();

            return;
        }

        if ("error".equals(which)) {
            try {
                throw new UnsupportedOperationException("sample: handled and recorded");
            } catch (UnsupportedOperationException error) {
                AtlasCrash.recordError(error);
            }

            return;
        }

        if ("native".equals(which)) {
            nativeBoom();

            return;
        }

        if ("oom".equals(which)) {
            // Exhausts the Java heap: an OutOfMemoryError, uncaught.
            List<byte[]> hoard = new ArrayList<byte[]>();

            while (true) {
                hoard.add(new byte[8 * 1024 * 1024]);
            }
        }

        if ("kill".equals(which)) {
            // Killed from outside, which the device script does with kill -9;
            // here only the pid is logged for it.
            Log.i(SampleApplication.TAG, "pid=" + android.os.Process.myPid());
        }
    }
}
