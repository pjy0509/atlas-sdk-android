package dev.appatlas.sdk.crash;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The `session` item in its three moments. The server keeps day totals, not
 * sessions, so each send says only what it adds: `init` counts a start, an
 * end state counts that end, and the first handled error counts once.
 */
final class SessionItems {

    private SessionItems() {
    }

    static Map<String, Object> started(String eventId, String sid, String startedIso) {
        Map<String, Object> payload = base(eventId, sid, "ok", startedIso);
        payload.put("init", Boolean.TRUE);

        return payload;
    }

    static Map<String, Object> errored(String eventId, String sid, String startedIso) {
        Map<String, Object> payload = base(eventId, sid, "ok", startedIso);
        payload.put("errors", 1);

        return payload;
    }

    /** `status` is `crashed` or `abnormal`; a clean exit is never sent. */
    static Map<String, Object> ended(String eventId, String sid, String status, String startedIso,
                                     int errors, long durationMs) {
        Map<String, Object> payload = base(eventId, sid, status, startedIso);
        payload.put("errors", errors);

        if (durationMs >= 0) {
            payload.put("duration", durationMs / 1000L);
        }

        return payload;
    }

    private static Map<String, Object> base(String eventId, String sid, String status, String startedIso) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", eventId);
        payload.put("sid", sid);
        payload.put("status", status);

        if (startedIso != null) {
            payload.put("started", startedIso);
        }

        return payload;
    }
}
