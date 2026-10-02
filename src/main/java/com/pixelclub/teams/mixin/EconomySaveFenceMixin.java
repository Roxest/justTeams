package com.pixelclub.teams.mixin;

import com.pixelclub.teams.money.WalletFence;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.impactdev.impactor.api.economy.accounts.Account;

@Mixin(targets="net.impactdev.impactor.core.economy.storage.EconomyStorage",remap=false)
public abstract class EconomySaveFenceMixin {
    @WrapMethod(method="save(Lnet/impactdev/impactor/api/economy/accounts/Account;)Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<Void> trackImplicitSaves(Account account,Operation<CompletableFuture<Void>> original){WalletFence.Ticket ticket=WalletFence.reserve(account);try{CompletableFuture<Void> result=original.call(account);WalletFence.bind(ticket,result);return result;}catch(RuntimeException|Error error){WalletFence.fail(ticket,error);throw error;}}
}
