package com.pixelclub.teams.store;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class RowData {
    private RowData() {}

    static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, copyValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    static Object copyValue(Object value) {
        if (value instanceof Timestamp time) {
            Timestamp copy = new Timestamp(time.getTime());
            copy.setNanos(time.getNanos());
            return copy;
        }
        if (value instanceof java.sql.Date date) return new java.sql.Date(date.getTime());
        if (value instanceof java.sql.Time time) return new java.sql.Time(time.getTime());
        if (value instanceof byte[] bytes) return bytes.clone();
        return value;
    }

    static String text(Object value) { return value == null ? null : value.toString(); }
    static int integer(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
    static double decimal(Object value) { return value == null ? 0 : ((Number) value).doubleValue(); }
    static boolean flag(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return value != null && (value.toString().equals("1") || Boolean.parseBoolean(value.toString()));
    }
}
