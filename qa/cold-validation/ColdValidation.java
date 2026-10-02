package com.pixelclub.teams.tools;

import com.google.gson.*;
import com.pixelclub.teams.chest.NativeChestCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Read-only artifact/primitive evidence. No decoder, registry, SQL, Docker, config or server call. */
public final class ColdValidation {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public static void main(String[] args) throws Exception {
        for (String filename : args) {
            byte[] input = Files.readAllBytes(Path.of(filename));
            JsonArray rows = JsonParser.parseString(new String(input, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rows");
            int passed = 0, failed = 0;
            for (int index = 0; index < rows.size(); index++) {
                String inventory = rows.get(index).getAsJsonObject().get("inventory_json").getAsString();
                try { ColdFiles.inventory(inventory); passed++; }
                catch (Exception invalid) {
                    failed++;
                    JsonObject parsed = NativeChestCodec.parseStrict(inventory).getAsJsonObject();
                    String expected = parsed.remove("sha256").getAsString();
                    JsonObject mismatch = new JsonObject(); mismatch.addProperty("row_index", index); mismatch.addProperty("expected_sha256", expected);
                    mismatch.addProperty("cold_sha256", ColdFiles.semanticHash(parsed)); mismatch.addProperty("native_sha256", NativeChestCodec.semanticSha256(parsed));
                    System.out.println(GSON.toJson(mismatch));
                }
            }
            JsonObject result = new JsonObject(); result.addProperty("input_name", Path.of(filename).getFileName().toString()); result.addProperty("rows", rows.size()); result.addProperty("passed", passed); result.addProperty("failed", failed); result.addProperty("file_sha256", ColdFiles.sha(input));
            System.out.println(GSON.toJson(result));
        }
        for (float number : new float[]{1.0E-5f, 1.0E20f}) {
            JsonPrimitive typed = new JsonPrimitive(number); String json = GSON.toJson(typed);
            JsonElement nativeParsed = NativeChestCodec.parseStrict(json), coldParsed = ColdFiles.parse(json);
            JsonObject result = new JsonObject(); result.addProperty("primitive_float_json", json);
            result.addProperty("native_before_sha256", NativeChestCodec.semanticSha256(typed));
            result.addProperty("native_after_sha256", NativeChestCodec.semanticSha256(nativeParsed));
            result.addProperty("cold_after_sha256", ColdFiles.semanticHash(coldParsed));
            result.addProperty("parsed_json", GSON.toJson(nativeParsed));
            result.addProperty("equal", NativeChestCodec.semanticSha256(typed).equals(NativeChestCodec.semanticSha256(nativeParsed)));
            System.out.println(GSON.toJson(result));
        }
    }
}
