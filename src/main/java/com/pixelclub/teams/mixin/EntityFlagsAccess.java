package com.pixelclub.teams.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.network.syncher.EntityDataAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityFlagsAccess {
    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> pxcTeams$flags(){throw new AssertionError();}
}
