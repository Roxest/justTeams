package com.pixelclub.teams.chest;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Recipient custody: creative overflow must remain in the receipt, never disappear. */
public final class OwnedInventoryReturn {
    private OwnedInventoryReturn(){}
    public static void insert(Inventory inventory,ItemStack remaining){insert(inventory.items,inventory.offhand.getFirst(),inventory.selected,inventory.getMaxStackSize(),remaining);inventory.setChanged();}
    public static void insert(List<ItemStack> main,ItemStack offhand,int selected,int containerLimit,ItemStack remaining){
        if(remaining.isEmpty())return;if(main.size()!=36||containerLimit<1)throw new IllegalArgumentException("Invalid recipient inventory");
        if(selected>=0&&selected<9)merge(main.get(selected),remaining,containerLimit);merge(offhand,remaining,containerLimit);
        for(int i=0;i<main.size()&&!remaining.isEmpty();i++)if(i!=selected)merge(main.get(i),remaining,containerLimit);
        for(int i=0;i<main.size()&&!remaining.isEmpty();i++)if(main.get(i).isEmpty()){int amount=Math.min(remaining.getCount(),Math.min(containerLimit,remaining.getMaxStackSize()));if(amount<1)break;main.set(i,remaining.copyWithCount(amount));remaining.shrink(amount);}
    }
    private static void merge(ItemStack target,ItemStack remaining,int limit){if(target.isEmpty()||remaining.isEmpty()||!ItemStack.isSameItemSameComponents(target,remaining))return;int amount=Math.min(remaining.getCount(),Math.max(0,Math.min(limit,target.getMaxStackSize())-target.getCount()));if(amount>0){target.grow(amount);remaining.shrink(amount);}}
}
