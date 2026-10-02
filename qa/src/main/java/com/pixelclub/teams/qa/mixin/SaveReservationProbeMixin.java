package com.pixelclub.teams.qa.mixin;
import com.pixelclub.teams.qa.RuntimeFixture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="net.impactdev.impactor.core.economy.storage.EconomyStorage",remap=false)
public abstract class SaveReservationProbeMixin {
    @Inject(method="save(Lnet/impactdev/impactor/api/economy/accounts/Account;)Ljava/util/concurrent/CompletableFuture;",at=@At("HEAD"),require=1)
    private void originalBodyEntry(@Coerce Object account,CallbackInfoReturnable<Object> callback){RuntimeFixture.observeOriginalSave(account);}
}
