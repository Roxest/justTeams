package com.pixelclub.teams.mixin;

import com.pixelclub.teams.TeamsMod;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.util.*;

/** Preserve viewer glow through ordinary entity flag updates without changing server entity state. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class TeamGlowMetadataMixin {
    @ModifyVariable(method="send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private Packet<?> pxcTeams$viewerGlow(Packet<?> packet){
        var core=TeamsMod.core();if(core==null||!(packet instanceof ClientboundSetEntityDataPacket data)||!((Object)this instanceof ServerGamePacketListenerImpl game)||!core.server.isSameThread())return packet;
        try{var target=game.player.serverLevel().getEntity(data.id());if(!(target instanceof ServerPlayer player)||!core.shouldGlow(game.player,player))return packet;var flags=EntityFlagsAccess.pxcTeams$flags();var replacement=SynchedEntityData.DataValue.create(flags,(byte)(player.getEntityData().get(flags)|0x40));List<SynchedEntityData.DataValue<?>> values=new ArrayList<>(data.packedItems());values.removeIf(value->value.id()==replacement.id());values.add(replacement);return new ClientboundSetEntityDataPacket(data.id(),List.copyOf(values));}catch(RuntimeException error){return packet;}
    }
}
