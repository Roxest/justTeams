package com.pixelclub.teams.mixin;

import com.pixelclub.teams.money.WalletFence;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.io.UncheckedIOException;
import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Does not alter balances or serialization. Save errors propagate and successful files are forced to disk. */
@Mixin(targets="net.impactdev.impactor.core.economy.storage.implementations.ConfigurateProvider",remap=false)
public abstract class ConfigurateSaveReceiptMixin {
    private static final String TARGET="save(Ljava/nio/file/Path;Lnet/impactdev/impactor/api/economy/accounts/Account;)V";
    @Inject(method=TARGET,at=@At("HEAD"),require=1)
    private void captureRealRelativeLock(Path path,@Coerce Object account,CallbackInfo callback){WalletFence.capture(this,path,account);}
    @Redirect(method=TARGET,at=@At(value="INVOKE",target="Ljava/lang/Exception;printStackTrace()V"),require=1)
    private void propagateFailure(Exception error){throw new IllegalStateException("Impactor account save failed",error);}
    @Inject(method=TARGET,at=@At("RETURN"),require=1)
    private void forceReceipt(Path path,@Coerce Object account,CallbackInfo callback){try(FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}catch(IOException error){throw new UncheckedIOException("Impactor account force failed",error);}}
}
