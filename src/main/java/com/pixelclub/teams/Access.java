package com.pixelclub.teams;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import com.pixelclub.teams.store.Member;
import java.util.*;

public final class Access {
    private Access(){}
    public static boolean command(ServerPlayer player,String action){return Permissions.check(player.createCommandSourceStack(),"justteams.command."+action,true);}
    public static boolean admin(CommandSourceStack source){return Permissions.check(source,"justteams.admin",2);}
    public static boolean node(ServerPlayer player,String node,boolean fallback){return Permissions.check(player.createCommandSourceStack(),node,fallback);}
    public static boolean bypass(ServerPlayer player,String action){return Permissions.check(player.createCommandSourceStack(),"justteams.bypass."+action,2);}
    public static boolean elevated(Member member){return member.owner()||"CO_OWNER".equals(member.role());}
    public static boolean flag(Member member,ServerPlayer player,String flag,String bypass){return member.flag(flag)||bypass(player,bypass);}
    public static int permissionMaximum(ServerPlayer player,String prefix,int cap) {
        int highest=0;
        try {
            Object api=Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
            Object users=Class.forName("net.luckperms.api.LuckPerms").getMethod("getUserManager").invoke(api);
            Object user=Class.forName("net.luckperms.api.model.user.UserManager").getMethod("getUser",UUID.class).invoke(users,player.getUUID());
            if(user==null)return 0;
            Object cache=Class.forName("net.luckperms.api.model.PermissionHolder").getMethod("getCachedData").invoke(user);
            Object permissions=Class.forName("net.luckperms.api.cacheddata.CachedDataManager").getMethod("getPermissionData").invoke(cache);
            Object values=Class.forName("net.luckperms.api.cacheddata.CachedPermissionData").getMethod("getPermissionMap").invoke(permissions);
            if(values instanceof Map<?,?> map)for(var entry:map.entrySet()){
                String key=entry.getKey().toString();if(!key.startsWith(prefix)||!Boolean.TRUE.equals(entry.getValue()))continue;
                String suffix=key.substring(prefix.length());if(!suffix.matches("[0-9]+"))continue;
                int amount=Integer.parseInt(suffix);if(amount>highest&&node(player,key,false))highest=Math.min(cap,amount);
            }
        }catch(ReflectiveOperationException|RuntimeException ignored){}
        return highest;
    }
}
