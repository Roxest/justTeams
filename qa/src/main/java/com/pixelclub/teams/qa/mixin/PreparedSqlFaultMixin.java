package com.pixelclub.teams.qa.mixin;
import com.pixelclub.teams.qa.RuntimeFixture;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Closes the actual fixture pool after a real prepared SQL commit, with no mock JDBC. */
@Mixin(targets="com.pixelclub.teams.store.TeamStore",remap=false)
public abstract class PreparedSqlFaultMixin {
    @Inject(method="beginOperation(Ljava/lang/String;ILjava/util/UUID;Ljava/lang/String;DLjava/lang/String;Ljava/lang/String;)Lcom/pixelclub/teams/store/TeamStore$Operation;",at=@At("RETURN"),require=1)
    private void afterPrepared(String op,int team,UUID player,String kind,double amount,String phase,String payload,CallbackInfoReturnable<Object> callback){RuntimeFixture.afterPrepared(kind,phase);}
}
