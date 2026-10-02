package com.pixelclub.teams.mixin;

import com.pixelclub.teams.money.WalletFence;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="net.impactdev.impactor.core.economy.storage.EconomyStorage",remap=false)
public abstract class EconomySaveFenceMixin {
    @Inject(method="save(Lnet/impactdev/impactor/api/economy/accounts/Account;)Ljava/util/concurrent/CompletableFuture;",at=@At("RETURN"),require=1)
    private void trackImplicitSaves(@Coerce Object account,CallbackInfoReturnable<CompletableFuture<Void>> callback){WalletFence.track(account,callback.getReturnValue());}
}
