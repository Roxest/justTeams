package com.pixelclub.teams;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.*;

public final class TeamsMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String pack){}
    @Override public String getRefMapperConfig(){return null;}
    @Override public boolean shouldApplyMixin(String target,String mixin){return !target.startsWith("net.impactdev.impactor.")||FabricLoader.getInstance().isModLoaded("impactor");}
    @Override public void acceptTargets(Set<String> own,Set<String> other){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String name,ClassNode node,String mixin,IMixinInfo info){}
    @Override public void postApply(String name,ClassNode node,String mixin,IMixinInfo info){}
}
