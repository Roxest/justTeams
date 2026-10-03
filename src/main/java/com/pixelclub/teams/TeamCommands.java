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
    private static final Map<CommandDispatcher<?>,Map<String,CommandNode<?>>> owned=new WeakHashMap<>();
    /** Mojang's scoreboard /team node, kept so console, functions and command blocks still reach it. */
    private static volatile CommandNode<CommandSourceStack> vanillaTeam;
    private static final Set<String> VANILLA_TEAM=Set.of("add","remove","empty","join","leave","list","modify");
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,Settings settings) {
        Map<String,CommandNode<?>> previous=owned.remove(dispatcher);if(previous!=null)previous.forEach((name,node)->{if(dispatcher.getRoot().getChild(name)==node)remove(dispatcher.getRoot(),name);});owned.put(dispatcher,new HashMap<>());
        for(String root:List.of("team","t"))registerRoot(dispatcher,root,false);
        for(String root:List.of("teammsg","tm","tmsg"))registerRoot(dispatcher,root,true);
        String primary=settings.command("primary-command","team"),message=settings.command("primary-message-command","teammsg");if(!primary.matches("[a-zA-Z0-9_-]+")||!message.matches("[a-zA-Z0-9_-]+")||primary.equalsIgnoreCase(message))throw new IllegalArgumentException("invalid primary team command names");if(!primary.equals("team"))registerRoot(dispatcher,primary,false);if(!message.equals("teammsg"))registerRoot(dispatcher,message,true);
        Map<String,List<String>> aliases=Map.of("guild",List.of("guild","g"),"clan",List.of("clan","c"),"party",List.of("party"),"guildmsg",List.of("guildmsg","gmsg"),"clanmsg",List.of("clanmsg","cmsg"),"partymsg",List.of("partymsg"));
        aliases.forEach((key,names)->{if(settings.commandAlias(key))for(String name:names)registerRoot(dispatcher,name,key.endsWith("msg"));});
    }
    private static void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher,String name,boolean message) {
        boolean team=name.equals("team");
        if(team){CommandNode<CommandSourceStack> existing=dispatcher.getRoot().getChild("team");if(existing!=null)vanillaTeam=existing;}
        remove(dispatcher.getRoot(),name);
        CommandNode<?> node=dispatcher.register(Commands.literal(name).executes(context->execute(context.getSource(),"",message,team)).then(Commands.argument("arguments",StringArgumentType.greedyString()).executes(context->execute(context.getSource(),StringArgumentType.getString(context,"arguments"),message,team))));owned.get(dispatcher).put(name,node);
    }
    /** Non-player sources keep vanilla scoreboard teams (`team add|join|modify ...`); players always get justTeams. */
    private static int vanilla(CommandSourceStack source,String arguments){
        CommandDispatcher<CommandSourceStack> detached=new CommandDispatcher<>();detached.getRoot().addChild(vanillaTeam);
        try{return detached.execute("team "+arguments.strip(),source);}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){source.sendFailure(net.minecraft.network.chat.Component.literal(e.getMessage()));return 0;}
    }
    /**
     * Arclight copies vanilla /team into the Bukkit command map before this mod registers, so the bare label keeps
     * vanilla's op-only permission. Release only that exact wrapper (node identity); any plugin's claim is left alone.
     */
    public static String releaseBukkitTeamLabel(Object commands,CommandDispatcher<CommandSourceStack> dispatcher){
        try{
            Object map=commandMap();Field field=null;
            for(Class<?> type=map.getClass();type!=null&&field==null;type=type.getSuperclass())try{field=type.getDeclaredField("knownCommands");}catch(NoSuchFieldException ignored){}
            if(field==null)return "team=UNVERIFIED(no knownCommands)";
            field.setAccessible(true);@SuppressWarnings("unchecked")Map<String,Object> known=(Map<String,Object>)field.get(map);
            Object entry=known.get("team");if(entry==null)return "team=absent";
            if(!entry.getClass().getSimpleName().equals("VanillaCommandWrapper"))return "team=kept("+entry.getClass().getName()+")";
            Field node=null;
            for(Class<?> type=entry.getClass();type!=null&&node==null;type=type.getSuperclass())for(Field f:type.getDeclaredFields())if(CommandNode.class.isAssignableFrom(f.getType())){node=f;break;}
            if(node==null)return "team=UNVERIFIED(no node field)";
            node.setAccessible(true);
            if(vanillaTeam==null||node.get(entry)!=vanillaTeam)return "team=kept(foreign VanillaCommandWrapper)";
            CommandNode<CommandSourceStack> ours=dispatcher.getRoot().getChild("team");
            if(ours==null||ours==vanillaTeam)return "team=UNVERIFIED(own node missing)";
            // Same wrapper type Arclight builds for every Brigadier root (as /t has), but around this mod's node.
            for(var constructor:entry.getClass().getDeclaredConstructors()){
                Class<?>[] types=constructor.getParameterTypes();
                if(types.length==2&&types[0].isInstance(commands)&&types[1].isAssignableFrom(ours.getClass())){
                    constructor.setAccessible(true);Object wrapper=constructor.newInstance(commands,ours);
                    if(!known.replace("team",entry,wrapper))return "team=UNVERIFIED(map changed)";
                    return "team=released(vanilla wrapper)->own wrapper";
                }
            }
            return "team=UNVERIFIED(no wrapper constructor)";
        }catch(ReflectiveOperationException|RuntimeException e){return "team=UNVERIFIED("+e.getClass().getSimpleName()+")";}
    }
    /** Owner of each bare label in the Bukkit command map, for the boot log. */
    public static String bukkitOwners(){
        try{
            Object map=commandMap();StringBuilder out=new StringBuilder();
            for(String name:List.of("team","t","teammsg","tm","tmsg","guild","g","clan","c","party","guildmsg","gmsg","clanmsg","cmsg","partymsg")){
                Object command=map.getClass().getMethod("getCommand",String.class).invoke(map,name);
                out.append(name).append('=').append(command==null?"brigadier":command.getClass().getSimpleName()).append(' ');
            }
            return out.toString().trim();
        }catch(ReflectiveOperationException|RuntimeException e){return "UNVERIFIED("+e.getClass().getSimpleName()+")";}
    }
    private static Object commandMap()throws ReflectiveOperationException{Object server=Class.forName("org.bukkit.Bukkit").getMethod("getServer").invoke(null);return server.getClass().getMethod("getCommandMap").invoke(server);}
    @SuppressWarnings("unchecked") private static void remove(CommandNode<?> root,String name) {
        try{for(String field:List.of("children","literals","arguments")){Field f=CommandNode.class.getDeclaredField(field);f.setAccessible(true);((Map<String,?>)f.get(root)).remove(name);}}
        catch(ReflectiveOperationException e){throw new IllegalStateException("cannot replace donor command aliases");}
    }
    private static int execute(CommandSourceStack source,String arguments,boolean message,boolean vanillaRoot) {
        TeamsCore core=TeamsMod.core();String[] args=arguments.isBlank()?new String[0]:arguments.strip().split("\\s+");
        if(vanillaRoot&&vanillaTeam!=null&&source.getPlayer()==null&&args.length>0&&VANILLA_TEAM.contains(args[0].toLowerCase(Locale.ROOT)))return vanilla(source,arguments);
        try {
            if(source.getPlayer()!=null&&!TeamsCore.authenticated(source.getPlayer()))throw new TeamsCore.Refusal("LOGIN_REQUIRED");
            if(args.length>0&&(args[0].equalsIgnoreCase("admin")||args[0].equalsIgnoreCase("reload"))) {
                if(!Access.admin(source))throw new TeamsCore.Refusal("DENIED");
                if(core==null){source.sendSuccess(()->net.minecraft.network.chat.Component.literal("ready=false reason="+TeamsMod.problem()),false);return 1;}
                if(args[0].equalsIgnoreCase("reload")){core.reload();source.sendSuccess(()->net.minecraft.network.chat.Component.literal("justTeams reloaded"),false);return 1;}
                String action=args.length>1?args[1].toLowerCase(Locale.ROOT):source.getPlayer()==null?"status":"gui";
                if(action.equals("status")){String status=core.status();source.sendSuccess(()->net.minecraft.network.chat.Component.literal(status),false);return 1;}
                if(action.equals("pending")){for(var pending:core.store.pendingOperations()){String row=pending.opid()+" team="+pending.teamId()+" kind="+pending.kind()+" phase="+pending.phase();source.sendSuccess(()->net.minecraft.network.chat.Component.literal(row),false);}return 1;}
                if(action.equals("chestrecover")){require(args,3);String result=core.chests.recover(args[2],args.length>3&&args[3].equalsIgnoreCase("apply"));TeamsMod.LOG.warn("[pxc-teams] chest recovery by {}: {}",source.getTextName(),result);source.sendSuccess(()->net.minecraft.network.chat.Component.literal(result),true);return 1;}
                ServerPlayer admin=source.getPlayerOrException();core.requireAdmin(admin);
                if(action.equals("gui")){core.menus.admin(admin,0);return 1;}
                require(args,3);var target=core.store.teamNamed(args[2]).orElseThrow(()->new TeamsCore.Refusal("UNKNOWN_TEAM"));
                switch(action){case "disband"->core.menus.confirm(admin,"Admin disband",()->core.adminDisband(admin,target.id()));case "home"->core.adminHome(admin,target.id());case "enderchest","ec"->core.chests.openAdministrative(admin,target);case "points"->{require(args,5);core.adminPoints(admin,target.id(),args[3].toLowerCase(Locale.ROOT),Long.parseLong(args[4]));}default->throw new TeamsCore.Refusal("UNKNOWN_ADMIN_ACTION");}return 1;
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
                case "rename" -> {require(args,2);core.rename(player,args[1]);}
                case "color","setcolor" -> {require(args,2);core.setColor(player,args[1],args.length>2?args[2]:null);}
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
                case "ally","allies" -> {if(args.length>=2&&args[1].equalsIgnoreCase("toggle"))core.ally(player,"toggle",null);else if(args.length>=3)core.ally(player,args[1].toLowerCase(Locale.ROOT),args[2]);else{core.check(player,"ally");core.menus.allies(player);}}
                case "blacklist" -> {if(args.length>=2)core.blacklist(player,args[1],tail(args,2),false);else{core.check(player,"blacklist");core.elevated(player);core.menus.blacklist(player);}}
                case "unblacklist" -> {require(args,2);core.blacklist(player,args[1],null,true);}
                case "setjoinfee" -> {require(args,2);core.setJoinFee(player,Double.parseDouble(args[1]));}
                case "points" -> {core.check(player,"points");core.success(player,"Points: "+core.requireTeam(player).field("points"));}
                case "platform" -> {core.check(player,"platform");core.success(player,core.platform(player.getUUID()));}
                case "serveralias" -> {require(args,2);core.serverAlias(player,args[1].toLowerCase(Locale.ROOT),args.length>2?args[2]:null,args.length>3?tail(args,3):null);}
                case "quests","quest" -> {core.check(player,"quests");core.menus.quests(player);}
                case "info" -> {core.check(player,"info");var team=args.length>1?core.store.teamNamed(args[1]).orElseThrow(()->new TeamsCore.Refusal("UNKNOWN_TEAM")):core.requireTeam(player);core.success(player,team.name()+" ["+team.tag()+"] members="+core.store.members(team.id()).size()+" bank="+Texts.money(team.balance())+" tier="+team.tier());}
                case "help" -> {core.check(player,"help");core.success(player,"/team create|invite|accept|join|home|warp|bank|ec|chat|settings|top");}
                default -> throw new TeamsCore.Refusal("UNKNOWN_TEAM_ACTION");
            }
            return 1;
        }catch(Exception error){if(core!=null&&source.getPlayer()!=null)core.refuse(source.getPlayer(),error);else source.sendFailure(net.minecraft.network.chat.Component.literal(error instanceof TeamsCore.Refusal?error.getMessage():"justTeams refused: "+error.getClass().getSimpleName()));return 0;}
    }
    private static void require(String[] args,int count){if(args.length<count)throw new TeamsCore.Refusal("MISSING_ARGUMENT");}
    private static String tail(String[] args,int start){return start>=args.length?"":String.join(" ",Arrays.copyOfRange(args,start,args.length));}
}
