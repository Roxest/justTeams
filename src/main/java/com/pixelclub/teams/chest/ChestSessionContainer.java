package com.pixelclub.teams.chest;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One team/player/generation lease. The caller owns the team-id lock and persistence CAS. */
public final class ChestSessionContainer extends SimpleContainer {
    private final int teamId;
    private final UUID playerId;
    private final long generation;
    private final BooleanSupplier leaseValid;
    private boolean open = true;

    public ChestSessionContainer(int teamId, UUID playerId, long generation,
                                 List<ItemStack> stacks, BooleanSupplier leaseValid) {
        super(checkedSize(stacks));
        if (teamId <= 0 || generation < 0) throw new IllegalArgumentException("Invalid chest lease identity");
        this.teamId = teamId;
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.generation = generation;
        this.leaseValid = Objects.requireNonNull(leaseValid, "leaseValid");
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            // SimpleContainer#setItem clamps oversized stacks; importing must never truncate donor counts.
            getItems().set(slot, stack == null ? ItemStack.EMPTY : stack.copy());
        }
    }

    public int teamId() { return teamId; }
    public UUID playerId() { return playerId; }
    public long generation() { return generation; }
    public void revoke() { open = false; }
    public boolean isOpen() { return open; }

    @Override
    public boolean stillValid(Player player) {
        return open && playerId.equals(player.getUUID()) && leaseValid.getAsBoolean();
    }

    public List<ItemStack> snapshot() {
        List<ItemStack> result = new ArrayList<>(getContainerSize());
        for (int slot = 0; slot < getContainerSize(); slot++) result.add(getItem(slot).copy());
        return result;
    }

    private static int checkedSize(List<ItemStack> stacks) {
        Objects.requireNonNull(stacks, "stacks");
        NativeChestCodec.validateSize(stacks.size());
        return stacks.size();
    }
}
