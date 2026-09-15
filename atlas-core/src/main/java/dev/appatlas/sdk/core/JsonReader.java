package dev.appatlas.sdk.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reading half of {@link Json}: enough JSON to take a server answer
 * apart, with the same zero-dependency reach as the writer. Values come back
 * as Map, List, String, Long, Double, Boolean or null.
 */
public final class JsonReader {

    private final String text;
    private int at;

    private JsonReader(String text) {
        this.text = text;
    }

    /** The parsed value, or null when the text is not JSON. */
    public static Object parse(String text) {
        if (text == null) {
            return null;
        }

        try {
            JsonReader reader = new JsonReader(text);
            Object value = reader.value();
            reader.space();

            return reader.at == text.length() ? value : null;
        } catch (RuntimeException broken) {
            return null;
        }
    }

    /** The parsed object, or an empty map for anything else — a server answer
     *  that is not the shape the caller expects reads as an empty one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object value = parse(text);

        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private Object value() {
        space();

        char c = text.charAt(at);

        switch (c) {
            case '{': return objectValue();
            case '[': return arrayValue();
            case '"': return stringValue();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return numberValue();
        }
    }

    private Map<String, Object> objectValue() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        at++;
        space();

        if (text.charAt(at) == '}') {
            at++;

            return map;
        }

        while (true) {
            space();
            String key = stringValue();
            space();
            expect(":");
            map.put(key, value());
            space();

            char next = text.charAt(at++);

            if (next == '}') {
                return map;
            }

            if (next != ',') {
                throw new IllegalStateException("expected , or }");
            }
        }
    }

    private List<Object> arrayValue() {
        List<Object> list = new ArrayList<Object>();
        at++;
        space();

        if (text.charAt(at) == ']') {
            at++;

            return list;
        }

        while (true) {
            list.add(value());
            space();

            char next = text.charAt(at++);

            if (next == ']') {
                return list;
            }

            if (next != ',') {
                throw new IllegalStateException("expected , or ]");
            }
        }
    }

    private String stringValue() {
        expect("\"");

        StringBuilder out = new StringBuilder();

        while (true) {
            char c = text.charAt(at++);

            if (c == '"') {
                return out.toString();
            }

            if (c != '\\') {
                out.append(c);
                continue;
            }

            char escape = text.charAt(at++);

            switch (escape) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default: throw new IllegalStateException("bad escape");
            }
        }
    }

    private Object numberValue() {
        int start = at;

        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) != -1) {
            at++;
        }

        String slice = text.substring(start, at);

        if (slice.indexOf('.') == -1 && slice.indexOf('e') == -1 && slice.indexOf('E') == -1) {
            return Long.valueOf(slice);
        }

        return Double.valueOf(slice);
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, at)) {
            throw new IllegalStateException("expected " + literal);
        }

        at += literal.length();
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }
}
