package dev.appatlas.sdk.core;

import java.util.List;
import java.util.Map;

/**
 * A minimal JSON writer, so the core owns its bytes with zero dependencies
 * and identical output on every platform the envelope fixtures check.
 * Values are String, Number, Boolean, Map&lt;String,?&gt;, List&lt;?&gt; or null.
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);

        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            string(out, (String) value);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            // JSON has no NaN/Infinity; a broken measurement becomes null.
            out.append(Double.isNaN(number) || Double.isInfinite(number) ? "null" : String.valueOf(number));
        } else if (value instanceof Map) {
            object(out, (Map<?, ?>) value);
        } else if (value instanceof List) {
            array(out, (List<?>) value);
        } else {
            string(out, String.valueOf(value));
        }
    }

    private static void object(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) out.append(',');
            first = false;
            string(out, String.valueOf(entry.getKey()));
            out.append(':');
            append(out, entry.getValue());
        }

        out.append('}');
    }

    private static void array(StringBuilder out, List<?> list) {
        out.append('[');

        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(',');
            append(out, list.get(i));
        }

        out.append(']');
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }

        out.append('"');
    }
}
