package com.pixelclub.teams;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import com.pixelclub.teams.store.Member;
import java.util.*;

public final class Access {
    private Access(){}
    public static boolean command(ServerPlayer player,String action){String node=switch(action){case "tag","settag"->"settag";case "description","setdesc"->"setdescription";case "ec"->"enderchest";case "msg","teammsg"->"teammsg";case "create","invite","accept","deny","join","leave","disband","kick","promote","demote","transfer","bank","home","sethome","warp","setwarp","delwarp","warps","enderchest","top","chat","info","help","pvp","public","quests"->action;default->null;};return node==null||Permissions.check(player.createCommandSourceStack(),"justteams.command."+node,true);}
    public static boolean admin(CommandSourceStack source){return Permissions.check(source,"justteams.admin",2);}
    public static boolean node(ServerPlayer player,String node,boolean fallback){return Permissions.check(player.createCommandSourceStack(),node,fallback);}
    public static boolean bypass(ServerPlayer player,String action){return Permissions.check(player.createCommandSourceStack(),"justteams.bypass."+action,2);}
    public static boolean elevated(Member member){return member.owner()||"CO_OWNER".equals(member.role());}
    public static boolean flag(Member member,ServerPlayer player,String flag,String bypass){return member.flag(flag)||bypass(player,bypass);}
    public static String meta(ServerPlayer player,boolean prefix){
        try{Object api=Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);Object users=Class.forName("net.luckperms.api.LuckPerms").getMethod("getUserManager").invoke(api);Object user=Class.forName("net.luckperms.api.model.user.UserManager").getMethod("getUser",UUID.class).invoke(users,player.getUUID());if(user==null)return "";Object cached=Class.forName("net.luckperms.api.model.PermissionHolder").getMethod("getCachedData").invoke(user);Object data=Class.forName("net.luckperms.api.cacheddata.CachedDataManager").getMethod("getMetaData").invoke(cached);Object value=Class.forName("net.luckperms.api.cacheddata.CachedMetaData").getMethod(prefix?"getPrefix":"getSuffix").invoke(data);return Objects.toString(value,"");}catch(ReflectiveOperationException|RuntimeException e){return "";}
    }
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
    public static double permissionMoneyMaximum(ServerPlayer player,String prefix,double cap){
        double highest=0;try{Object api=Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);Object users=Class.forName("net.luckperms.api.LuckPerms").getMethod("getUserManager").invoke(api);Object user=Class.forName("net.luckperms.api.model.user.UserManager").getMethod("getUser",UUID.class).invoke(users,player.getUUID());if(user==null)return 0;Object cache=Class.forName("net.luckperms.api.model.PermissionHolder").getMethod("getCachedData").invoke(user);Object permissions=Class.forName("net.luckperms.api.cacheddata.CachedDataManager").getMethod("getPermissionData").invoke(cache);Object values=Class.forName("net.luckperms.api.cacheddata.CachedPermissionData").getMethod("getPermissionMap").invoke(permissions);if(values instanceof Map<?,?> map)for(var entry:map.entrySet()){String key=entry.getKey().toString();if(!key.startsWith(prefix)||!Boolean.TRUE.equals(entry.getValue()))continue;try{double amount=Double.parseDouble(key.substring(prefix.length()));if(Double.isFinite(amount)&&amount>highest&&node(player,key,false))highest=amount;}catch(NumberFormatException ignored){}}}catch(ReflectiveOperationException|RuntimeException ignored){}return cap>0?Math.min(cap,highest):highest;
    }
}
