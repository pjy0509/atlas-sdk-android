package dev.appatlas.sdk.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The platform-free half of every Atlas SDK: an item goes to disk first as a
 * one-item envelope, and a single background worker drains the queue — at
 * start, and after every offer. Modules hand items in; they never touch the
 * network themselves.
 */
public final class AtlasCore {

    public static final String VERSION = "0.2.0";

    private final String sdkName;
    private final String baseUrl;
    private final String installId;
    // The device/app facts every envelope's header carries, snapshotted once:
    // nothing in it changes while the process lives.
    private final Map<String, Object> context;
    private final DiskQueue queue;
    private final Transport transport;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "atlas-core");
            thread.setDaemon(true);

            return thread;
        }
    });

    public AtlasCore(String sdkName, String baseUrl, String sdkKey, File queueDir, String installId) {
        this(sdkName, baseUrl, sdkKey, queueDir, installId, null);
    }

    public AtlasCore(String sdkName, String baseUrl, String sdkKey, File queueDir, String installId,
                     Map<String, Object> context) {
        this.sdkName = sdkName;
        this.baseUrl = baseUrl;
        this.installId = installId;
        this.context = context;
        this.queue = new DiskQueue(queueDir);
        this.transport = new Transport(baseUrl, sdkKey);
    }

    public String installId() {
        return installId;
    }

    /** For modules with endpoints of their own (the links claim). */
    public String baseUrl() {
        return baseUrl;
    }

    /** A fresh id for one event: the server's idempotency handle. */
    public static String newEventId() {
        return UUID.randomUUID().toString();
    }

    /**
     * One item, disk-first, then the wire. Only the in-memory serialization
     * happens on the caller's thread; the disk write rides the worker, so a
     * main-thread caller never blocks on I/O.
     */
    public void enqueue(String type, Map<String, Object> payload) {
        batch().add(type, payload).enqueue();
    }

    /** Several items that must arrive together: a crash and its session's end. */
    public Batch batch() {
        return new Batch();
    }

    /** Items bound for one envelope, so a flaky network cannot deliver half. */
    public final class Batch {

        private final EnvelopeWriter writer = new EnvelopeWriter(sdkName, VERSION, isoNow(), installId, context);

        private Batch() {
        }

        public Batch add(String type, Map<String, Object> payload) {
            writer.add(type, payload);

            return this;
        }

        /** Disk on the worker, then the wire. */
        public void enqueue() {
            final byte[] envelope = writer.bytes();

            worker.execute(new Runnable() {
                @Override
                public void run() {
                    queue.offer(envelope);
                    drain();
                }
            });
        }

        /**
         * Disk on the caller's thread, and nothing else: for a thread about
         * to die with its process. The next start's flush is the delivery.
         */
        public boolean persistNow() {
            return queue.offer(writer.bytes(), true) != null;
        }
    }

    /** Drain whatever the disk holds — called at start and after each offer. */
    public void flushSoon() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                drain();
            }
        });
    }

    /**
     * Drains on the worker and waits at most `timeoutMs` for it: for the start
     * after a launch crash, where the next crash may come before any
     * background send would.
     */
    public void flushWithin(long timeoutMs) {
        if (timeoutMs <= 0) {
            return;
        }

        try {
            worker.submit(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            }).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception late) {
            // Out of time or interrupted: the drain carries on in the background.
        }
    }

    /** Waits for the worker to go idle; for tests, never for app code. */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        worker.shutdown();

        return worker.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void drain() {
        for (File file : queue.list()) {
            long now = System.currentTimeMillis();

            if (transport.limited(now)) {
                return;
            }

            byte[] envelope = read(file);

            if (envelope == null) {
                // Unreadable is undeliverable; keeping it would spin forever.
                file.delete();
                continue;
            }

            switch (transport.send(envelope, now)) {
                case DELIVERED:
                case REFUSED:
                    file.delete();
                    break;
                case RETRY_LATER:
                    // The queue is ordered; if the head cannot go, the rest
                    // cannot either.
                    return;
            }
        }
    }

    private static byte[] read(File file) {
        try {
            long size = file.length();

            if (size <= 0 || size > 16 * 1024 * 1024) {
                return null;
            }

            byte[] bytes = new byte[(int) size];
            FileInputStream stream = new FileInputStream(file);

            try {
                int cursor = 0;

                while (cursor < bytes.length) {
                    int got = stream.read(bytes, cursor, bytes.length - cursor);

                    if (got == -1) {
                        return null;
                    }

                    cursor += got;
                }
            } finally {
                stream.close();
            }

            return bytes;
        } catch (IOException gone) {
            return null;
        }
    }

    public static String isoNow() {
        return iso(System.currentTimeMillis());
    }

    /** UTC to the second, the one timestamp shape every item carries. */
    public static String iso(long epochMs) {
        // No java.time below API 26; SimpleDateFormat reaches every floor.
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));

        return format.format(new Date(epochMs));
    }
}
