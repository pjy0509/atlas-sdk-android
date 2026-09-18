package dev.appatlas.sdk.crash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the line-based file the C handler wrote for a native crash and turns
 * it into a `crash` payload. Native frames carry the raw instruction address,
 * the address relative to the module's ELF base, and the module's build id —
 * everything the server needs to look the symbol up, and nothing it does not.
 *
 * FROZEN with the writer (atlas_crash.c): the line vocabulary.
 */
final class NativeReport {

    private NativeReport() {
    }

    // The kernel's si_code for a sent signal is <= 0; a fault is > 0. A file
    // that stops before "end" was interrupted mid-write and is dropped.
    static Map<String, Object> read(File file, String mappingId) {
        String signalName = null;
        String signalCode = null;
        String faultAddress = null;
        long crashedAtMs = System.currentTimeMillis();
        String threadName = null;
        long pid = -1;
        long tid = -2;
        boolean whole = false;
        // The CFI walk is the truer one; it replaces the frame-pointer walk when present.
        List<Object> fpFrames = new ArrayList<Object>();
        List<Object> cfiFrames = new ArrayList<Object>();
        List<Object> into = fpFrames;

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));

            try {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    String[] parts = line.split(" ");

                    if (line.startsWith("time ") && parts.length >= 2) {
                        crashedAtMs = parseLong(parts[1], crashedAtMs / 1000L) * 1000L;
                    } else if (line.startsWith("signal ") && parts.length >= 4) {
                        signalName = signalName(parseInt(parts[1]));
                        signalCode = parts[2];
                        faultAddress = "0x" + parts[3];
                    } else if (line.startsWith("pid ") && parts.length >= 4) {
                        pid = parseLong(parts[1], -1L);
                        tid = parseLong(parts[3], -2L);
                    } else if (line.startsWith("thread ") && parts.length >= 2) {
                        threadName = line.substring("thread ".length());
                    } else if (line.equals("unwind cfi")) {
                        into = cfiFrames;
                    } else if (line.equals("unwind fp")) {
                        into = fpFrames;
                    } else if (line.startsWith("frame ") && parts.length >= 5) {
                        into.add(nativeFrame(parts));
                    } else if (line.equals("end")) {
                        whole = true;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception unreadable) {
            return null;
        }

        List<Object> frames = cfiFrames.isEmpty() ? fpFrames : cfiFrames;

        if (!whole || frames.isEmpty() || signalName == null) {
            return null;
        }

        // The kernel names the main thread after the process, clipped to 15
        // bytes; a reader knows it as "main".
        return payload(signalName, signalCode, faultAddress, crashedAtMs, pid == tid ? "main" : threadName,
                frames, mappingId);
    }

    private static Map<String, Object> payload(String signalName, String signalCode, String faultAddress,
                                               long crashedAtMs, String threadName, List<Object> frames,
                                               String mappingId) {
        Map<String, Object> raised = new LinkedHashMap<String, Object>();
        raised.put("type", signalName);
        raised.put("message", "Fatal signal " + signalName + " at " + faultAddress);
        raised.put("frames", frames);

        List<Object> exceptions = new ArrayList<Object>();
        exceptions.add(raised);

        Map<String, Object> mechanism = new LinkedHashMap<String, Object>();
        mechanism.put("type", "signalHandler");
        mechanism.put("handled", Boolean.FALSE);
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("signal", signalName);
        meta.put("code", signalCode);
        meta.put("faultAddress", faultAddress);
        mechanism.put("native", meta);

        List<Object> threads = new ArrayList<Object>();
        Map<String, Object> crashedThread = new LinkedHashMap<String, Object>();
        crashedThread.put("name", threadName == null ? "unknown" : threadName);
        crashedThread.put("crashed", Boolean.TRUE);
        crashedThread.put("frames", frames);
        threads.add(crashedThread);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", dev.appatlas.sdk.core.AtlasCore.newEventId());
        payload.put("crashedAt", dev.appatlas.sdk.core.AtlasCore.iso(crashedAtMs));

        if (mappingId != null) {
            payload.put("mappingId", mappingId);
        }

        payload.put("mechanism", mechanism);
        payload.put("exceptions", exceptions);
        payload.put("threads", threads);

        return payload;
    }

    // frame <pc> <relative> <buildId|-> <path|->
    private static Map<String, Object> nativeFrame(String[] parts) {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        String path = parts[4];
        int slash = path.lastIndexOf('/');
        // The module is the frame's "function" stand-in until the server
        // symbolicates: a reader still sees which .so faulted and where.
        frame.put("module", slash >= 0 ? path.substring(slash + 1) : path);
        frame.put("function", "0x" + parts[2]);
        frame.put("instructionAddr", "0x" + parts[1]);
        frame.put("relativeAddr", "0x" + parts[2]);

        if (!"-".equals(parts[3])) {
            frame.put("buildId", parts[3]);
        }
        if (!"-".equals(path)) {
            frame.put("image", path);
        }

        return frame;
    }

    private static String signalName(int signo) {
        switch (signo) {
            case 4: return "SIGILL";
            case 6: return "SIGABRT";
            case 7: return "SIGBUS";
            case 8: return "SIGFPE";
            case 11: return "SIGSEGV";
            case 5: return "SIGTRAP";
            case 31: return "SIGSYS";
            default: return "SIG" + signo;
        }
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException bad) {
            return 0;
        }
    }
}
