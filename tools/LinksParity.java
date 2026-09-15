import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;

/**
 * The links half of the parity gate: the referrer parser against the traps
 * Branch ships, the link-URL reader, and the claim client's verdicts against
 * a throwaway local server — its captured request body goes to a file for
 * the Python side to hold against the real route's schema.
 */
public final class LinksParity {

    private LinksParity() {
    }

    static void run(File outDir) throws Exception {
        checkReferrerParser();
        checkLinkUrl();
        checkClaim(outDir);

        System.out.println("parity: links referrer, url and claim hold");
    }

    // ReferrerParser and LinkUrl are package-private by design; the gate
    // reaches them the way their own module does — reflectively, so the
    // public surface stays exactly the public surface.
    private static Method quiet(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);

        return method;
    }

    private static void checkReferrerParser() throws Exception {
        Method clickToken = quiet(Class.forName("dev.appatlas.sdk.links.ReferrerParser"),
                "clickToken", String.class);

        // The real thing, riding beside Play's own params.
        Object token = clickToken.invoke(null, "atlas%3Dab12cd34ef56ab78.sig-here");
        require("ab12cd34ef56ab78.sig-here".equals(clickToken.invoke(null,
                "utm_source=partner&atlas=ab12cd34ef56ab78.sig-here&utm_medium=cpc")), "plain token lost");

        // Play delivers the referrer once-encoded: atlas%3D... is a VALUE of
        // nothing — a whole-string pre-decode would invent a pair (Branch's
        // bug); split-then-decode reads it as a key called "atlas=...".
        require(token == null, "a pre-decoded parse invented a token");

        // An encoded = and & inside a value survive: split raw, decode halves.
        require("a=b&c".equals(clickToken.invoke(null, "atlas=a%3Db%26c")), "encoded =/& corrupted");

        // A hostile token hidden inside another param's value must not win.
        require("real.sig".equals(clickToken.invoke(null,
                "utm_content=x%26atlas%3Dfake.sig&atlas=real.sig")), "value smuggling won");

        // Organic Play: a referrer exists, a token does not.
        require(clickToken.invoke(null, "utm_source=google-play&utm_medium=organic") == null,
                "organic misread as attributed");
        require(clickToken.invoke(null, "") == null, "empty must be null");
        require(clickToken.invoke(null, (Object) null) == null, "null must be null");

        // A malformed %-sequence keeps its raw text instead of throwing.
        require("%zz".equals(clickToken.invoke(null, "atlas=%zz")), "malformed escape threw");
    }

    private static void checkLinkUrl() throws Exception {
        Class<?> type = Class.forName("dev.appatlas.sdk.links.LinkUrl");
        Method parse = quiet(type, "parse", String.class);

        Object link = parse.invoke(null, "https://appatlas.dev/aB3kM9p?ch=email&cp=spring%202026&src=qr");
        require(link != null, "a visit URL must parse");
        require("aB3kM9p".equals(field(link, "shortId")), "shortId lost");
        require("email".equals(field(link, "channel")), "ch lost");
        require("spring 2026".equals(field(link, "campaign")), "cp not decoded");
        require("qr".equals(field(link, "source")), "src lost");

        // "install" carries an l, which the server's alphabet excludes; a word
        // built purely of alphabet letters (like "account") matches by design,
        // exactly as it would server-side.
        require(parse.invoke(null, "https://appatlas.dev/install") == null, "an excluded letter must refuse");
        require(parse.invoke(null, "https://appatlas.dev/aB3kM9p/extra") == null, "two segments are not a link");
        require(parse.invoke(null, "https://appatlas.dev/aB3kM9pX") == null, "eight chars are not a short id");
        require(parse.invoke(null, "not a url") == null, "garbage must be null");
    }

    private static void checkClaim(File outDir) throws Exception {
        final byte[][] captured = new byte[1][];
        final int[] status = {200};
        final String answer = "{\"payload\":{\"promo\":\"launch\"},\"path\":\"spotify://\","
                + "\"clickedAt\":\"2026-09-15T09:00:00+00:00\",\"channel\":\"email\","
                + "\"campaign\":null,\"match\":\"referrer\"}";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ingest/link/claim", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                captured[0] = drain(exchange.getRequestBody());

                if (status[0] == 200) {
                    byte[] body = answer.getBytes("UTF-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    exchange.sendResponseHeaders(status[0], -1);
                }

                exchange.close();
            }
        });
        server.start();

        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Class<?> type = Class.forName("dev.appatlas.sdk.links.ClaimClient");
            Constructor<?> make = type.getDeclaredConstructor(String.class);
            make.setAccessible(true);
            Method claim = quiet(type, "claim", String.class, String.class, String.class);
            Object client = make.newInstance(base);

            Object first = claim.invoke(client, "ab12cd34ef56ab78.sig", "install-1", "referrer");
            require("DONE".equals(String.valueOf(field(first, "outcome"))), "200 must be DONE");
            Object link = field(first, "link");
            require(link != null, "200 must carry the link");
            require("spotify://".equals(field(link, "path")), "path lost");
            require("email".equals(field(link, "channel")), "channel lost");
            require(field(link, "campaign") == null, "a null campaign must stay null");
            require(Boolean.TRUE.equals(field(link, "deferred")), "a claim is deferred by definition");
            require("launch".equals(((Map<?, ?>) field(link, "payload")).get("promo")), "payload lost");

            // For the Python side: the exact body the client sends.
            FileOutputStream out = new FileOutputStream(new File(outDir, "claim-request.json"));
            out.write(captured[0]);
            out.close();

            status[0] = 409;
            Object second = claim.invoke(client, "t.s", "install-1", "referrer");
            require("DONE".equals(String.valueOf(field(second, "outcome"))), "409 must end the attempts");
            require(field(second, "link") == null, "409 carries no link");

            status[0] = 404;
            require("DONE".equals(String.valueOf(field(claim.invoke(client, "t.s", "i", "referrer"), "outcome"))),
                    "404 must end the attempts");

            status[0] = 500;
            require("RETRY".equals(String.valueOf(field(claim.invoke(client, "t.s", "i", "referrer"), "outcome"))),
                    "5xx must retry next launch");
        } finally {
            server.stop(0);
        }
    }

    private static Object field(Object holder, String name) throws Exception {
        Field field = holder.getClass().getDeclaredField(name);
        field.setAccessible(true);

        return field.get(holder);
    }

    private static byte[] drain(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] scratch = new byte[1024];
        int got;

        while ((got = stream.read(scratch)) != -1) {
            out.write(scratch, 0, got);
        }

        stream.close();

        return out.toByteArray();
    }

    private static void require(boolean held, String complaint) {
        if (!held) {
            throw new AssertionError(complaint);
        }
    }
}
