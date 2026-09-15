package dev.appatlas.sdk.links;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the Play install-referrer string. The two mistakes Branch ships are
 * the spec here: never URL-decode the whole string before splitting (a value
 * may carry encoded &amp; and =), and never split a pair without a limit (a
 * value may carry = of its own). Raw first, split on &amp;, first = only,
 * decode each half once.
 */
final class ReferrerParser {

    private ReferrerParser() {
    }

    /** The atlas click token, or null for organic and foreign referrers. */
    static String clickToken(String referrer) {
        return pairs(referrer).get("atlas");
    }

    static Map<String, String> pairs(String referrer) {
        Map<String, String> out = new LinkedHashMap<String, String>();

        if (referrer == null || referrer.length() == 0) {
            return out;
        }

        for (String pair : referrer.split("&")) {
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
            // A malformed %-sequence: the raw text says more than nothing.
            return part;
        }
    }
}
