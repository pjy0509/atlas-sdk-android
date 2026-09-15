package dev.appatlas.sdk.links;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads an incoming visit URL: the short id in its single path segment, and
 * the inflow keys the page also reads (ch, cp, src, nid). Host-agnostic on
 * purpose — a custom link domain is still the same URL shape.
 */
final class LinkUrl {

    // The server's short-id alphabet (db/deep_links.py): no 0/O/1/l/I.
    private static final Pattern SHORT_ID = Pattern.compile("^[2-9A-HJ-NP-Za-km-z]{7}$");

    final String shortId;
    final String channel;
    final String campaign;
    final String source;
    final String pushId;

    private LinkUrl(String shortId, Map<String, String> query) {
        this.shortId = shortId;
        this.channel = query.get("ch");
        this.campaign = query.get("cp");
        this.source = query.get("src");
        this.pushId = query.get("nid");
    }

    /** The parsed link, or null when the URL is not a visit URL. */
    static LinkUrl parse(String url) {
        if (url == null) {
            return null;
        }

        URI parsed;

        try {
            parsed = new URI(url);
        } catch (Exception broken) {
            return null;
        }

        String path = parsed.getPath();

        if (path == null) {
            return null;
        }

        // One segment, in the short-id alphabet: appatlas.dev/<id>.
        String candidate = path.startsWith("/") ? path.substring(1) : path;

        if (candidate.indexOf('/') != -1 || !SHORT_ID.matcher(candidate).matches()) {
            return null;
        }

        return new LinkUrl(candidate, query(parsed.getRawQuery()));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<String, String>();

        if (raw == null || raw.length() == 0) {
            return out;
        }

        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = decode(split == -1 ? pair : pair.substring(0, split));
            String value = split == -1 ? "" : decode(pair.substring(split + 1));

            if (key.length() > 0 && !out.containsKey(key)) {
                out.put(key, value);
            }
        }

        return out;
    }

    private static String decode(String part) {
        try {
            return URLDecoder.decode(part, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException undecodable) {
            return part;
        }
    }
}
