package com.pixelclub.teams.mixin;

import com.pixelclub.teams.TeamsMod;
import com.pixelclub.teams.TeamsCore;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class TeamDamageMixin {
    @Inject(method="hurt",at=@At("HEAD"),cancellable=true)
    private void preventFriendlyFire(DamageSource source,float amount,CallbackInfoReturnable<Boolean> callback){
        TeamsCore core=TeamsMod.core();if(core==null)return;
        if((Object)this instanceof ServerPlayer victim&&source.getEntity() instanceof ServerPlayer attacker)try{if(core.blockDamage(attacker,victim))callback.setReturnValue(false);}catch(Exception e){callback.setReturnValue(false);core.refuse(attacker,e);}
    }
    @ModifyVariable(method="hurt",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private float teamDamage(float amount,DamageSource source,float original){TeamsCore core=TeamsMod.core();if(core!=null&&(Object)this instanceof ServerPlayer victim&&source.getEntity() instanceof ServerPlayer attacker)try{return core.damage(attacker,victim,amount);}catch(Exception e){core.refuse(attacker,e);}return amount;}
    @Inject(method="hurt",at=@At("RETURN"))
    private void cancelWarmupOnDamage(DamageSource source,float amount,CallbackInfoReturnable<Boolean> callback){TeamsCore core=TeamsMod.core();if(core!=null&&callback.getReturnValueZ()&&(Object)this instanceof ServerPlayer victim){try{core.damageTaken(victim);}catch(Exception e){core.refuse(victim,e);}if(source.getEntity() instanceof ServerPlayer attacker)try{core.pvpLanded(attacker,victim);}catch(Exception e){core.refuse(attacker,e);}}}
}
