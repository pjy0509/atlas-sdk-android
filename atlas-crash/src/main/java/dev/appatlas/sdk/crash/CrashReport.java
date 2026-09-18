package dev.appatlas.sdk.crash;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A throwable as the `crash` / `error` item the server groups on
 * (the server repo's docs/sdk-crash.md §3). The exception already holds its
 * stack, so nothing here unwinds anything.
 *
 * FROZEN once shipped: the mechanism strings. The server keys issue kinds on
 * them, and a rename regroups every open issue.
 */
final class CrashReport {

    static final String MECHANISM_UNCAUGHT = "uncaughtExceptionHandler";
    static final String MECHANISM_RECORDED = "recordError";
    static final String MECHANISM_ANR = "anr";
    // A death the OS recorded and this SDK only heard about at the next start.
    static final String MECHANISM_EXIT_INFO = "exitInfo";

    static final int MAX_CAUSES = 8;
    static final int MAX_FRAMES = 256;
    static final int MAX_THREADS = 128;

    private CrashReport() {
    }

    static Map<String, Object> payload(String eventId, String crashedAtIso, String sessionId, String mappingId,
                                       String mechanism, boolean handled, Throwable error,
                                       Map<Thread, StackTraceElement[]> threads, Thread crashed) {
        Map<String, Object> payload = head(eventId, crashedAtIso, sessionId, mappingId, mechanism, handled);
        payload.put("exceptions", exceptions(error));

        if (threads != null) {
            payload.put("threads", threads(threads, crashed, error));
        }

        return payload;
    }

    /**
     * A death with no throwable: an ANR, an OOM kill, a signal from outside.
     * The exception type is what groups it, so an out-of-memory kill needs no
     * frames to become its own issue. `mechanismType` is frozen; the OS's own
     * trace, when it left one, stands in for the stack.
     */
    static Map<String, Object> fromExit(String eventId, String crashedAtIso, String sessionId, String mappingId,
                                        String mechanismType, String type, String message, List<Object> frames) {
        Map<String, Object> raised = new LinkedHashMap<String, Object>();
        raised.put("type", type);
        raised.put("message", message == null ? "" : message);
        raised.put("frames", frames == null ? new ArrayList<Object>() : frames);

        List<Object> exceptions = new ArrayList<Object>();
        exceptions.add(raised);

        Map<String, Object> payload = head(eventId, crashedAtIso, sessionId, mappingId, mechanismType, false);
        payload.put("exceptions", exceptions);

        return payload;
    }

    private static Map<String, Object> head(String eventId, String crashedAtIso, String sessionId,
                                            String mappingId, String mechanism, boolean handled) {
        Map<String, Object> how = new LinkedHashMap<String, Object>();
        how.put("type", mechanism);
        how.put("handled", handled);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", eventId);
        payload.put("crashedAt", crashedAtIso);

        if (sessionId != null) {
            payload.put("sessionId", sessionId);
        }
        if (mappingId != null) {
            payload.put("mappingId", mappingId);
        }

        payload.put("mechanism", how);

        return payload;
    }

    /** Outermost first, down getCause(); the last entry is the root cause. */
    private static List<Object> exceptions(Throwable error) {
        List<Object> chain = new ArrayList<Object>();
        // A cause cycle is legal Java and would otherwise never end.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());

        for (Throwable link = error; link != null && chain.size() < MAX_CAUSES && seen.add(link); link = link.getCause()) {
            Map<String, Object> raised = new LinkedHashMap<String, Object>();
            raised.put("type", link.getClass().getName());
            raised.put("message", message(link));
            raised.put("frames", frames(link.getStackTrace()));
            chain.add(raised);
        }

        return chain;
    }

    private static String message(Throwable link) {
        try {
            String message = link.getMessage();

            return message == null ? "" : message.length() > 1000 ? message.substring(0, 1000) : message;
        } catch (Throwable overridden) {
            // getMessage() is app code, and this runs while the app is dying.
            return "";
        }
    }

    static List<Object> frames(StackTraceElement[] trace) {
        List<Object> frames = new ArrayList<Object>();

        for (int i = 0; trace != null && i < trace.length && i < MAX_FRAMES; i++) {
            frames.add(frame(trace[i].getClassName(), trace[i].getMethodName(),
                    trace[i].getFileName(), trace[i].getLineNumber()));
        }

        return frames;
    }

    static Map<String, Object> frame(String module, String function, String file, int line) {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("module", module);
        frame.put("function", function);

        if (file != null) {
            frame.put("file", file);
        }
        // Native and unknown lines are negative; they say nothing.
        if (line > 0) {
            frame.put("line", line);
        }

        return frame;
    }

    private static List<Object> threads(Map<Thread, StackTraceElement[]> all, Thread crashed, Throwable error) {
        List<Object> threads = new ArrayList<Object>();

        for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            if (threads.size() >= MAX_THREADS) {
                break;
            }

            Map<String, Object> thread = new LinkedHashMap<String, Object>();
            thread.put("name", entry.getKey().getName());
            boolean dying = entry.getKey() == crashed;
            thread.put("crashed", dying);
            // Sampled now, the dying thread shows this handler; where it died
            // is the exception's own trace.
            thread.put("frames", frames(dying ? error.getStackTrace() : entry.getValue()));
            threads.add(thread);
        }

        return threads;
    }
}
