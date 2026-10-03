package com.pixelclub.teams;

import java.util.UUID;

/** Optional hybrid-server authentication, with no Bukkit linkage on pure Fabric. */
final class AuthMeBridge {
    private AuthMeBridge() {}
    /** null means no hybrid provider; any present-provider failure is a denial. */
    static Boolean authenticated(UUID uuid,String name) {
        if(uuid==null||name==null)return false;
        final Class<?> bukkit;
        try{bukkit=Class.forName("org.bukkit.Bukkit",false,AuthMeBridge.class.getClassLoader());}
        catch(ClassNotFoundException absent){return null;}
        catch(LinkageError broken){return false;}
        try {
            if(bukkit.getMethod("getServer").invoke(null)==null)return null;
            Class<?> managerType=Class.forName("org.bukkit.plugin.PluginManager"),pluginType=Class.forName("org.bukkit.plugin.Plugin"),playerType=Class.forName("org.bukkit.entity.Player");
            Object manager=bukkit.getMethod("getPluginManager").invoke(null);
            Object plugin=managerType.getMethod("getPlugin",String.class).invoke(manager,"AuthMe");
            if(plugin==null)return null;
            if(!pluginType.isInstance(plugin)||!Boolean.TRUE.equals(pluginType.getMethod("isEnabled").invoke(plugin)))return false;
            Object player=bukkit.getMethod("getPlayer",UUID.class).invoke(null,uuid);
            if(!playerType.isInstance(player)||!Boolean.TRUE.equals(playerType.getMethod("isOnline").invoke(player))||!uuid.equals(playerType.getMethod("getUniqueId").invoke(player))||!name.equals(playerType.getMethod("getName").invoke(player)))return false;
            Class<?> apiType=Class.forName("fr.xephi.authme.api.v3.AuthMeApi",true,plugin.getClass().getClassLoader());
            Object api=apiType.getMethod("getInstance").invoke(null);
            return api!=null&&Boolean.TRUE.equals(apiType.getMethod("isAuthenticated",playerType).invoke(api,player));
        }catch(ReflectiveOperationException|LinkageError|RuntimeException failure){return false;}
    }
}
