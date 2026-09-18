package dev.appatlas.sdk.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The write-before-send store: an envelope hits disk before any network is
 * tried, and the flush at next start is what delivery really rests on. The
 * cap evicts oldest-first, and the filename carries the order — a timestamp
 * plus a counter, so two writes in one millisecond still sort.
 */
public final class DiskQueue {

    public static final int MAX_FILES = 30;
    private static final String SUFFIX = ".envelope";
    // A kept envelope (a crash) is the last thing the cap evicts: thirty
    // offline launches must not push out the one report that matters.
    private static final String KEPT = "_keep";

    private final File dir;
    private int counter;

    public DiskQueue(File dir) {
        // No I/O here: construction happens on the caller's thread, and the
        // directory is made on first use, always off the main thread.
        this.dir = dir;
    }

    /** Persists the envelope, evicting the oldest past the cap. */
    public File offer(byte[] envelope) {
        return offer(envelope, false);
    }

    /** `keep` marks an envelope the cap evicts only when nothing else is left. */
    public synchronized File offer(byte[] envelope, boolean keep) {
        dir.mkdirs();

        File[] present = list();
        int excess = present.length - MAX_FILES + 1;

        for (int pass = 0; pass < 2 && excess > 0; pass++) {
            for (int i = 0; i < present.length && excess > 0; i++) {
                boolean kept = present[i] != null && present[i].getName().endsWith(KEPT + SUFFIX);

                if (present[i] != null && kept == (pass == 1)) {
                    present[i].delete();
                    present[i] = null;
                    excess--;
                }
            }
        }

        File file = new File(dir, System.currentTimeMillis() + "_" + (counter++ % 1000) + (keep ? KEPT : "") + SUFFIX);

        try {
            FileOutputStream stream = new FileOutputStream(file);

            try {
                stream.write(envelope);
            } finally {
                stream.close();
            }
        } catch (IOException lost) {
            // Full disk or a vanished directory: the event is gone, the app
            // must not be.
            file.delete();

            return null;
        }

        return file;
    }

    /** Oldest first, the order they should leave in. */
    public synchronized File[] list() {
        File[] files = dir.listFiles();

        if (files == null) {
            return new File[0];
        }

        int kept = 0;

        for (File file : files) {
            if (file.getName().endsWith(SUFFIX)) {
                files[kept++] = file;
            }
        }

        File[] queue = Arrays.copyOf(files, kept);
        Arrays.sort(queue, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });

        return queue;
    }
}
