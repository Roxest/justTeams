package com.pixelclub.teams.store;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small strict JSON object reader: retains raw nested values and adds journal metadata. */
final class Json {
    private Json() {}
    static String put(String object, String key, String rawValue) {
        Map<String, String> fields = read(object);
        fields.put(key, rawValue);
        return write(fields);
    }
    static String merge(String old, String changes) {
        Map<String, String> fields = read(old);
        fields.putAll(read(changes));
        return write(fields);
    }
    static boolean booleanValue(String object, String key) { return "true".equals(read(object).get(key)); }
    static String rawValue(String object, String key) { return read(object).get(key); }
    static long longValue(String object, String key, long fallback) {
        String value = read(object).get(key);
        return value == null ? fallback : Long.parseLong(value);
    }
    static double doubleValue(String object, String key, double fallback) {
        String value = read(object).get(key);
        return value == null ? fallback : Double.parseDouble(value);
    }
    static String string(String value) {
        if (value == null) return "null";
        StringBuilder encoded = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\b' -> encoded.append("\\b");
                case '\f' -> encoded.append("\\f");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                default -> { if (character < 32) encoded.append(String.format("\\u%04x", (int) character)); else encoded.append(character); }
            }
        }
        return encoded.append('"').toString();
    }
    private static String write(Map<String, String> fields) {
        return "{" + String.join(",", fields.entrySet().stream().map(entry -> string(entry.getKey()) + ":" + entry.getValue()).toList()) + "}";
    }
    private static Map<String, String> read(String object) {
        Parser parser = new Parser(object == null ? "{}" : object);
        Map<String, String> fields = parser.object(0);
        parser.space();
        if (parser.index != parser.source.length()) throw new IllegalArgumentException("Trailing journal JSON data");
        return fields;
    }
    private static final class Parser {
        private final String source;
        private int index;
        private Parser(String source) { this.source = source; }
        private void space() { while (index < source.length() && " \n\r\t".indexOf(source.charAt(index)) >= 0) index++; }
        private boolean take(char character) { space(); if (index < source.length() && source.charAt(index) == character) { index++; return true; } return false; }
        private void expect(char character) { if (!take(character)) throw new IllegalArgumentException("Invalid journal JSON"); }
        private Map<String, String> object(int depth) {
            if (depth > 64) throw new IllegalArgumentException("Journal JSON is too deeply nested");
            expect('{');
            Map<String, String> fields = new LinkedHashMap<>();
            if (take('}')) return fields;
            do {
                String key = text(); expect(':'); space();
                int start = index; value(depth + 1);
                if (fields.put(key, source.substring(start, index)) != null) throw new IllegalArgumentException("Duplicate journal JSON key");
                if (take('}')) return fields;
            } while (take(','));
            throw new IllegalArgumentException("Invalid journal JSON object");
        }
        private void value(int depth) {
            space();
            if (index >= source.length() || depth > 64) throw new IllegalArgumentException("Invalid journal JSON value");
            char first = source.charAt(index);
            if (first == '{') { object(depth); return; }
            if (first == '"') { text(); return; }
            if (first == '[') {
                index++;
                if (take(']')) return;
                do { value(depth + 1); if (take(']')) return; } while (take(','));
                throw new IllegalArgumentException("Invalid journal JSON array");
            }
            int start = index;
            while (index < source.length() && ",]} \n\r\t".indexOf(source.charAt(index)) < 0) index++;
            String token = source.substring(start, index);
            if (!token.equals("true") && !token.equals("false") && !token.equals("null") && !token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("Invalid journal JSON primitive");
        }
        private String text() {
            expect('"');
            StringBuilder text = new StringBuilder();
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '"') return text.toString();
                if (character < 32) throw new IllegalArgumentException("Invalid JSON string control character");
                if (character == '\\') {
                    if (index >= source.length()) throw new IllegalArgumentException("Invalid JSON escape");
                    char escape = source.charAt(index++);
                    switch (escape) {
                        case '"', '\\', '/' -> text.append(escape);
                        case 'b' -> text.append('\b');
                        case 'f' -> text.append('\f');
                        case 'n' -> text.append('\n');
                        case 'r' -> text.append('\r');
                        case 't' -> text.append('\t');
                        case 'u' -> {
                            if (index + 4 > source.length()) throw new IllegalArgumentException("Invalid Unicode escape");
                            String digits = source.substring(index, index + 4);
                            if (!digits.matches("[0-9a-fA-F]{4}")) throw new IllegalArgumentException("Invalid Unicode escape");
                            text.append((char) Integer.parseInt(digits, 16)); index += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid JSON escape");
                    }
                } else text.append(character);
            }
            throw new IllegalArgumentException("Unclosed JSON string");
        }
    }
}
