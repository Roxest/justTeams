package com.pixelclub.teams.converter;

import com.pixelclub.teams.chest.NativeChestCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Collection;
import java.util.Base64;

/** Uses the live CraftServer class loader; no Bukkit class is present in a native runtime ABI. */
final class BukkitChestBridge {
    private static final int MAX_STREAM_BYTES = 12 * 1024 * 1024;
    private static final Set<String> SERIAL_CLASSES = Set.of(
            "org.bukkit.util.io.Wrapper", "org.bukkit.inventory.ItemStack",
            "java.lang.Object", "java.lang.String", "java.lang.Number", "java.lang.Integer", "java.util.UUID",
            "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
            "java.lang.Float", "java.lang.Boolean", "java.lang.Character", "java.lang.Enum",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.Map$Entry",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet", "java.util.LinkedHashSet",
            "java.util.Arrays$ArrayList", "java.util.Collections$EmptyList", "java.util.Collections$EmptyMap",
            "java.util.Collections$SingletonList", "java.util.Collections$SingletonMap",
            "java.util.Collections$UnmodifiableCollection", "java.util.Collections$UnmodifiableList",
            "java.util.Collections$UnmodifiableRandomAccessList", "java.util.Collections$UnmodifiableMap",
            "com.google.common.collect.ImmutableMap$SerializedForm",
            "com.google.common.collect.ImmutableBiMap$SerializedForm",
            "com.google.common.collect.ImmutableList$SerializedForm",
            "com.google.common.collect.ImmutableSet$SerializedForm",
            "com.google.common.collect.RegularImmutableMap", "com.google.common.collect.RegularImmutableBiMap",
            "com.google.common.collect.SingletonImmutableBiMap", "org.bukkit.attribute.AttributeModifier",
            "org.bukkit.Color", "org.bukkit.FireworkEffect", "org.bukkit.potion.PotionEffect",
            "org.bukkit.block.banner.Pattern", "org.bukkit.util.Vector", "org.bukkit.util.BlockVector",
            "org.bukkit.util.BoundingBox", "org.bukkit.Location",
            "com.google.common.collect.RegularImmutableList", "com.google.common.collect.SingletonImmutableList",
            "com.google.common.collect.RegularImmutableSet", "com.google.common.collect.SingletonImmutableSet");
    private final Class<?> bukkitItem;
    private final Class<?> inputStream;
    private final Class<?> outputStream;
    private final Method asNative;
    private final Method asBukkit;
    private final Method decodeLines;
    private final Method encodeLines;
    private final String craftInventoryPackage;
    private final String craftProfileClass;
    private String rejectedClass;
    private final net.minecraft.core.RegistryAccess registries;
    private final ClassLoader bukkitLoader;
    private final Field wrapperMap;

    BukkitChestBridge(MinecraftServer server) throws ReflectiveOperationException {
        registries = server.registryAccess();
        Object craftServer = server.getClass().getMethod("bridge$getServer").invoke(server);
        if (craftServer == null) throw new IllegalStateException("CraftServer is unavailable");
        ClassLoader loader = craftServer.getClass().getClassLoader();
        bukkitLoader = loader;
        wrapperMap = loader.loadClass("org.bukkit.util.io.Wrapper").getDeclaredField("map");
        wrapperMap.setAccessible(true);
        bukkitItem = loader.loadClass("org.bukkit.inventory.ItemStack");
        inputStream = loader.loadClass("org.bukkit.util.io.BukkitObjectInputStream");
        outputStream = loader.loadClass("org.bukkit.util.io.BukkitObjectOutputStream");
        craftInventoryPackage = craftServer.getClass().getPackageName() + ".inventory";
        craftProfileClass = craftServer.getClass().getPackageName() + ".profile.CraftPlayerProfile";
        Class<?> craftItem = loader.loadClass(craftInventoryPackage + ".CraftItemStack");
        asNative = craftItem.getMethod("asNMSCopy", bukkitItem);
        asBukkit = craftItem.getMethod("asBukkitCopy", ItemStack.class);
        Class<?> base64 = loader.loadClass("org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder");
        decodeLines = base64.getMethod("decodeLines", String.class);
        encodeLines = base64.getMethod("encodeLines", byte[].class);
    }

    List<ItemStack> decode(String blob) throws Exception {
        if (blob == null || blob.isBlank() || blob.length() > NativeChestCodec.MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Missing or oversized donor blob");
        }
        byte[] bytes = (byte[]) decodeLines.invoke(null, blob);
        if (bytes.length > MAX_STREAM_BYTES || bytes.length < 8 || bytes[0] != (byte) 0xac
                || bytes[1] != (byte) 0xed || bytes[2] != 0 || bytes[3] != 5) {
            throw new IllegalArgumentException("Invalid donor Java stream header or size");
        }
        List<JsonElement> rawSource = rawSlots(bytes);
        try (ObjectInputStream stream = (ObjectInputStream) inputStream
                .getConstructor(java.io.InputStream.class).newInstance(new ByteArrayInputStream(bytes))) {
            rejectedClass = null;
            stream.setObjectInputFilter(this::filter);
            int size = stream.readInt();
            NativeChestCodec.validateSize(size);
            List<ItemStack> result = new ArrayList<>(size);
            List<Object> bukkitSources = new ArrayList<>(size);
            for (int slot = 0; slot < size; slot++) {
                Object source = stream.readObject();
                bukkitSources.add(source);
                if (source == null) { result.add(ItemStack.EMPTY); continue; }
                if (!bukkitItem.isInstance(source)) throw new IllegalArgumentException("Non-item donor slot " + slot);
                ItemStack stack = (ItemStack) asNative.invoke(null, source);
                if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Donor slot " + slot + " became empty");
                Object reversed = asBukkit.invoke(null, stack);
                if (!source.equals(reversed)) throw new IllegalArgumentException("Donor slot " + slot + " loses Bukkit metadata");
                result.add(stack);
            }
            if (stream.read() != -1) throw new IllegalArgumentException("Trailing donor stream data");
            List<JsonElement> rawRestored = rawSlots(serializeBukkit(bukkitSources));
            if (rawSource.size() != rawRestored.size()) throw new IllegalArgumentException("Bukkit raw slot count changed");
            for (int slot = 0; slot < size; slot++) {
                if (!rawSource.get(slot).equals(rawRestored.get(slot))) {
                    throw new IllegalArgumentException("Donor slot " + slot + " loses raw Bukkit serialization fields "
                            + rawDifferences(rawSource.get(slot), rawRestored.get(slot), "", 0));
                }
            }
            return result;
        } catch (java.io.InvalidClassException exception) {
            if (rejectedClass != null) throw new UnsupportedClass(rejectedClass);
            throw exception;
        }
    }

    private static String rawDifferences(JsonElement first, JsonElement second, String path, int depth) {
        Set<String> safe = Set.of("v", "type", "amount", "meta", "==", "meta-type", "display-name", "item-name",
                "lore", "internal", "unhandled", "enchants", "attribute-modifiers", "color", "damage", "repair-cost",
                "custom-model-data", "ItemFlags", "PublicBukkitValues", "blockMaterial", "custom", "potion-type");
        if (depth > 3 || !first.isJsonObject() || !second.isJsonObject()) return path;
        List<String> changed = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>(first.getAsJsonObject().keySet());
        names.addAll(second.getAsJsonObject().keySet());
        for (String name : names) {
            JsonElement left = first.getAsJsonObject().get(name);
            JsonElement right = second.getAsJsonObject().get(name);
            if (java.util.Objects.equals(left, right)) continue;
            String label = path + (safe.contains(name) ? name : "opaque") + ".";
            changed.add(left != null && right != null ? rawDifferences(left, right, label, depth + 1) : label);
        }
        return String.join(",", changed);
    }

    private byte[] serializeBukkit(List<Object> stacks) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = (ObjectOutputStream) outputStream
                .getConstructor(java.io.OutputStream.class).newInstance(bytes)) {
            stream.writeInt(stacks.size());
            for (Object stack : stacks) stream.writeObject(stack);
        }
        return bytes.toByteArray();
    }

    /** Read wrappers without Bukkit resolveObject; compare maps before any unknown meta/enchant can disappear. */
    private List<JsonElement> rawSlots(byte[] bytes) throws Exception {
        try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
                return descriptor.getName().startsWith("[") ? super.resolveClass(descriptor) : bukkitLoader.loadClass(descriptor.getName());
            }
        }) {
            rejectedClass = null;
            stream.setObjectInputFilter(this::filter);
            int size = stream.readInt();
            NativeChestCodec.validateSize(size);
            List<JsonElement> result = new ArrayList<>(size);
            for (int slot = 0; slot < size; slot++) result.add(NativeChestCodec.parseStrict(rawJson(stream.readObject(), 0).toString()));
            if (stream.read() != -1) throw new IllegalArgumentException("Trailing raw donor stream data");
            return result;
        } catch (java.io.InvalidClassException exception) {
            if (rejectedClass != null) throw new UnsupportedClass(rejectedClass);
            throw exception;
        }
    }

    private JsonElement rawJson(Object value, int depth) throws Exception {
        if (depth > 128) throw new IllegalArgumentException("Raw Bukkit nesting limit");
        if (value == null) return JsonNull.INSTANCE;
        if (value.getClass().getName().equals("org.bukkit.util.io.Wrapper")) return rawJson(wrapperMap.get(value), depth + 1);
        if (value instanceof String string) return new JsonPrimitive(string);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof Boolean bool) return new JsonPrimitive(bool);
        if (value instanceof Character character) return new JsonPrimitive(character);
        if (value instanceof java.util.UUID uuid) return new JsonPrimitive(uuid.toString());
        if (value instanceof Map<?, ?> map) {
            JsonObject result = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Non-string raw Bukkit key");
                Object child = entry.getValue();
                if (key.equals("internal") && "ItemMeta".equals(map.get("==")) && child instanceof String encoded) {
                    byte[] compressed = Base64.getMimeDecoder().decode(encoded);
                    if (compressed.length >= 2 && compressed[0] == (byte) 0x1f && compressed[1] == (byte) 0x8b) {
                        result.addProperty(key, NbtIo.readCompressed(new ByteArrayInputStream(compressed),
                                NbtAccounter.create(16L * 1024 * 1024)).toString());
                        continue;
                    }
                }
                result.add(key, rawJson(child, depth + 1));
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            JsonArray result = new JsonArray();
            for (Object child : collection) result.add(rawJson(child, depth + 1));
            return result;
        }
        if (value.getClass().isArray()) {
            JsonObject result = new JsonObject();
            result.addProperty("array_type", value.getClass().getComponentType().getTypeName());
            JsonArray values = new JsonArray();
            for (int index = 0; index < Array.getLength(value); index++) values.add(rawJson(Array.get(value, index), depth + 1));
            result.add("values", values);
            return result;
        }
        throw new UnsupportedClass(value.getClass().getName());
    }

    String encode(List<ItemStack> stacks) throws Exception {
        NativeChestCodec.validateSize(stacks.size());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = (ObjectOutputStream) outputStream
                .getConstructor(java.io.OutputStream.class).newInstance(bytes)) {
            stream.writeInt(stacks.size());
            for (int slot = 0; slot < stacks.size(); slot++) {
                ItemStack nativeItem = stacks.get(slot);
                if (nativeItem == null || nativeItem.isEmpty()) { stream.writeObject(null); continue; }
                Object converted = asBukkit.invoke(null, nativeItem);
                ItemStack roundtrip = (ItemStack) asNative.invoke(null, converted);
                if (!NativeChestCodec.samePersistentItem(nativeItem, roundtrip, registries)) {
                    throw new IllegalArgumentException("Native slot " + slot + " loses data in Bukkit conversion");
                }
                stream.writeObject(converted);
            }
        }
        return (String) encodeLines.invoke(null, (Object) bytes.toByteArray());
    }

    private ObjectInputFilter.Status filter(ObjectInputFilter.FilterInfo info) {
        if (info.depth() > 128 || info.references() > 250_000 || info.streamBytes() > MAX_STREAM_BYTES
                || info.arrayLength() > 1_000_000) return ObjectInputFilter.Status.REJECTED;
        Class<?> type = info.serialClass();
        if (type == null) return ObjectInputFilter.Status.UNDECIDED;
        while (type.isArray()) type = type.componentType();
        // resolveObject also filters Bukkit's reconstructed CraftMeta/ItemStack instances.
        boolean craftResult = type.getPackageName().equals(craftInventoryPackage)
                && (type.getSimpleName().startsWith("CraftMeta") || type.getSimpleName().equals("CraftItemStack"));
        if (type.isPrimitive() || SERIAL_CLASSES.contains(type.getName()) || craftResult || type.getName().equals(craftProfileClass)) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        rejectedClass = type.getName();
        return ObjectInputFilter.Status.REJECTED;
    }

    static final class UnsupportedClass extends IllegalArgumentException {
        UnsupportedClass(String name) { super(name); }
    }
}
