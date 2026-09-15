package dev.appatlas.sdk;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The device and app facts every module shares, snapshotted once at start
 * and carried in each envelope's header as `device` and `app` blocks. All of
 * it is non-identifying context — the standard crash-SDK set, nothing that
 * names a device or a person. Every probe is guarded: on some ROM, each of
 * these has thrown for somebody.
 */
final class DeviceContext {

    private DeviceContext() {
    }

    static Map<String, Object> snapshot(Context app) {
        // The common keys come first, in the order every SDK writes them;
        // platform extras follow.
        Map<String, Object> device = new LinkedHashMap<String, Object>();
        device.put("os", "android");
        device.put("osVersion", Build.VERSION.RELEASE);
        device.put("model", Build.MODEL);
        device.put("arch", arch());
        device.put("locale", Locale.getDefault().toString());
        device.put("timezone", TimeZone.getDefault().getID());
        device.put("apiLevel", Build.VERSION.SDK_INT);
        device.put("manufacturer", Build.MANUFACTURER);

        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("device", device);
        context.put("app", appBlock(app));

        return context;
    }

    private static Map<String, Object> appBlock(Context app) {
        Map<String, Object> block = new LinkedHashMap<String, Object>();

        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            block.put("version", info.versionName);
            // The deprecated int field reaches API 16; the long variant is 28+.
            block.put("build", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (Exception unreadable) {
            // Our own package should always resolve; a ROM that disagrees
            // still gets its events, just unversioned.
        }

        String installer = installer(app);

        if (installer != null) {
            block.put("installer", installer);
        }

        return block;
    }

    /**
     * Which store put the app here — sentry's stance: the deprecated call on
     * every level, because the replacement's full answer needs a system
     * permission. Null means sideloaded (or a ROM that would not say).
     */
    @SuppressWarnings("deprecation")
    private static String installer(Context app) {
        try {
            return app.getPackageManager().getInstallerPackageName(app.getPackageName());
        } catch (Exception moody) {
            return null;
        }
    }

    private static String arch() {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                String[] abis = Build.SUPPORTED_ABIS;

                return abis != null && abis.length > 0 ? abis[0] : "unknown";
            }

            return Build.CPU_ABI;
        } catch (Exception hidden) {
            return "unknown";
        }
    }
}
