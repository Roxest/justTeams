package com.pixelclub.teams.chest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.food.FoodProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NativeChestCodecTest {
    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void preservesSlotsCountsAndFullPersistentComponents() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.DAMAGE, 42);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("test sword"));
        sword.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("line one"), Component.literal("line two"))));
        CompoundTag tag = new CompoundTag();
        tag.putString("opaque_plugin_key", "preserve");
        tag.putByte("byte", (byte) 1);
        tag.putShort("short", (short) 1);
        tag.putInt("integer", 1);
        tag.putLong("long", 1);
        tag.putByteArray("bytes", new byte[]{1, 2});
        tag.putIntArray("ints", new int[]{1, 2});
        tag.putLongArray("longs", new long[]{1, Long.MAX_VALUE});
        CompoundTag nested = new CompoundTag();
        nested.putLong("counter", 1234567890123L);
        tag.put("nested", nested);
        sword.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        List<ItemStack> input = new ArrayList<>(Collections.nCopies(27, ItemStack.EMPTY));
        input.set(0, new ItemStack(Items.STONE, 64));
        input.set(26, sword);
        String encoded = NativeChestCodec.encode(input, registries);
        List<ItemStack> output = NativeChestCodec.decode(encoded, registries);
        assertEquals(27, output.size());
        for (int slot = 0; slot < input.size(); slot++) {
            assertTrue(NativeChestCodec.samePersistentItem(input.get(slot), output.get(slot), registries), "slot " + slot);
        }
        assertEquals(sword.get(DataComponents.CUSTOM_DATA), output.get(26).get(DataComponents.CUSTOM_DATA));
        assertEquals(sword.get(DataComponents.DAMAGE), output.get(26).get(DataComponents.DAMAGE));
        assertEquals(encoded, NativeChestCodec.encode(output, registries));
    }
    @Test void scientificFloatPayloadSurvivesEncodeDecodeAndChecksumParsing(){
        ItemStack item=new ItemStack(Items.STONE);CompoundTag data=new CompoundTag();data.putFloat("tiny",1.0e-5f);data.putFloat("large",1.0e20f);data.putDouble("tiny_double",1.0e-30);item.set(DataComponents.CUSTOM_DATA,CustomData.of(data));List<ItemStack> contents=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));contents.set(0,item);String encoded=NativeChestCodec.encode(contents,registries);List<ItemStack> decoded=NativeChestCodec.decode(encoded,registries);assertTrue(NativeChestCodec.samePersistentItem(item,decoded.getFirst(),registries));assertEquals(encoded,NativeChestCodec.encode(decoded,registries));
        JsonObject value=new JsonObject();value.addProperty("tiny",1.0e-5f);value.addProperty("large",1.0e20f);assertEquals(NativeChestCodec.semanticSha256(value),NativeChestCodec.semanticSha256(NativeChestCodec.parseStrict(value.toString())));
    }

    @Test
    void rejectsUnknownItemComponentAndSilentExtraItemFields() {
        for (String item : List.of("{\"id\":\"missing:unknown\"}",
                "{\"id\":\"minecraft:stone\",\"components\":{\"missing:component\":1}}",
                "{\"id\":\"minecraft:stone\",\"unknown_property\":true}")) {
            List<ItemStack> slots = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
            slots.set(0, new ItemStack(Items.STONE));
            JsonObject document = NativeChestCodec.parseStrict(NativeChestCodec.encode(slots, registries)).getAsJsonObject();
            JsonObject record = document.getAsJsonArray("slots").get(0).getAsJsonObject();
            JsonObject changes = NativeChestCodec.parseStrict(item).getAsJsonObject();
            for (String key : changes.keySet()) record.getAsJsonObject("item").add(key, changes.get(key));
            sign(document);
            assertThrows(IllegalArgumentException.class, () -> NativeChestCodec.decode(document.toString(), registries));
        }
    }

    @Test
    void rejectsMalformedSizeHashDuplicatePropertiesAndTrailingData() {
        for (int size : List.of(0, 8, 10, 55, 63)) {
            assertThrows(IllegalArgumentException.class,
                    () -> NativeChestCodec.encode(Collections.nCopies(size, ItemStack.EMPTY), registries));
        }
        JsonObject shortSlots = emptyDocument(9);
        shortSlots.getAsJsonArray("slots").remove(8);
        sign(shortSlots);
        assertThrows(IllegalArgumentException.class, () -> NativeChestCodec.decode(shortSlots.toString(), registries));
        JsonObject damagedHash = NativeChestCodec.parseStrict(
                NativeChestCodec.encode(Collections.nCopies(9, ItemStack.EMPTY), registries)).getAsJsonObject();
        damagedHash.addProperty("sha256", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> NativeChestCodec.decode(damagedHash.toString(), registries));
        assertThrows(IllegalArgumentException.class, () -> NativeChestCodec.parseStrict("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> NativeChestCodec.parseStrict("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> NativeChestCodec.parseStrict("{unquoted:1}"));
        JsonObject unsigned = emptyDocument(9);
        unsigned.remove("sha256");
        assertThrows(IllegalArgumentException.class, () -> NativeChestCodec.decode(unsigned.toString(), registries));
    }

    @Test
    void sessionSnapshotPreservesOversizedDonorCountsWithoutAliasing() {
        ItemStack source = new ItemStack(Items.STONE, 99);
        List<ItemStack> stacks = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        stacks.set(8, source);
        ChestSessionContainer session = new ChestSessionContainer(1, UUID.randomUUID(), 7, stacks, () -> true);
        source.setCount(1);
        assertEquals(99, session.getItem(8).getCount());
        List<ItemStack> snapshot = session.snapshot();
        snapshot.get(8).setCount(2);
        assertEquals(99, session.getItem(8).getCount());
        assertEquals(7, session.generation());
        session.revoke();
        assertFalse(session.isOpen());
    }

    @Test
    void comparesSerializedFloatJsonWithoutDoubleWideningFalsePositive() {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.set(DataComponents.FOOD, new FoodProperties.Builder().nutrition(4).saturationModifier(1.05F).build());
        List<ItemStack> items = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        items.set(0, stack);
        String json = NativeChestCodec.encode(items, registries);
        ItemStack restored = NativeChestCodec.decode(json, registries).getFirst();
        assertEquals(stack.get(DataComponents.FOOD), restored.get(DataComponents.FOOD));
        assertEquals(json, NativeChestCodec.encode(NativeChestCodec.decode(json, registries), registries));
    }

    @Test
    void canonicalizesEmptyContainerCapacityWithoutRemovingContentsOrMutatingSource() throws Exception {
        // CraftMeta can carry all 27 empty cells; the native CODEC restores the same empty inventory as EMPTY.
        var constructor = ItemContainerContents.class.getDeclaredConstructor(NonNullList.class);
        constructor.setAccessible(true);
        ItemContainerContents capacity27 = constructor.newInstance(NonNullList.withSize(27, ItemStack.EMPTY));
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, capacity27);
        List<ItemStack> items = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        items.set(0, shulker);
        String json = NativeChestCodec.encode(items, registries);
        ItemStack restored = NativeChestCodec.decode(json, registries).getFirst();
        assertTrue(NativeChestCodec.samePersistentItem(shulker, restored, registries));
        assertEquals(0, restored.get(DataComponents.CONTAINER).nonEmptyStream().count());
        assertEquals(27, shulker.get(DataComponents.CONTAINER).stream().count());
        assertEquals(json, NativeChestCodec.encode(NativeChestCodec.decode(json, registries), registries));
    }

    private static JsonObject emptyDocument(int size) {
        JsonObject result = new JsonObject();
        result.addProperty("format", 1);
        result.addProperty("size", size);
        JsonArray slots = new JsonArray();
        for (int slot = 0; slot < size; slot++) slots.add(com.google.gson.JsonNull.INSTANCE);
        result.add("slots", slots);
        sign(result);
        return result;
    }

    private static void sign(JsonObject document) {
        document.remove("sha256");
        document.addProperty("sha256", NativeChestCodec.semanticSha256(document));
    }
}
