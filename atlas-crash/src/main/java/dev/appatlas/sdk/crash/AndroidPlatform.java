package dev.appatlas.sdk.crash;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.StatFs;
import android.os.StrictMode;
import android.os.SystemClock;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.appatlas.sdk.core.AtlasCore;

/**
 * The device's state at the moment of a report: what a developer asks first
 * about a crash they cannot reproduce. Every probe is guarded — on some ROM
 * each of these has thrown for somebody — and a probe that fails is left out,
 * never invented.
 */
final class AndroidPlatform implements CrashPlatform, Application.ActivityLifecycleCallbacks {

    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk", "/data/local/xbin/su",
    };

    private final Context app;
    private final AtomicInteger started = new AtomicInteger();
    private volatile String screen;

    AndroidPlatform(Context app) {
        this.app = app;

        if (app instanceof Application) {
            ((Application) app).registerActivityLifecycleCallbacks(this);
        }
    }

    @Override
    public Map<String, Object> context() {
        Map<String, Object> facts = new LinkedHashMap<String, Object>();
        facts.put("foreground", started.get() > 0);

        if (screen != null) {
            facts.put("screen", screen);
        }

        try {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            ((ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
            facts.put("memoryFreeBytes", memory.availMem);
            facts.put("memoryTotalBytes", memory.totalMem);
            facts.put("lowMemory", memory.lowMemory);
        } catch (Throwable moody) {
            // Left out.
        }

        try {
            facts.put("diskFreeBytes", diskFree(app.getFilesDir()));
        } catch (Throwable moody) {
            // Left out.
        }

        try {
            int orientation = app.getResources().getConfiguration().orientation;
            facts.put("orientation", orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait");
        } catch (Throwable moody) {
            // Left out.
        }

        // To the second: the wall clock minus uptime jitters by milliseconds
        // between launches, and equal boots must compare equal.
        facts.put("bootTime", AtlasCore.iso(
                (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000L * 1000L));
        facts.put("rooted", rooted());

        return facts;
    }

    @Override
    public void beforeDiskWrite() {
        try {
            // A strict app kills disk writes on the main thread; this one is the point.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);
        } catch (Throwable moody) {
            // Then the write takes its chances.
        }
    }

    @Override
    public Thread mainThread() {
        return Looper.getMainLooper().getThread();
    }

    @SuppressWarnings("deprecation")
    private static long diskFree(File dir) {
        StatFs stat = new StatFs(dir.getPath());

        return Build.VERSION.SDK_INT >= 18
                ? stat.getAvailableBytes()
                : (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
    }

    private static boolean rooted() {
        try {
            if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
                return true;
            }

            for (String path : SU_PATHS) {
                if (new File(path).exists()) {
                    return true;
                }
            }
        } catch (Throwable hidden) {
            // Unknown reads as not rooted.
        }

        return false;
    }

    @Override
    public void onActivityStarted(Activity activity) {
        started.incrementAndGet();
    }

    @Override
    public void onActivityStopped(Activity activity) {
        started.decrementAndGet();
    }

    @Override
    public void onActivityResumed(Activity activity) {
        // The class on screen: the first thing asked about a crash nobody can reproduce.
        screen = activity.getClass().getName();
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
