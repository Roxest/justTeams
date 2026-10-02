package com.pixelclub.teams.chest;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OwnedInventoryReturnTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private List<ItemStack> full(){List<ItemStack> slots=new ArrayList<>();for(int i=0;i<36;i++)slots.add(new ItemStack(Items.STONE,64));return slots;}
    @Test void fullInventoryRetainsEveryUnacceptedItem(){var slots=full();ItemStack pending=new ItemStack(Items.DIAMOND,7);OwnedInventoryReturn.insert(slots,ItemStack.EMPTY,0,99,pending);assertEquals(7,pending.getCount());assertTrue(slots.stream().allMatch(item->item.is(Items.STONE)&&item.getCount()==64));}
    @Test void partialMergeAndEmptySlotConsumeExactlyAcceptedCount(){var slots=full();slots.set(2,new ItemStack(Items.DIAMOND,60));ItemStack pending=new ItemStack(Items.DIAMOND,7);OwnedInventoryReturn.insert(slots,ItemStack.EMPTY,2,99,pending);assertEquals(64,slots.get(2).getCount());assertEquals(3,pending.getCount());slots.set(8,ItemStack.EMPTY);OwnedInventoryReturn.insert(slots,ItemStack.EMPTY,2,99,pending);assertTrue(pending.isEmpty());assertEquals(3,slots.get(8).getCount());}
    @Test void componentsAreNotMergedAndOffhandOnlyUsesExistingStack(){var slots=full();ItemStack named=new ItemStack(Items.DIAMOND,60);named.set(DataComponents.CUSTOM_NAME,Component.literal("different"));slots.set(0,named);ItemStack hand=new ItemStack(Items.DIAMOND,62),pending=new ItemStack(Items.DIAMOND,7);OwnedInventoryReturn.insert(slots,hand,0,99,pending);assertEquals(60,named.getCount());assertEquals(64,hand.getCount());assertEquals(5,pending.getCount());}
}
