package dev.appatlas.sdk.crash;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the main thread's Java frames out of the trace the OS wrote for an
 * ANR (ApplicationExitInfo.getTraceInputStream). The file is the classic
 * traces.txt text: a quoted thread header, then `at class.method(File:line)`
 * lines until a blank one.
 */
final class AnrTrace {

    private AnrTrace() {
    }

    /** Empty when the dump holds no Java frames for "main": not reportable. */
    static List<Object> mainFrames(String trace) {
        List<Object> frames = new ArrayList<Object>();

        if (trace == null) {
            return frames;
        }

        boolean inMain = false;

        for (String raw : trace.split("\n")) {
            String line = raw.trim();

            if (line.startsWith("\"")) {
                if (inMain) {
                    break;
                }

                inMain = line.startsWith("\"main\"");
            } else if (inMain && line.startsWith("at ") && frames.size() < CrashReport.MAX_FRAMES) {
                Object frame = frame(line.substring(3));

                if (frame != null) {
                    frames.add(frame);
                }
            }
        }

        return frames;
    }

    private static Object frame(String call) {
        int open = call.indexOf('(');
        int close = call.lastIndexOf(')');
        String name = open < 0 ? call : call.substring(0, open);
        int dot = name.lastIndexOf('.');

        if (dot <= 0) {
            return null;
        }

        String file = null;
        int lineNumber = -1;

        if (open >= 0 && close > open) {
            String where = call.substring(open + 1, close);
            int colon = where.lastIndexOf(':');

            if (colon > 0) {
                file = where.substring(0, colon);

                try {
                    lineNumber = Integer.parseInt(where.substring(colon + 1));
                } catch (NumberFormatException unnumbered) {
                    // "Native method" and friends: a file-less frame.
                }
            }
        }

        return CrashReport.frame(name.substring(0, dot), name.substring(dot + 1), file, lineNumber);
    }
}
