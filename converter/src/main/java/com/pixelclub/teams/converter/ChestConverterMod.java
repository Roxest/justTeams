package com.pixelclub.teams.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pixelclub.teams.chest.NativeChestCodec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

/** Offline file transformer. Refuses a non-loopback/non-isolated server and never opens a database. */
public final class ChestConverterMod implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Set<String> FORWARD_FIELDS = Set.of("team_id", "blob", "source_sha256");
    private static final Set<String> REVERSE_FIELDS = Set.of("team_id", "inventory_json", "original_sha256");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("jtchestconvert")
                        .requires(source -> source.hasPermission(4) && source.getEntity() == null)
                        .then(Commands.literal("forward").executes(context -> run(context.getSource(), false)))
                        .then(Commands.literal("reverse").executes(context -> run(context.getSource(), true)))
                        .then(Commands.literal("verify").executes(context -> verifyNative(context.getSource())))
                        .then(Commands.literal("fixtures").executes(context -> fixtures(context.getSource())))));
    }

    private static int fixtures(CommandSourceStack source) {
        try {
            Path output = requireIsolated(source.getServer()).resolve("fixtures.json");
            if (Files.exists(output)) throw new IllegalArgumentException("Output exists; refusing overwrite");
            List<ItemStack> items = new ArrayList<>(Collections.nCopies(27, ItemStack.EMPTY));
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.set(DataComponents.CUSTOM_NAME, Component.literal("Chest codec fixture"));
            sword.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("first"), Component.literal("second"))));
            sword.set(DataComponents.DAMAGE, 123);
            var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            enchantments.set(source.getServer().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.UNBREAKING), 3);
            sword.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
            CompoundTag custom = new CompoundTag();
            custom.putString("opaque_plugin_metadata", "preserve");
            custom.putLongArray("opaque_longs", new long[]{1L, Long.MAX_VALUE});
            sword.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
            items.set(0, sword);
            ItemStack potion = new ItemStack(Items.POTION);
            potion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING));
            items.set(1, potion);
            ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(sword.copy(), potion.copy())));
            items.set(2, shulker);
            items.set(3, new ItemStack(Items.STONE, 99));
            int slot = 4;
            for (String id : List.of("cobblemon:master_ball", "sophisticatedbackpacks:backpack")) {
                ResourceLocation key = ResourceLocation.parse(id);
                if (!BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalArgumentException("Required fixture item absent");
                ItemStack modItem = new ItemStack(BuiltInRegistries.ITEM.get(key));
                modItem.set(DataComponents.CUSTOM_DATA, CustomData.of(custom.copy()));
                items.set(slot++, modItem);
            }
            JsonObject row = new JsonObject();
            row.addProperty("team_id", Integer.MAX_VALUE);
            row.addProperty("inventory_json", NativeChestCodec.encode(items, source.getServer().registryAccess()));
            row.add("original_sha256", com.google.gson.JsonNull.INSTANCE);
            JsonArray rows = new JsonArray();
            rows.add(row);
            JsonObject document = new JsonObject();
            document.addProperty("format", 1);
            document.add("rows", rows);
            atomicWrite(output, GSON.toJson(document));
            source.sendSuccess(() -> Component.literal("Native chest fixtures PASS: 1 row"), false);
            return 1;
        } catch (Exception exception) {
            // Fixtures contain only synthetic text; their detailed failure has no donor metadata.
            source.sendFailure(Component.literal("Native chest fixtures ABORT; no output: " + exception.getMessage()));
            return 0;
        }
    }

    private static int run(CommandSourceStack source, boolean reverse) {
        int currentTeam = 0;
        String stage = "preflight";
        try {
            MinecraftServer server = source.getServer();
            Path directory = requireIsolated(server);
            Path input = directory.resolve(reverse ? "native-input.json" : "donor-input.json");
            Path output = directory.resolve(reverse ? "reverse-output.json" : "native-output.json");
            if (Files.exists(output)) throw new IllegalArgumentException("Output exists; refusing overwrite");
            byte[] sourceBytes = readInput(input);
            JsonObject document = envelope(sourceBytes);
            BukkitChestBridge bridge = new BukkitChestBridge(server);
            JsonArray converted = new JsonArray();
            Set<Integer> teamIds = new HashSet<>();
            for (JsonElement element : document.getAsJsonArray("rows")) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("Row must be an object");
                JsonObject row = element.getAsJsonObject();
                if (!(reverse ? REVERSE_FIELDS : FORWARD_FIELDS).containsAll(row.keySet())) {
                    throw new IllegalArgumentException("Unknown row field");
                }
                int team = teamId(row, teamIds);
                currentTeam = team;
                String originalHash;
                List<ItemStack> stacks;
                String nativeJson;
                if (reverse) {
                    nativeJson = text(row, "inventory_json");
                    originalHash = hash(row, "original_sha256", false);
                    stage = "native-decode";
                    stacks = NativeChestCodec.decode(nativeJson, server.registryAccess());
                } else {
                    String blob = text(row, "blob");
                    originalHash = hash(row, "source_sha256", true);
                    if (!originalHash.equals(NativeChestCodec.sha256(blob.getBytes(StandardCharsets.UTF_8)))) {
                        throw new IllegalArgumentException("Source hash mismatch for team " + team);
                    }
                    stage = "bukkit-decode";
                    stacks = bridge.decode(blob);
                    stage = "native-encode";
                    nativeJson = NativeChestCodec.encode(stacks, server.registryAccess());
                }
                stage = "native-verify";
                List<ItemStack> decoded = NativeChestCodec.decode(nativeJson, server.registryAccess());
                stage = "reverse-encode";
                String reverseBlob = bridge.encode(decoded);
                stage = "reverse-decode";
                List<ItemStack> bukkitRoundtrip = bridge.decode(reverseBlob);
                stage = "semantic-compare";
                String roundtripJson = NativeChestCodec.encode(bukkitRoundtrip, server.registryAccess());
                if (!sameInventory(nativeJson, roundtripJson)) {
                    throw new IllegalArgumentException("Reverse semantic mismatch for team " + team);
                }
                JsonObject result = new JsonObject();
                result.addProperty("team_id", team);
                result.addProperty("inventory_json", nativeJson);
                result.addProperty("original_sha256", originalHash);
                result.addProperty("reverse_blob", reverseBlob);
                result.addProperty("reverse_sha256", NativeChestCodec.sha256(reverseBlob.getBytes(StandardCharsets.UTF_8)));
                result.add("slots", manifest(nativeJson));
                converted.add(result);
            }
            JsonObject result = new JsonObject();
            result.addProperty("format", 1);
            result.addProperty("mode", reverse ? "reverse" : "forward");
            result.addProperty("source_file_sha256", NativeChestCodec.sha256(sourceBytes));
            result.addProperty("item_registry_sha256", itemRegistryHash());
            result.add("rows", converted);
            atomicWrite(output, GSON.toJson(result));
            source.sendSuccess(() -> Component.literal("Chest conversion PASS: " + converted.size()
                    + " rows; file-only atomic output " + output.getFileName()), false);
            return converted.size();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Chest conversion ABORT; no output: team=" + currentTeam
                    + " stage=" + stage + " " + safeFailure(exception)));
            return 0;
        }
    }

    private static int verifyNative(CommandSourceStack source) {
        try {
            Path directory = requireIsolated(source.getServer());
            Path output = directory.resolve("fabric-proof.json");
            if (Files.exists(output)) throw new IllegalArgumentException("Output exists; refusing overwrite");
            byte[] input = readInput(directory.resolve("native-input.json"));
            JsonObject document = envelope(input);
            JsonArray manifests = new JsonArray();
            Set<Integer> teams = new HashSet<>();
            for (JsonElement element : document.getAsJsonArray("rows")) {
                JsonObject row = element.getAsJsonObject();
                int team = teamId(row, teams);
                String json = text(row, "inventory_json");
                List<ItemStack> stacks = NativeChestCodec.decode(json, source.getServer().registryAccess());
                String roundtrip = NativeChestCodec.encode(stacks, source.getServer().registryAccess());
                if (!sameInventory(json, roundtrip)) {
                    throw new IllegalArgumentException("Native semantic mismatch for team " + team);
                }
                JsonObject proof = new JsonObject();
                proof.addProperty("team_id", team);
                proof.add("slots", manifest(roundtrip));
                manifests.add(proof);
            }
            JsonObject result = new JsonObject();
            result.addProperty("format", 1);
            result.addProperty("source_file_sha256", NativeChestCodec.sha256(input));
            result.addProperty("item_registry_sha256", itemRegistryHash());
            result.add("rows", manifests);
            atomicWrite(output, GSON.toJson(result));
            source.sendSuccess(() -> Component.literal("Native chest codec PASS: " + manifests.size() + " rows"), false);
            return manifests.size();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Native chest codec ABORT; no output: " + safeFailure(exception)));
            return 0;
        }
    }

    private static Path requireIsolated(MinecraftServer server) throws Exception {
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        if (!Boolean.getBoolean("pixelclub.chestConverter.isolated")
                || !Set.of("127.0.0.1", "::1").contains(server.getLocalIp())
                || !Files.isRegularFile(game.resolve(".justteams-chest-isolated"))) {
            throw new IllegalArgumentException("Requires explicit isolated JVM flag, loopback binding and rig marker");
        }
        Path directory = game.resolve("chest-conversion");
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException("Missing or linked chest-conversion directory");
        }
        return directory;
    }

    private static byte[] readInput(Path input) throws Exception {
        if (!Files.isRegularFile(input) || Files.isSymbolicLink(input)
                || Files.size(input) > NativeChestCodec.MAX_JSON_CHARS * 4L) {
            throw new IllegalArgumentException("Missing, linked or oversized input file");
        }
        return Files.readAllBytes(input);
    }

    private static JsonObject envelope(byte[] bytes) {
        JsonElement parsed = NativeChestCodec.parseStrict(new String(bytes, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Input must be an object");
        JsonObject result = parsed.getAsJsonObject();
        if (!result.has("format") || result.get("format").getAsBigDecimal().intValueExact() != 1
                || !result.has("rows") || !result.get("rows").isJsonArray()) {
            throw new IllegalArgumentException("Requires format 1 and rows array");
        }
        return result;
    }

    private static int teamId(JsonObject row, Set<Integer> seen) {
        int id = row.get("team_id").getAsBigDecimal().intValueExact();
        if (id <= 0 || !seen.add(id)) throw new IllegalArgumentException("Invalid or duplicate team id");
        return id;
    }

    private static String text(JsonObject row, String key) {
        JsonElement value = row.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing string " + key);
        }
        return value.getAsString();
    }

    private static String hash(JsonObject row, String key, boolean required) {
        if (!required && (!row.has(key) || row.get(key).isJsonNull())) return null;
        String hash = text(row, key);
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid " + key);
        return hash;
    }

    private static JsonArray manifest(String nativeJson) {
        JsonArray slots = NativeChestCodec.parseStrict(nativeJson).getAsJsonObject().getAsJsonArray("slots");
        JsonArray manifests = new JsonArray();
        for (int slot = 0; slot < slots.size(); slot++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("sha256", NativeChestCodec.semanticSha256(slots.get(slot)));
            if (slots.get(slot).isJsonNull()) entry.add("item", com.google.gson.JsonNull.INSTANCE);
            else {
                JsonObject record = slots.get(slot).getAsJsonObject();
                entry.add("item", record.get("item").deepCopy());
                entry.add("components_sha256", record.get("components_sha256").deepCopy());
                entry.addProperty("typed_nbt_sha256", NativeChestCodec.sha256(record.get("nbt").getAsString().getBytes(StandardCharsets.UTF_8)));
            }
            manifests.add(entry);
        }
        return manifests;
    }

    private static boolean sameInventory(String left, String right) {
        JsonObject first = NativeChestCodec.parseStrict(left).getAsJsonObject();
        JsonObject second = NativeChestCodec.parseStrict(right).getAsJsonObject();
        first.remove("sha256");
        second.remove("sha256");
        return first.equals(second);
    }

    private static String itemRegistryHash() {
        JsonArray items = new JsonArray();
        BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).sorted().forEach(items::add);
        return NativeChestCodec.semanticSha256(items);
    }

    private static void atomicWrite(Path output, String json) throws Exception {
        Path temporary = Files.createTempFile(output.getParent(), output.getFileName().toString(), ".partial");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String safeFailure(Exception exception) {
        // Only the exception type is reported. Donor item text may contain private player metadata.
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (root instanceof BukkitChestBridge.UnsupportedClass) return "unsupported serialized class " + root.getMessage();
        String message = root.getMessage();
        if (message != null && message.startsWith("Invalid native chest: ")) {
            int details = message.indexOf(" failed:");
            return details < 0 ? message : message.substring(0, details) + " failed";
        }
        if (message != null && message.matches("(?:Donor|Native|Non-item donor) slot [0-9]+ [a-zA-Z ]+")) return message;
        if (message != null && message.matches("Donor slot [0-9]+ loses raw Bukkit serialization fields [a-zA-Z0-9.,-]+")) return message;
        return root.getClass().getSimpleName();
    }
}
