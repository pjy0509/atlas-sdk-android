package dev.appatlas.sdk.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One envelope over the wire. The verdict is what the queue acts on:
 * delivered and refused both delete the file (a payload the server will
 * never take must not poison the queue), only a network failure or an
 * active rate limit keeps it for later.
 */
public final class Transport {

    public enum Verdict { DELIVERED, REFUSED, RETRY_LATER }

    private final URL endpoint;
    private final String authorization;
    // While set, sends are skipped entirely: buffering during a limit is how
    // a client turns one 429 into a flood.
    private volatile long retryNotBeforeMs;

    public Transport(String baseUrl, String sdkKey) {
        try {
            this.endpoint = new URL(baseUrl + "/api/ingest/envelope");
        } catch (IOException bad) {
            throw new IllegalArgumentException("not a base URL: " + baseUrl, bad);
        }

        this.authorization = "Bearer " + sdkKey;
    }

    public boolean limited(long nowMs) {
        return nowMs < retryNotBeforeMs;
    }

    public Verdict send(byte[] envelope, long nowMs) {
        if (limited(nowMs)) {
            return Verdict.RETRY_LATER;
        }

        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("Content-Type", "application/x-atlas-envelope");
            connection.setFixedLengthStreamingMode(envelope.length);

            OutputStream body = connection.getOutputStream();
            body.write(envelope);
            body.close();

            int status = connection.getResponseCode();
            drain(connection);

            if (status == 429) {
                retryNotBeforeMs = nowMs + retryAfterMs(connection);

                return Verdict.RETRY_LATER;
            }

            if (status >= 200 && status < 300) {
                return Verdict.DELIVERED;
            }

            // 4xx (401 revoked key, 400 broken framing, 413 oversize): the
            // server will never take this envelope; retrying is spam.
            if (status >= 400 && status < 500) {
                return Verdict.REFUSED;
            }

            return Verdict.RETRY_LATER;
        } catch (IOException offline) {
            return Verdict.RETRY_LATER;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static long retryAfterMs(HttpURLConnection connection) {
        try {
            return Long.parseLong(connection.getHeaderField("Retry-After")) * 1000L;
        } catch (NumberFormatException absent) {
            return 60_000L;
        }
    }

    private static void drain(HttpURLConnection connection) {
        // Reading the body is what lets the connection be pooled.
        try {
            InputStream stream = connection.getResponseCode() < 400
                    ? connection.getInputStream() : connection.getErrorStream();

            if (stream != null) {
                byte[] scratch = new byte[512];
                while (stream.read(scratch) != -1) {
                    // Discard.
                }
                stream.close();
            }
        } catch (IOException ignored) {
            // The verdict came from the status line; a broken body changes nothing.
        }
    }
}
