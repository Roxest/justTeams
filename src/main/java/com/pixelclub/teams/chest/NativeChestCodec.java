package com.pixelclub.teams.chest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.RegistryOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Registry-aware, loss-detecting storage. A decoding failure never becomes an empty inventory. */
public final class NativeChestCodec {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_JSON_CHARS = 16 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Set<String> ENVELOPE_KEYS = Set.of("format", "size", "slots", "sha256");
    private static final Set<String> CUSTOM_NBT_COMPONENTS = Set.of("minecraft:custom_data", "minecraft:entity_data",
            "minecraft:bucket_entity_data", "minecraft:block_entity_data");

    private NativeChestCodec() { }

    public static String encode(List<ItemStack> stacks, RegistryAccess registries) {
        Objects.requireNonNull(stacks, "stacks");
        validateSize(stacks.size());
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE,
                Objects.requireNonNull(registries, "registries"));
        JsonArray slots = new JsonArray();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            slots.add(stack == null || stack.isEmpty() ? JsonNull.INSTANCE : encodeItem(stack, ops, slot));
        }
        JsonObject body = new JsonObject();
        body.addProperty("format", FORMAT_VERSION);
        body.addProperty("size", stacks.size());
        body.add("slots", slots);
        body.addProperty("sha256", semanticSha256(body));
        String json = GSON.toJson(canonical(body));
        if (json.length() > MAX_JSON_CHARS) throw invalid("inventory exceeds JSON size limit");
        return json;
    }

    public static List<ItemStack> decode(String json, RegistryAccess registries) {
        JsonElement parsed = parseStrict(json);
        if (!parsed.isJsonObject()) throw invalid("inventory must be an object");
        JsonObject body = parsed.getAsJsonObject();
        if (!ENVELOPE_KEYS.containsAll(body.keySet())) throw invalid("unknown envelope property");
        if (integer(body.get("format"), "format") != FORMAT_VERSION) throw invalid("unsupported format");
        int size = integer(body.get("size"), "size");
        validateSize(size);
        if (!body.has("slots") || !body.get("slots").isJsonArray()) throw invalid("slots must be an array");
        JsonArray slots = body.getAsJsonArray("slots");
        if (slots.size() != size) throw invalid("slot count differs from declared size");
        if (!body.has("sha256")) throw invalid("missing checksum");
        {
            JsonElement expected = body.remove("sha256");
            if (!expected.isJsonPrimitive() || !expected.getAsJsonPrimitive().isString()
                    || !expected.getAsString().matches("[0-9a-f]{64}")
                    || !expected.getAsString().equals(semanticSha256(body))) throw invalid("checksum mismatch");
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE,
                Objects.requireNonNull(registries, "registries"));
        List<ItemStack> decoded = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            JsonElement encoded = slots.get(slot);
            if (encoded.isJsonNull()) {
                decoded.add(ItemStack.EMPTY);
                continue;
            }
            if (!encoded.isJsonObject()) throw invalid("slot " + slot + " must be an item record or null");
            JsonObject record = encoded.getAsJsonObject();
            if (!record.keySet().equals(Set.of("item", "nbt", "components_sha256")) || !record.get("item").isJsonObject()
                    || !record.get("nbt").isJsonPrimitive() || !record.get("nbt").getAsJsonPrimitive().isString()
                    || !record.get("components_sha256").isJsonPrimitive()
                    || !record.get("components_sha256").getAsJsonPrimitive().isString()
                    || !record.get("components_sha256").getAsString().matches("[0-9a-f]{64}")) {
                throw invalid("slot " + slot + " must contain item JSON, typed nbt and full component hash");
            }
            ItemStack jsonCheck = require(ItemStack.CODEC.parse(ops, record.get("item")), "decode JSON slot " + slot);
            if (jsonCheck.isEmpty()) throw invalid("slot " + slot + " JSON decoded to empty");
            CompoundTag typed;
            try { typed = TagParser.parseTag(record.get("nbt").getAsString()); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) { throw invalid("slot " + slot + " has invalid typed nbt"); }
            ItemStack stack = require(ItemStack.CODEC.parse(ops.withParent(NbtOps.INSTANCE), typed), "decode NBT slot " + slot);
            if (stack.isEmpty()) throw invalid("slot " + slot + " NBT decoded to empty");
            // Compare JSON documents, not Gson's in-memory Float vs BigDecimal number wrappers.
            // Float.toString(1.05f) is "1.05", while Float.doubleValue differs from parsed JSON 1.05.
            JsonElement roundtrip = parseStrict(GSON.toJson(encodeItem(stack, ops, slot)));
            if (!encoded.equals(roundtrip)) {
                JsonObject beforeComponents = record.getAsJsonObject("item").getAsJsonObject("components");
                JsonObject afterComponents = roundtrip.getAsJsonObject().getAsJsonObject("item").getAsJsonObject("components");
                List<String> changed = new ArrayList<>();
                if (beforeComponents != null) {
                    for (String name : beforeComponents.keySet()) {
                        if (afterComponents == null || !Objects.equals(beforeComponents.get(name), afterComponents.get(name))) changed.add(name);
                    }
                }
                throw invalid("slot " + slot + " loses item data on round trip; JSON components=" + String.join(",", changed)
                        + " typedNBT=" + record.get("nbt").equals(roundtrip.getAsJsonObject().get("nbt")));
            }
            decoded.add(stack);
        }
        return decoded;
    }

    public static void validateSize(int size) {
        if (size < 9 || size > 54 || size % 9 != 0) throw invalid("size must be 9..54 and divisible by 9");
    }

    /** Used by file-only migration manifests; keys are sorted recursively, arrays retain slot order. */
    public static String semanticSha256(JsonElement value) {
        return sha256(GSON.toJson(canonical(value)).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Reject duplicate keys, trailing JSON, excessive nesting and lenient JSON syntax. */
    public static JsonElement parseStrict(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_CHARS) throw invalid("missing or oversized JSON");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("trailing JSON");
            return result;
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid native chest JSON", exception);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 128) throw invalid("JSON nesting limit exceeded");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject result = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (result.has(name)) throw invalid("duplicate JSON property");
                    result.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray result = new JsonArray();
                while (reader.hasNext()) result.add(read(reader, depth + 1));
                reader.endArray();
                yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid("unexpected JSON token");
        };
    }

    private static int integer(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(field + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw invalid(field + " must be an integer");
        }
    }

    private static JsonElement encodeItem(ItemStack stack, RegistryOps<JsonElement> ops, int slot) {
        JsonElement encoded = require(ItemStack.CODEC.encodeStart(ops, stack), "encode slot " + slot);
        Tag typed = require(ItemStack.CODEC.encodeStart(ops.withParent(NbtOps.INSTANCE), stack), "encode typed slot " + slot);
        preserveCustomNbt(encoded, typed);
        require(ItemStack.CODEC.parse(ops, encoded), "verify JSON slot " + slot);
        ItemStack decoded = require(ItemStack.CODEC.parse(ops.withParent(NbtOps.INSTANCE), typed), "verify NBT slot " + slot);
        Tag restored = require(ItemStack.CODEC.encodeStart(ops.withParent(NbtOps.INSTANCE), decoded), "verify typed slot " + slot);
        Tag full = effectiveComponents(stack, ops.withParent(NbtOps.INSTANCE), "encode full components slot " + slot);
        Tag decodedFull = effectiveComponents(decoded, ops.withParent(NbtOps.INSTANCE), "verify full components slot " + slot);
        if (!typed.equals(restored) && stack.getItem() == decoded.getItem() && stack.getCount() == decoded.getCount()
                && full.equals(decodedFull) && !hasChangedTransient(stack, decoded)) {
            // Vanilla normalizes redundant default patches (e.g. an explicitly empty shulker container).
            // Accept that representation only when the complete effective component state is exact.
            typed = restored;
            encoded = require(ItemStack.CODEC.encodeStart(ops, decoded), "encode canonical slot " + slot);
            preserveCustomNbt(encoded, typed);
        }
        if (!typed.equals(restored) || hasChangedTransient(stack, decoded)) {
            List<String> changed = new ArrayList<>();
            if (typed instanceof CompoundTag source && restored instanceof CompoundTag target) {
                itemDifferences(source, target, "", changed, 0);
            }
            throw invalid("slot " + slot + " loses typed components on encode; components=" + String.join(",", changed)
                    + " transient=" + hasChangedTransient(stack, decoded));
        }
        JsonObject record = new JsonObject();
        record.add("item", encoded);
        record.addProperty("nbt", typed.toString());
        record.addProperty("components_sha256", sha256(full.toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    /** Exact codec-persisted state comparison, including NBT numeric widths/arrays and registry identities. */
    public static boolean samePersistentItem(ItemStack first, ItemStack second, RegistryAccess registries) {
        if (first == null || second == null) return first == second;
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        Tag left = effectiveComponents(first, ops, "compare source full components");
        Tag right = effectiveComponents(second, ops, "compare target full components");
        return first.getItem() == second.getItem() && first.getCount() == second.getCount() && left.equals(right)
                && !hasChangedTransient(first, second) && !hasChangedTransient(second, first);
    }

    private static Tag effectiveComponents(ItemStack stack, RegistryOps<Tag> ops, String action) {
        return require(DataComponentMap.CODEC.encodeStart(ops, stack.getComponents().filter(type -> !type.isTransient())), action);
    }

    private static boolean hasChangedTransient(ItemStack first, ItemStack second) {
        for (TypedDataComponent<?> component : first.getComponents()) {
            if (component.type().isTransient() && !Objects.equals(component.value(), second.get(component.type()))) return true;
        }
        return false;
    }

    private static void itemDifferences(CompoundTag source, CompoundTag target, String path, List<String> changed, int depth) {
        if (depth > 32 || changed.size() >= 12) return;
        if (!Objects.equals(source.get("id"), target.get("id"))) changed.add(path + "id");
        if (!Objects.equals(source.get("count"), target.get("count"))) changed.add(path + "count");
        CompoundTag first = source.getCompound("components");
        CompoundTag second = target.getCompound("components");
        for (String name : first.getAllKeys()) {
            if (Objects.equals(first.get(name), second.get(name))) continue;
            if (name.equals("minecraft:container") && first.get(name) instanceof ListTag entries && second.get(name) instanceof ListTag after) {
                for (int index = 0; index < entries.size(); index++) {
                    CompoundTag entry = entries.getCompound(index);
                    int slot = entry.getInt("slot");
                    CompoundTag match = null;
                    for (int other = 0; other < after.size(); other++) if (after.getCompound(other).getInt("slot") == slot) match = after.getCompound(other);
                    if (match == null) changed.add(path + "container[" + slot + "]missing");
                    else itemDifferences(entry.getCompound("item"), match.getCompound("item"), path + "container[" + slot + "].", changed, depth + 1);
                }
            } else changed.add(path + name);
        }
    }

    private static void preserveCustomNbt(JsonElement json, Tag typed) {
        if (json.isJsonObject() && typed instanceof CompoundTag compound) {
            JsonObject object = json.getAsJsonObject();
            for (String key : object.keySet()) {
                Tag child = compound.get(key);
                if (child == null) continue;
                if (key.equals("components") && object.get(key).isJsonObject() && child instanceof CompoundTag components) {
                    JsonObject values = object.getAsJsonObject(key);
                    for (String name : CUSTOM_NBT_COMPONENTS) {
                        if (values.has(name) && components.get(name) instanceof CompoundTag data) {
                            // CustomData.CODEC accepts SNBT via its TagParser alternative. Plain JSON narrows
                            // NBT number widths and loses typed arrays; the native codec's SNBT form preserves them.
                            values.addProperty(name, data.toString());
                        }
                    }
                }
                preserveCustomNbt(object.get(key), child);
            }
        } else if (json.isJsonArray() && typed instanceof ListTag list) {
            JsonArray array = json.getAsJsonArray();
            if (array.size() != list.size()) throw invalid("typed nested item list differs from JSON");
            for (int index = 0; index < array.size(); index++) preserveCustomNbt(array.get(index), list.get(index));
        }
    }

    private static <T> T require(DataResult<T> result, String action) {
        // Codec errors can embed arbitrary item lore/PDC. Report the action/slot without disclosing values.
        if (result.error().isPresent()) throw invalid(action + " failed");
        return result.result().orElseThrow(() -> invalid(action + " returned no value"));
    }

    private static JsonElement canonical(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            for (String key : new TreeSet<>(element.getAsJsonObject().keySet())) {
                sorted.add(key, canonical(element.getAsJsonObject().get(key)));
            }
            return sorted;
        }
        if (element.isJsonArray()) {
            JsonArray sorted = new JsonArray();
            for (JsonElement entry : element.getAsJsonArray()) sorted.add(canonical(entry));
            return sorted;
        }
        return element.deepCopy();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid native chest: " + reason);
    }
}
