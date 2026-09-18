package dev.appatlas.sdk;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.lang.reflect.Method;
import java.util.UUID;

import dev.appatlas.sdk.core.AtlasCore;

/**
 * The entry point: one line in Application.onCreate. Modules (links, crash,
 * push) attach to the core this creates; none of them touch the network or
 * the disk on their own.
 *
 * <pre>Atlas.start(this, "sdk_…");</pre>
 */
public final class Atlas {

    private static final String PREFS = "dev.appatlas.sdk";
    private static final String KEY_INSTALL_ID = "installId";
    private static final String DEFAULT_BASE_URL = "https://appatlas.dev";

    private static volatile AtlasCore core;

    private Atlas() {
    }

    public static void start(Context context, String sdkKey) {
        start(context, sdkKey, DEFAULT_BASE_URL);
    }

    /** The base URL override exists for self-hosted and staging servers. */
    public static synchronized void start(Context context, String sdkKey, String baseUrl) {
        if (core != null) {
            return;
        }

        Context app = context.getApplicationContext();
        Tls.enableTls12();

        core = new AtlasCore(
                "atlas-android",
                baseUrl,
                sdkKey,
                // Per-process, so a multi-process app never has two workers
                // fighting over one queue (Crashlytics's FileStore split).
                new File(app.getCacheDir(), "atlas/queue/" + sanitize(processName())),
                installId(app),
                DeviceContext.snapshot(app)
        );
        // Whatever a previous run could not send leaves now.
        core.flushSoon();

        // Modules on the classpath wake with the core; an artifact the app
        // did not ship is simply absent (bugsnag's plugin-loading shape).
        // Crash first: whatever a later module breaks is then already caught.
        bootModule("dev.appatlas.sdk.crash.AtlasCrash", app);
        bootModule("dev.appatlas.sdk.links.AtlasLinks", app);
    }

    private static void bootModule(String name, Context app) {
        try {
            Class.forName(name).getMethod("boot", Context.class).invoke(null, app);
        } catch (Exception absent) {
            // Not shipped, or refused to start: the core owes it nothing.
        }
    }

    /**
     * This process's name: the public API on 28+, the platform's own private
     * one below — the same ladder bugsnag and firebase-sessions climb. An
     * unknown name lands every process in one "main" queue, which is only
     * wrong for the multi-process minority and merely slower there.
     */
    private static String processName() {
        if (Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName();
        }

        try {
            Method current = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod(Build.VERSION.SDK_INT >= 18 ? "currentProcessName" : "currentPackageName");
            current.setAccessible(true);
            Object name = current.invoke(null);

            if (name instanceof String) {
                return (String) name;
            }
        } catch (Exception hidden) {
            // A ROM that renamed the private API: fall through.
        }

        return "main";
    }

    private static String sanitize(String name) {
        StringBuilder out = new StringBuilder(name.length());

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? c : '_');
        }

        return out.length() > 40 ? out.substring(out.length() - 40) : out.toString();
    }

    /** The running core, for modules; null before start (they stay quiet). */
    public static AtlasCore core() {
        return core;
    }

    /**
     * An install-scoped random id: minted on first start, gone with the app.
     * Never a device identifier — nothing here reads one.
     */
    private static String installId(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY_INSTALL_ID, null);

        if (existing != null) {
            return existing;
        }

        String minted = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        prefs.edit().putString(KEY_INSTALL_ID, minted).apply();

        return minted;
    }
}
