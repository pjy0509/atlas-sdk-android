package dev.appatlas.sdk.crash;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.appatlas.sdk.core.AtlasCore;

/**
 * What the app told us about its own state: a user id, custom keys, and a
 * ring of breadcrumbs. Bounded everywhere, because it rides every report and
 * is copied on a thread that is about to die.
 */
final class CrashScope {

    static final int MAX_KEYS = 64;
    static final int MAX_VALUE_CHARS = 1024;
    static final int MAX_BREADCRUMBS = 100;
    static final int MAX_LOG_CHARS = 64 * 1024;

    private String userId;
    private final Map<String, String> keys = new LinkedHashMap<String, String>();
    private final Map<String, Object>[] ring = newRing();
    private int written;
    private final StringBuilder log = new StringBuilder();

    @SuppressWarnings("unchecked")
    private static Map<String, Object>[] newRing() {
        return new Map[MAX_BREADCRUMBS];
    }

    synchronized void setUserId(String id) {
        userId = id == null || id.length() == 0 ? null : clip(id, 128);
    }

    synchronized void setKey(String name, String value) {
        if (name == null) {
            return;
        }

        if (value == null) {
            keys.remove(clip(name, 64));

            return;
        }

        String key = clip(name, 64);

        if (keys.containsKey(key) || keys.size() < MAX_KEYS) {
            keys.put(key, clip(value, MAX_VALUE_CHARS));
        }
    }

    synchronized void leaveBreadcrumb(String category, String message, String level, long atMs) {
        Map<String, Object> crumb = new LinkedHashMap<String, Object>();
        crumb.put("ts", AtlasCore.iso(atMs));
        crumb.put("category", clip(category == null ? "" : category, 40));
        crumb.put("level", level == null ? "info" : clip(level, 10));
        crumb.put("message", clip(message == null ? "" : message, 500));

        ring[written++ % MAX_BREADCRUMBS] = crumb;
    }

    /** A rolling log: the newest 64 KB of lines, the oldest dropped whole. */
    synchronized void log(String line, long atMs) {
        if (line == null) {
            return;
        }

        log.append(AtlasCore.iso(atMs)).append(' ').append(clip(line, 4096)).append('\n');

        if (log.length() > MAX_LOG_CHARS) {
            int cut = log.indexOf("\n", log.length() - MAX_LOG_CHARS);
            log.delete(0, cut < 0 ? log.length() - MAX_LOG_CHARS : cut + 1);
        }
    }

    /**
     * Writes the scope as JSON to `file`: what a native crash, read only at the
     * next start, needs from the process that died. Off the caller's thread.
     */
    void persistTo(File file) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        writeTo(snapshot);

        try {
            file.getParentFile().mkdirs();
            File fresh = new File(file.getPath() + ".tmp");
            FileOutputStream stream = new FileOutputStream(fresh);

            try {
                stream.write(dev.appatlas.sdk.core.Json.write(snapshot).getBytes("UTF-8"));
            } finally {
                stream.close();
            }

            // Rename, so a crash mid-write never leaves a half snapshot.
            fresh.renameTo(file);
        } catch (Throwable lost) {
            // The next change writes again.
        }
    }

    /** Copies the scope into a report payload; absent parts are left out. */
    synchronized void writeTo(Map<String, Object> payload) {
        if (userId != null) {
            Map<String, Object> user = new LinkedHashMap<String, Object>();
            user.put("id", userId);
            payload.put("user", user);
        }

        if (!keys.isEmpty()) {
            payload.put("keys", new LinkedHashMap<String, Object>(keys));
        }

        if (written > 0) {
            List<Object> crumbs = new ArrayList<Object>();
            int count = Math.min(written, MAX_BREADCRUMBS);

            // Oldest first, as they happened.
            for (int i = written - count; i < written; i++) {
                crumbs.add(ring[i % MAX_BREADCRUMBS]);
            }

            payload.put("breadcrumbs", crumbs);
        }

        if (log.length() > 0) {
            payload.put("log", log.toString());
        }
    }

    private static String clip(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) : text;
    }
}
