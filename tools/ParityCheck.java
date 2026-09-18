import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.appatlas.sdk.core.DiskQueue;
import dev.appatlas.sdk.core.EnvelopeWriter;
import dev.appatlas.sdk.core.Json;
import dev.appatlas.sdk.core.JsonReader;
import dev.appatlas.sdk.core.Transport;

/**
 * The JVM half of the envelope parity gate (sdk/android/check-core.sh runs
 * it): writes envelopes for the server's parser to verify, and exercises the
 * queue and transport verdicts against a throwaway local server — never the
 * real one.
 */
public final class ParityCheck {

    public static void main(String[] args) throws Exception {
        File outDir = new File(args[0]);
        outDir.mkdirs();

        writeSamples(outDir);
        checkQueue(new File(outDir, "queue"));
        checkTransportVerdicts();
        checkJsonRoundTrip();
        LinksParity.run(outDir);
        dev.appatlas.sdk.crash.CrashParity.run(outDir);

        System.out.println("parity: envelopes, queue, transport, json, links and crash hold");
    }

    private static void writeSamples(File outDir) throws IOException {
        Map<String, Object> open = new LinkedHashMap<String, Object>();
        open.put("eventId", "11111111-2222-3333-4444-555555555555");
        open.put("shortId", "aB3kM9p");
        open.put("channel", "email");

        // The context block every real envelope's header carries.
        Map<String, Object> device = new LinkedHashMap<String, Object>();
        device.put("os", "android");
        device.put("osVersion", "14");
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("device", device);

        write(outDir, "open.envelope", new EnvelopeWriter("atlas-android", "0.1.0",
                "2026-09-15T09:00:00Z", "c1a2b3d4e5f60718", context).add("open", open).bytes());

        // Escapes and non-ASCII: what a real payload will carry sooner or later.
        Map<String, Object> hostile = new LinkedHashMap<String, Object>();
        hostile.put("eventId", "22222222-0000-0000-0000-000000000002");
        hostile.put("note", "line\nbreak \"quoted\" back\\slash 한글 ctl:\u0001");
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("deep", Boolean.TRUE);
        nested.put("count", 42);
        hostile.put("nested", nested);
        write(outDir, "hostile.envelope", new EnvelopeWriter("atlas-android", "0.1.0",
                "2026-09-15T09:00:00Z", "c1a2b3d4e5f60718").add("open", hostile).bytes());

        // Two items in one request: the atomicity the format exists for.
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("eventId", "33333333-0000-0000-0000-000000000001");
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("eventId", "33333333-0000-0000-0000-000000000002");
        write(outDir, "pair.envelope", new EnvelopeWriter("atlas-android", "0.1.0",
                "2026-09-15T09:00:00Z", "c1a2b3d4e5f60718")
                .add("open", first).add("session", second).bytes());
    }

    private static void checkQueue(File dir) {
        DiskQueue queue = new DiskQueue(dir);

        for (int i = 0; i < DiskQueue.MAX_FILES + 5; i++) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("n", i);
            require(queue.offer(new EnvelopeWriter("t", "0", "now", "id").add("open", payload).bytes()) != null,
                    "offer failed");
        }

        File[] listed = queue.list();
        require(listed.length == DiskQueue.MAX_FILES, "cap not held: " + listed.length);

        for (int i = 1; i < listed.length; i++) {
            require(listed[i - 1].getName().compareTo(listed[i].getName()) < 0, "order broken");
        }
    }

    private static void checkTransportVerdicts() throws Exception {
        final int[] status = {200};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ingest/envelope", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (status[0] == 429) {
                    exchange.getResponseHeaders().set("Retry-After", "30");
                }

                exchange.sendResponseHeaders(status[0], -1);
                exchange.close();
            }
        });
        server.start();

        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] body = new EnvelopeWriter("t", "0", "now", "id").bytes();

            Transport transport = new Transport(base, "sdk_test");
            require(transport.send(body, 0) == Transport.Verdict.DELIVERED, "2xx must deliver");

            status[0] = 400;
            require(transport.send(body, 0) == Transport.Verdict.REFUSED, "4xx must refuse");

            status[0] = 429;
            require(transport.send(body, 0) == Transport.Verdict.RETRY_LATER, "429 must defer");
            require(transport.limited(29_000L), "the Retry-After deadline must hold");
            require(!transport.limited(31_000L), "the deadline must expire");

            status[0] = 500;
            require(transport.send(body, 40_000L) == Transport.Verdict.RETRY_LATER, "5xx must retry");
        } finally {
            server.stop(0);
        }
    }

    private static void checkJsonRoundTrip() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("deep", Boolean.TRUE);
        nested.put("count", 42L);

        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("note", "line\nbreak \"quoted\" back\\slash \ud55c\uae00 ctl:\u0001");
        value.put("pi", 3.5d);
        value.put("nothing", null);
        value.put("nested", nested);

        Object back = JsonReader.parse(Json.write(value));
        require(value.equals(back), "json round trip drifted: " + back);
        require(JsonReader.parse("not json") == null, "garbage must read as null");
        require(JsonReader.object("[1]").isEmpty(), "a non-object must read as empty");
    }

    private static void write(File dir, String name, byte[] bytes) throws IOException {
        FileOutputStream stream = new FileOutputStream(new File(dir, name));

        try {
            stream.write(bytes);
        } finally {
            stream.close();
        }
    }

    private static void require(boolean held, String complaint) {
        if (!held) {
            throw new AssertionError(complaint);
        }
    }
}
