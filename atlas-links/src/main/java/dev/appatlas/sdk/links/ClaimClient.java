package dev.appatlas.sdk.links;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.appatlas.sdk.core.Json;
import dev.appatlas.sdk.core.JsonReader;

/**
 * Exchanges a click token for the link it came from. The consumption rule is
 * Adjust's: nothing is marked done until the server has answered — a claim
 * lost to a dead process replays on the next launch, and the server's
 * idempotency (409) makes the replay harmless.
 */
final class ClaimClient {

    /** DONE ends the attempts (claimed, already claimed, or never ours);
     *  RETRY means ask again next launch. */
    enum Outcome { DONE, RETRY }

    static final class Answer {
        final Outcome outcome;
        /** The claimed link; null when DONE without one (409/404). */
        final AtlasLink link;

        Answer(Outcome outcome, AtlasLink link) {
            this.outcome = outcome;
            this.link = link;
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final URL endpoint;

    ClaimClient(String baseUrl) {
        try {
            this.endpoint = new URL(baseUrl + "/api/ingest/link/claim");
        } catch (IOException bad) {
            throw new IllegalArgumentException("not a base URL: " + baseUrl, bad);
        }
    }

    Answer claim(String token, String installId, String via) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("token", token);
        body.put("installId", installId);
        body.put("os", "android");
        body.put("via", via);

        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            byte[] payload = Json.write(body).getBytes(UTF8);
            connection.setFixedLengthStreamingMode(payload.length);

            OutputStream out = connection.getOutputStream();
            out.write(payload);
            out.close();

            int status = connection.getResponseCode();

            if (status >= 200 && status < 300) {
                return new Answer(Outcome.DONE, link(read(connection.getInputStream())));
            }

            // Already claimed, expired, or never ours: over, quietly.
            if (status == 404 || status == 409) {
                return new Answer(Outcome.DONE, null);
            }

            return new Answer(Outcome.RETRY, null);
        } catch (IOException offline) {
            return new Answer(Outcome.RETRY, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static AtlasLink link(String answer) {
        Map<String, Object> parsed = JsonReader.object(answer);
        Object payload = parsed.get("payload");

        return new AtlasLink(
                payload instanceof Map ? (Map<String, Object>) payload : new LinkedHashMap<String, Object>(),
                text(parsed.get("path")),
                null,
                text(parsed.get("channel")),
                text(parsed.get("campaign")),
                text(parsed.get("clickedAt")),
                true,
                text(parsed.get("match"))
        );
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] scratch = new byte[1024];

        try {
            int got;

            while ((got = stream.read(scratch)) != -1) {
                out.write(scratch, 0, got);
            }
        } finally {
            stream.close();
        }

        return new String(out.toByteArray(), UTF8);
    }
}
