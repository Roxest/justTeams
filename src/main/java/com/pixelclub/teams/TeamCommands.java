package com.pixelclub.teams;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import java.lang.reflect.Field;
import java.util.*;

public final class TeamCommands {
    private TeamCommands(){}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,Settings settings) {
        for(String root:List.of("team","t"))registerRoot(dispatcher,root,false);
        for(String root:List.of("teammsg","tm","tmsg"))registerRoot(dispatcher,root,true);
        Map<String,List<String>> aliases=Map.of("guild",List.of("guild","g"),"clan",List.of("clan","c"),"party",List.of("party"),"guildmsg",List.of("guildmsg","gmsg"),"clanmsg",List.of("clanmsg","cmsg"),"partymsg",List.of("partymsg"));
        aliases.forEach((key,names)->{if(settings.bool("command-aliases."+key+".enabled",true))for(String name:names)registerRoot(dispatcher,name,key.endsWith("msg"));});
    }
    private static void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher,String name,boolean message) {
        remove(dispatcher.getRoot(),name);
        dispatcher.register(Commands.literal(name).executes(context->execute(context.getSource(),"",message)).then(Commands.argument("arguments",StringArgumentType.greedyString()).executes(context->execute(context.getSource(),StringArgumentType.getString(context,"arguments"),message))));
    }
    @SuppressWarnings("unchecked") private static void remove(CommandNode<?> root,String name) {
        try{for(String field:List.of("children","literals","arguments")){Field f=CommandNode.class.getDeclaredField(field);f.setAccessible(true);((Map<String,?>)f.get(root)).remove(name);}}
        catch(ReflectiveOperationException e){throw new IllegalStateException("cannot replace donor command aliases");}
    }
    private static int execute(CommandSourceStack source,String arguments,boolean message) {
        TeamsCore core=TeamsMod.core();String[] args=arguments.isBlank()?new String[0]:arguments.strip().split("\\s+");
        try {
            if(args.length>0&&(args[0].equalsIgnoreCase("admin")||args[0].equalsIgnoreCase("reload"))) {
                if(!Access.admin(source))throw new TeamsCore.Refusal("DENIED");
                if(core==null){source.sendSuccess(()->net.minecraft.network.chat.Component.literal("ready=false reason="+TeamsMod.problem()),false);return 1;}
                if(args[0].equalsIgnoreCase("reload")){core.reload();source.sendSuccess(()->net.minecraft.network.chat.Component.literal("justTeams reloaded"),false);return 1;}
                String action=args.length>1?args[1].toLowerCase(Locale.ROOT):"status";
                if(action.equals("status")){String status=core.status();source.sendSuccess(()->net.minecraft.network.chat.Component.literal(status),false);return 1;}
                if(action.equals("pending")){for(var pending:core.store.pendingOperations()){String row=pending.opid()+" team="+pending.teamId()+" kind="+pending.kind()+" phase="+pending.phase();source.sendSuccess(()->net.minecraft.network.chat.Component.literal(row),false);}return 1;}
                throw new TeamsCore.Refusal("UNKNOWN_ADMIN_ACTION");
            }
            if(core==null)throw new TeamsCore.Refusal("NOT_READY");ServerPlayer player=source.getPlayerOrException();
            if(message){core.message(player,arguments);return 1;}
            if(args.length==0){core.check(player,"gui");core.menus.team(player);return 1;}
            String action=args[0].toLowerCase(Locale.ROOT);
            switch(action) {
                case "gui","menu" -> {core.check(player,"gui");core.menus.team(player);}
                case "create" -> {require(args,3);core.create(player,args[1],args[2]);}
                case "invite" -> {require(args,2);core.invite(player,args[1]);}
                case "accept" -> {require(args,2);core.accept(player,args[1]);}
                case "deny" -> {require(args,2);core.check(player,"deny");var team=core.store.teamNamed(args[1]).orElseThrow(()->new TeamsCore.Refusal("UNKNOWN_TEAM"));core.ensureChanged(core.store.denyInvite(player.getUUID(),team.id()));core.success(player,"&aINVITE_DENIED");}
                case "join" -> {require(args,2);core.join(player,args[1]);}
                case "unjoin" -> {require(args,2);core.check(player,"unjoin");var team=core.store.teamNamed(args[1]).orElseThrow(()->new TeamsCore.Refusal("UNKNOWN_TEAM"));core.ensureChanged(core.store.deleteRows("join_requests",Map.of("team_id",team.id(),"player_uuid",player.getUUID()))>0);core.success(player,"&aREQUEST_REMOVED");}
                case "leave" -> core.menus.confirm(player,"leave",()->core.leave(player));
                case "disband" -> core.menus.confirm(player,"disband",()->core.disband(player));
                case "kick" -> {require(args,2);core.kick(player,core.resolvePlayer(args[1]));}
                case "promote","demote" -> {require(args,2);core.role(player,core.resolvePlayer(args[1]),action.equals("promote"));}
                case "transfer" -> {require(args,2);UUID who=core.resolvePlayer(args[1]);core.menus.confirm(player,"transfer",()->core.transfer(player,who));}
                case "settings" -> {core.check(player,"settings");core.menus.settings(player);}
                case "tag","settag" -> {require(args,2);core.setting(player,"tag",args[1]);}
                case "description","setdesc" -> {require(args,2);core.setting(player,"description",tail(args,1));}
                case "rename" -> {require(args,2);core.setting(player,"name",args[1]);}
                case "color","setcolor" -> {require(args,2);if(!Access.node(player,"justteams.setcolor",true))throw new TeamsCore.Refusal("DENIED");if(!args[1].matches("#[0-9a-fA-F]{6}|[a-z_]+"))throw new TeamsCore.Refusal("INVALID_COLOR");core.setting(player,"color",args[1]);}
                case "public" -> {var team=core.requireTeam(player);core.setting(player,"is_public",!team.isPublic());}
                case "glow" -> {var team=core.requireTeam(player);core.setting(player,"glow_enabled",!team.flag("glow_enabled"));}
                case "pvp" -> {var team=core.requireTeam(player);core.setting(player,"pvp_enabled",!team.pvp());}
                case "bank" -> {if(args.length==1){core.check(player,"bank");core.menus.bank(player);}else{require(args,3);if(!Set.of("deposit","withdraw").contains(args[1].toLowerCase(Locale.ROOT)))throw new TeamsCore.Refusal("BANK_USAGE");core.bank(player,args[1].equalsIgnoreCase("deposit"),Double.parseDouble(args[2]),UUID.randomUUID().toString());}}
                case "home" -> core.teleport(player,"home",null,null);
                case "sethome","delhome" -> core.setHome(player,action.equals("delhome"));
                case "setwarp" -> {require(args,2);core.setWarp(player,args[1],args.length>2?args[2]:null);}
                case "delwarp" -> {require(args,2);core.deleteWarp(player,args[1]);}
                case "warp" -> {require(args,2);core.teleport(player,"warp",args[1],args.length>2?args[2]:null);}
                case "warps" -> {core.check(player,"warps");core.menus.warps(player);}
                case "enderchest","ec" -> {core.check(player,"enderchest");core.chests.open(player);}
                case "claim" -> core.chests.claim(player);
                case "top" -> {core.check(player,"top");core.menus.top(player,args.length>1?args[1]:null);}
                case "chat" -> {if(args.length>1)core.message(player,tail(args,1));else core.toggleChat(player);}
                case "msg" -> core.message(player,tail(args,1));
                case "chatspy","spy" -> core.toggleSpy(player);
                case "upgrades","upgrade" -> {core.check(player,"upgrade");core.menus.upgrades(player);}
                case "requests" -> {core.check(player,"requests");core.elevated(player);core.menus.requests(player);}
                case "invites" -> {core.check(player,"invites");core.menus.invites(player);}
                case "ally","allies" -> {core.check(player,"ally");core.menus.allies(player);}
                case "blacklist" -> {core.check(player,"blacklist");core.elevated(player);core.menus.blacklist(player);}
                case "quests","quest" -> {core.check(player,"quests");core.menus.quests(player);}
                case "info" -> {var team=args.length>1?core.store.teamNamed(args[1]).orElseThrow(()->new TeamsCore.Refusal("UNKNOWN_TEAM")):core.requireTeam(player);core.success(player,team.name()+" ["+team.tag()+"] members="+core.store.members(team.id()).size()+" bank="+Texts.money(team.balance())+" tier="+team.tier());}
                case "help" -> core.success(player,"/team create|invite|accept|join|home|warp|bank|ec|chat|settings|top");
                default -> throw new TeamsCore.Refusal("UNKNOWN_TEAM_ACTION");
            }
            return 1;
        }catch(Exception error){if(core!=null&&source.getPlayer()!=null)core.refuse(source.getPlayer(),error);else source.sendFailure(net.minecraft.network.chat.Component.literal(error instanceof TeamsCore.Refusal?error.getMessage():"justTeams refused: "+error.getClass().getSimpleName()));return 0;}
    }
    private static void require(String[] args,int count){if(args.length<count)throw new TeamsCore.Refusal("MISSING_ARGUMENT");}
    private static String tail(String[] args,int start){return start>=args.length?"":String.join(" ",Arrays.copyOfRange(args,start,args.length));}
}
