package dev.appatlas.sdk.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the wire bytes the server's parser reads (common/envelope.py), and
 * that tests/envelope_cases.json pins for every SDK. Every item declares its
 * byte length, so a payload may carry any whitespace the platform's JSON
 * happens to emit.
 */
public final class EnvelopeWriter {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public EnvelopeWriter(String sdkName, String sdkVersion, String sentAtIso, String installId) {
        this(sdkName, sdkVersion, sentAtIso, installId, null);
    }

    /**
     * `context` rides the header once per envelope — device and app facts
     * every item shares (the crash module reads the same block), never
     * repeated per item.
     */
    public EnvelopeWriter(String sdkName, String sdkVersion, String sentAtIso, String installId,
                          Map<String, Object> context) {
        Map<String, Object> sdk = new LinkedHashMap<String, Object>();
        sdk.put("name", sdkName);
        sdk.put("version", sdkVersion);

        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("sdk", sdk);
        header.put("sentAt", sentAtIso);
        header.put("installId", installId);

        if (context != null) {
            header.putAll(context);
        }

        line(Json.write(header));
    }

    /** One item: a type-plus-length header line, then the payload bytes. */
    public EnvelopeWriter add(String type, Map<String, Object> payload) {
        byte[] body = Json.write(payload).getBytes(UTF8);

        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("type", type);
        headers.put("length", body.length);

        line(Json.write(headers));
        raw(body);
        raw(new byte[]{'\n'});

        return this;
    }

    public byte[] bytes() {
        return out.toByteArray();
    }

    private void line(String text) {
        raw(text.getBytes(UTF8));
        raw(new byte[]{'\n'});
    }

    private void raw(byte[] bytes) {
        try {
            out.write(bytes);
        } catch (IOException impossible) {
            // A ByteArrayOutputStream cannot fail to grow.
            throw new IllegalStateException(impossible);
        }
    }
}
