package com.pixelclub.teams.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** File integrity and the format-1 shape/checksum only; no Minecraft or Bukkit decoding. */
final class ColdFiles {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    static final int MAX_INVENTORY = 16 * 1024 * 1024;
    record Document(byte[] bytes, JsonObject json) {}

    private ColdFiles() {}

    static Document read(Path path) throws Exception {
        if (Files.size(path) > 256L * 1024 * 1024) throw new IllegalArgumentException();
        byte[] bytes = Files.readAllBytes(path);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JsonElement parsed = parse(text);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException();
        JsonObject json = parsed.getAsJsonObject();
        if (integer(json.get("format")) != 1 || !json.has("rows") || !json.get("rows").isJsonArray()) throw new IllegalArgumentException();
        return new Document(bytes, json);
    }

    static Map<Integer, JsonObject> rows(JsonObject document) {
        Map<Integer, JsonObject> rows = new TreeMap<>();
        for (JsonElement element : document.getAsJsonArray("rows")) {
            if (!element.isJsonObject()) throw new IllegalArgumentException();
            JsonObject row = element.getAsJsonObject();
            int id = integer(row.get("team_id"));
            if (id < 1 || id == Integer.MAX_VALUE || rows.putIfAbsent(id, row) != null) throw new IllegalArgumentException();
        }
        return rows;
    }

    static void requireCold(JsonObject document) {
        JsonElement cold = document.get("cold_verified");
        if (cold == null || !cold.isJsonPrimitive() || !cold.getAsJsonPrimitive().isBoolean() || !cold.getAsBoolean()) throw new IllegalArgumentException();
    }
    static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty()) throw new IllegalArgumentException();
        return value.getAsString();
    }
    static String hash(JsonObject object, String key, boolean nullable) {
        JsonElement value = object.get(key);
        if (nullable && value != null && value.isJsonNull()) return null;
        String result = text(object, key);
        if (!result.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
        return result;
    }
    static int integer(JsonElement element) { return number(element).intValueExact(); }
    static long longInteger(JsonElement element) { return number(element).longValueExact(); }
    private static BigDecimal number(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        return element.getAsBigDecimal();
    }

    static void inventory(String json) throws Exception {
        if (json == null || json.length() > MAX_INVENTORY) throw new IllegalArgumentException();
        JsonElement parsed = parse(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException();
        JsonObject body = parsed.getAsJsonObject();
        if (!body.keySet().equals(Set.of("format", "size", "slots", "sha256")) || integer(body.get("format")) != 1) throw new IllegalArgumentException();
        int size = integer(body.get("size"));
        if (size < 9 || size > 54 || size % 9 != 0 || !body.get("slots").isJsonArray()) throw new IllegalArgumentException();
        JsonArray slots = body.getAsJsonArray("slots");
        if (slots.size() != size) throw new IllegalArgumentException();
        String expected = hash(body, "sha256", false);
        body.remove("sha256");
        if (!expected.equals(semanticHash(body))) throw new IllegalArgumentException();
        for (JsonElement slot : slots) {
            if (slot.isJsonNull()) continue;
            if (!slot.isJsonObject()) throw new IllegalArgumentException();
            JsonObject record = slot.getAsJsonObject();
            if (!record.keySet().equals(Set.of("item", "nbt", "components_sha256")) || !record.get("item").isJsonObject()) throw new IllegalArgumentException();
            text(record, "nbt"); hash(record, "components_sha256", false);
        }
    }

    static String semanticHash(JsonElement value) throws Exception { return sha(GSON.toJson(canonical(value)).getBytes(StandardCharsets.UTF_8)); }
    static String sha(String value) throws Exception { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static byte[] bytes(JsonObject object) { return GSON.toJson(object).getBytes(StandardCharsets.UTF_8); }
    private static JsonElement canonical(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject object = new JsonObject();
            new java.util.TreeSet<>(element.getAsJsonObject().keySet()).forEach(key -> object.add(key, canonical(element.getAsJsonObject().get(key))));
            return object;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray(); for (JsonElement value : element.getAsJsonArray()) array.add(canonical(value)); return array;
        }
        return element.deepCopy();
    }

    static JsonElement parse(String text) throws Exception {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement result = value(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
            return result;
        }
    }
    private static JsonElement value(JsonReader reader, int depth) throws Exception {
        if (depth > 128) throw new IllegalArgumentException();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject result = new JsonObject();
                while (reader.hasNext()) { String key = reader.nextName(); if (result.has(key)) throw new IllegalArgumentException(); result.add(key, value(reader, depth + 1)); }
                reader.endObject(); yield result;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray result = new JsonArray();
                while (reader.hasNext()) result.add(value(reader, depth + 1)); reader.endArray(); yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException();
        };
    }
}
