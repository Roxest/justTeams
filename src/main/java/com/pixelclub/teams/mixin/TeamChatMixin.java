package com.pixelclub.teams.mixin;

import com.pixelclub.teams.TeamsMod;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class TeamChatMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="broadcastChatMessage",at=@At("HEAD"),cancellable=true)
    private void teamsChat(PlayerChatMessage message,CallbackInfo callback){
        var core=TeamsMod.core();if(core==null||!core.wantsChat(player,message.signedContent()))return;
        // Signing/last-seen validation has already happened; mutations always run on the server thread.
        if(player.getServer().isSameThread())TeamsMod.chat(player,message.signedContent());else player.getServer().execute(()->TeamsMod.chat(player,message.signedContent()));
        callback.cancel();
    }
}
