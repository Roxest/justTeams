package com.pixelclub.teams;

import com.pixelclub.teams.store.*;
import com.pixelclub.teams.store.TeamStore.Operation;
import com.pixelclub.teams.money.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.nio.file.*;
import java.sql.*;

/** One server-thread authority. Every write re-reads membership/rights and propagates storage failures. */
public final class TeamsCore implements AutoCloseable {
    public final MinecraftServer server;
    public final Settings settings;
    public final TeamStore store;
    public final BankService bank;
    public final TeamMenus menus;
    public final TeamChests chests;
    private final TeamDataSource data;
    private final Map<UUID,Team> teamCache=new HashMap<>();
    private final Set<UUID> chatMode=new HashSet<>(),spies=new HashSet<>();
    private final Map<UUID,Prompt> prompts=new HashMap<>();
    private final Map<UUID,Warmup> warmups=new HashMap<>();
    private final Map<String,Long> cooldowns=new HashMap<>();
    private long ticks;
    private boolean ready;
    private record Prompt(long expires,Consumer<String> callback){}
    private record Warmup(long at,ServerLevel origin,Vec3 position,ServerLevel target,double x,double y,double z,float yaw,float pitch,String kind){}
    public static final class Refusal extends RuntimeException {public Refusal(String code){super(code);}}

    public TeamsCore(MinecraftServer server,Settings settings)throws Exception {
        this.server=server;this.settings=settings;
        data=new TeamDataSource(settings);
        store=new TeamStore(data,settings.first("donut_","storage.table_prefix","storage.mysql.table_prefix","storage.prefix"));
        store.validateSchema();
        // Additive tables only. A narrower replica database is never treated as a donor import.
        store.provisionAdditiveTables();
        bank=new BankService(store,new PackEconomy(FabricLoader.getInstance().getGameDir()),settings.folder().resolve("bank-intents.jsonl"),settings.number("team_bank.max_balance",-1));
        menus=new TeamMenus(this);chests=new TeamChests(this);
        for(Operation pending:store.pendingOperations())store.freezeOperation(pending.opid(),"RESTART_PENDING_"+pending.phase());
        chests.validateConvertedDonor();
        ready=true;
        for(ServerPlayer player:server.getPlayerList().getPlayers())joined(player);
        registerPlaceholders();
    }
    public boolean ready(){return ready;}
    public synchronized void check(ServerPlayer player,String action)throws Exception {
        if(!ready)throw new Refusal("NOT_READY");
        if(!authenticated(player))throw new Refusal("LOGIN_REQUIRED");
        if(!Access.command(player,action))throw new Refusal("DENIED");
        String feature=switch(action){case "bank"->"team_bank";case "home","sethome","delhome"->"team_home";case "warp","warps","setwarp","delwarp"->"team_warps";case "enderchest","ec"->"team_enderchest";case "chat","msg"->"team_chat";default->"team_"+action;};
        if(!settings.enabled(feature))throw new Refusal("FEATURE_DISABLED");
        if(settings.bool("world_restrictions.enabled",false)) {
            Object disabled=settings.value("world_restrictions.disabled_worlds."+feature);
            if(disabled instanceof Collection<?> list&&list.stream().anyMatch(v->worldName(player.serverLevel()).equalsIgnoreCase(v.toString())))throw new Refusal("WORLD_DENIED");
        }
    }
    public static boolean authenticated(ServerPlayer player) {
        // Server-owned Carpet actors are test/automation identities, never client-supplied subclasses.
        if(player.getClass().getName().equals("carpet.patches.EntityPlayerMPFake")&&testActorsAllowed())return true;
        if(!FabricLoader.getInstance().isModLoaded("pxc-auth"))return player.getServer()!=null&&player.getServer().usesAuthentication();
        try {
            Class<?> state=Class.forName("com.pixelclub.auth.api.AuthState");
            for(String name:List.of("isAuthenticated","isLoggedIn")) {
                try{return Boolean.TRUE.equals(state.getMethod(name,UUID.class).invoke(null,player.getUUID()));}catch(NoSuchMethodException ignored){}
                try{return Boolean.TRUE.equals(state.getMethod(name,ServerPlayer.class).invoke(null,player));}catch(NoSuchMethodException ignored){}
            }
        }catch(ReflectiveOperationException ignored){}
        return false;
    }
    private static boolean testActorsAllowed(){
        if(!Boolean.getBoolean("pxcteams.testActors"))return false;
        try{Properties properties=new Properties();try(java.io.Reader reader=Files.newBufferedReader(FabricLoader.getInstance().getGameDir().resolve("server.properties"))){properties.load(reader);}String bind=properties.getProperty("server-ip","");return bind.equals("127.0.0.1")||bind.equals("::1");}catch(Exception e){return false;}
    }
    public Team requireTeam(ServerPlayer player)throws Exception{return store.teamOf(player.getUUID()).orElseThrow(()->new Refusal("NO_TEAM"));}
    public Member requireMember(ServerPlayer player)throws Exception{return store.member(player.getUUID()).orElseThrow(()->new Refusal("NO_TEAM"));}
    public void elevated(ServerPlayer player)throws Exception{if(!Access.elevated(requireMember(player))&&!Access.bypass(player,"permissions"))throw new Refusal("ELEVATED_REQUIRED");}
    public void owner(ServerPlayer player)throws Exception{if(!requireTeam(player).owner().equals(player.getUUID())&&!Access.bypass(player,"permissions"))throw new Refusal("OWNER_REQUIRED");}
    public void flag(ServerPlayer player,String flag,String bypass)throws Exception{if(!Access.flag(requireMember(player),player,flag,bypass))throw new Refusal("MEMBER_FLAG_DENIED");}
    public void success(ServerPlayer player,String text){player.sendSystemMessage(Texts.of(settings.message("prefix","&7[Team] ")+text));}
    public void refuse(ServerPlayer player,Throwable error){String code=error instanceof Refusal?error.getMessage():"STORAGE_"+error.getClass().getSimpleName();success(player,"&c"+settings.message(code.toLowerCase(Locale.ROOT),code));}
    public void safely(ServerPlayer player,Throwing task){try{task.run();}catch(Exception e){refuse(player,e);}}
    @FunctionalInterface public interface Throwing{void run()throws Exception;}
    public synchronized void joined(ServerPlayer player)throws Exception {
        store.updateRows("player_cache","player_uuid",player.getUUID(),Map.of("player_name",player.getGameProfile().getName(),"last_seen",new Timestamp(System.currentTimeMillis())));
        if(store.rows("player_cache","player_uuid",player.getUUID()).isEmpty())store.insertRow("player_cache",Map.of("player_uuid",player.getUUID(),"player_name",player.getGameProfile().getName()));
        refresh(player.getUUID());
    }
    public synchronized void departed(ServerPlayer player){prompts.remove(player.getUUID());warmups.remove(player.getUUID());chatMode.remove(player.getUUID());spies.remove(player.getUUID());chests.close(player);teamCache.remove(player.getUUID());}
    public void refresh(UUID player)throws Exception{Optional<Team> team=store.teamOf(player);if(team.isPresent())teamCache.put(player,team.get());else teamCache.remove(player);}
    public void refreshTeam(int team)throws Exception{for(Member member:store.members(team))refresh(member.uuid());}
    public void invalidate(UUID player){prompts.remove(player);warmups.remove(player);chatMode.remove(player);chests.revoke(player);ServerPlayer online=server.getPlayerList().getPlayer(player);if(online!=null)online.closeContainer();teamCache.remove(player);}
    public UUID resolvePlayer(String name)throws Exception {
        try{return UUID.fromString(name);}catch(IllegalArgumentException ignored){}
        ServerPlayer online=server.getPlayerList().getPlayerByName(name);if(online!=null)return online.getUUID();
        List<Map<String,Object>> found=store.rows("player_cache",null,null).stream().filter(row->name.equalsIgnoreCase(String.valueOf(row.get("player_name")))).toList();
        if(found.size()!=1)throw new Refusal(found.isEmpty()?"UNKNOWN_PLAYER":"AMBIGUOUS_PLAYER");return UUID.fromString(found.getFirst().get("player_uuid").toString());
    }
    public String playerName(UUID player)throws Exception{ServerPlayer online=server.getPlayerList().getPlayer(player);if(online!=null)return online.getGameProfile().getName();List<Map<String,Object>> rows=store.rows("player_cache","player_uuid",player);return rows.isEmpty()?player.toString():String.valueOf(rows.getFirst().get("player_name"));}
    public int capacity(Team team){int base=settings.integer("settings.max_team_size",10);int tier=settings.integer("team_upgrades.tiers."+team.tier()+".max_members",base);int value=Math.max(base,tier);ServerPlayer owner=server.getPlayerList().getPlayer(team.owner());if(owner!=null&&settings.bool("settings.permission_based_team_size",true))value=Math.max(value,Access.permissionMaximum(owner,"justteams.team.size.",1000));return Math.min(100,value);}
    public int maxWarps(Team team){return Math.min(100,Math.max(1,settings.integer("team_upgrades.tiers."+team.tier()+".max_warps",settings.integer("team_warps.max_warps",5))));}
    public String serverName(){return settings.first("survival","server-identifier","server_identifier");}
    public void ensureChanged(boolean changed){if(!changed)throw new Refusal("STATE_REFUSED");}
    public synchronized boolean paid(ServerPlayer player,String feature,boolean bankFirst,Callable<Boolean> action)throws Exception {
        if(settings.itemCostEnabled())throw new Refusal("ITEM_COST_CONFIGURATION_REQUIRES_MIGRATION");
        String op=UUID.randomUUID().toString();BankService.Result charge=bank.charge(op,player.getUUID(),settings.cost(feature),bankFirst);
        if(!charge.success()){success(player,"&c"+charge.code()+ (charge.pending()?" op="+charge.opid():""));return false;}
        boolean changed;
        try{changed=action.call();}catch(Exception e){bank.unknownCharge(op,"FEATURE_EXECUTION_UNKNOWN");throw e;}
        if(!bank.finishCharge(op,changed))throw new Refusal("FEATURE_RECOVERY_REQUIRED");return changed;
    }
    public synchronized void create(ServerPlayer player,String name,String tag)throws Exception {
        check(player,"create");if(store.teamOf(player.getUUID()).isPresent())throw new Refusal("ALREADY_IN_TEAM");
        if(!name.matches("[A-Za-z0-9_]+")||name.length()<settings.integer("settings.min_name_length",3)||name.length()>settings.integer("settings.max_name_length",16))throw new Refusal("INVALID_NAME");
        if(!tag.matches("[A-Za-z0-9_]+")||tag.length()>settings.integer("settings.max_tag_length",6))throw new Refusal("INVALID_TAG");
        if(paid(player,"team_creation",false,()->store.create(name,tag,player.getUUID(),player.getGameProfile().getName(),serverName(),settings.text("settings.creation.default_color","<white>"))!=null)){
            refresh(player.getUUID());menus.team(player);success(player,"&a"+settings.message("team_created","Created <team>").replace("<team>",name));
        }
    }
    public synchronized void invite(ServerPlayer player,String target)throws Exception {
        check(player,"invite");elevated(player);Team team=requireTeam(player);UUID who=resolvePlayer(target);
        if(store.members(team.id()).size()>=capacity(team))throw new Refusal("TEAM_FULL");
        ensureChanged(store.invite(team.id(),player.getUUID(),who,System.currentTimeMillis()+settings.integer("settings.invite_expiry_seconds",300)*1000L));
        ServerPlayer online=server.getPlayerList().getPlayer(who);if(online!=null)success(online,"&a/team accept "+team.name());success(player,"&aINVITED");
    }
    public synchronized void accept(ServerPlayer player,String name)throws Exception {
        check(player,"accept");Team team=store.teamNamed(name).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));
        ensureChanged(store.accept(player.getUUID(),team.id(),capacity(team)));refresh(player.getUUID());success(player,"&aJOINED "+team.name());menus.team(player);
    }
    public synchronized void join(ServerPlayer player,String name)throws Exception {
        check(player,"join");if(store.teamOf(player.getUUID()).isPresent())throw new Refusal("ALREADY_IN_TEAM");
        Team team=store.teamNamed(name).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));
        if(!team.isPublic()) {
            String key="request:"+player.getUUID();long now=System.currentTimeMillis();if(cooldowns.getOrDefault(key,0L)>now)throw new Refusal("REQUEST_COOLDOWN");
            if(!store.rows("join_requests","player_uuid",player.getUUID()).stream().filter(row->((Number)row.get("team_id")).intValue()==team.id()).toList().isEmpty())throw new Refusal("ALREADY_REQUESTED");
            if(!store.rows("team_blacklist","player_uuid",player.getUUID()).stream().filter(row->((Number)row.get("team_id")).intValue()==team.id()).toList().isEmpty())throw new Refusal("BLACKLISTED");
            ensureChanged(store.insertRow("join_requests",Map.of("team_id",team.id(),"player_uuid",player.getUUID())));cooldowns.put(key,now+60000);success(player,"&aREQUEST_SENT");return;
        }
        ensureChanged(store.joinPublic(player.getUUID(),team.id(),capacity(team)));refresh(player.getUUID());success(player,"&aJOINED "+team.name());menus.team(player);
    }
    public synchronized void leave(ServerPlayer player)throws Exception{check(player,"leave");int team=requireTeam(player).id();chests.close(player);ensureChanged(store.leave(player.getUUID()));invalidate(player.getUUID());refreshTeam(team);success(player,"&aLEFT_TEAM");menus.team(player);}
    public synchronized void kick(ServerPlayer player,UUID target)throws Exception {
        check(player,"kick");Team team=requireTeam(player);Member victim=store.member(target).orElseThrow(()->new Refusal("NO_MEMBER"));
        if(victim.teamId()!=team.id()||victim.owner())throw new Refusal("DENIED");
        flag(player,"can_kick_members","permissions");if(victim.role().equals("CO_OWNER")&&!requireMember(player).owner())flag(player,"can_edit_co_owners","permissions");
        chests.revoke(target);ensureChanged(store.kick(team.id(),target));invalidate(target);refreshTeam(team.id());success(player,"&aKICKED");
    }
    public synchronized void role(ServerPlayer player,UUID target,boolean promote)throws Exception {
        check(player,promote?"promote":"demote");Team team=requireTeam(player);Member member=store.member(target).orElseThrow(()->new Refusal("NO_MEMBER"));
        if(member.teamId()!=team.id()||member.owner())throw new Refusal("DENIED");
        flag(player,promote?"can_promote_members":"can_demote_members","permissions");
        if(promote&&!requireMember(player).owner())flag(player,"can_promote_to_co_owner","permissions");
        String role=promote?"CO_OWNER":"MEMBER";
        Map<String,Boolean> flags=new HashMap<>();flags.put("can_withdraw",promote);flags.put("can_use_enderchest",true);flags.put("can_set_home",promote);flags.put("can_use_home",true);
        flags.put("can_edit_members",promote);flags.put("can_edit_co_owners",false);flags.put("can_kick_members",promote);flags.put("can_promote_members",false);flags.put("can_demote_members",false);
        ensureChanged(store.setMemberRoleAndFlags(team.id(),target,role,flags));chests.revoke(target);refreshTeam(team.id());success(player,"&aROLE_UPDATED");
    }
    public synchronized void editFlag(ServerPlayer player,UUID target,String key,boolean value)throws Exception{check(player,"settings");owner(player);Team team=requireTeam(player);Member victim=store.member(target).orElseThrow(()->new Refusal("NO_MEMBER"));if(victim.teamId()!=team.id()||victim.owner())throw new Refusal("DENIED");ensureChanged(store.setMemberFlags(team.id(),target,Map.of(key,value)));chests.revoke(target);success(player,"&aFLAG_UPDATED");}
    public synchronized void transfer(ServerPlayer player,UUID target)throws Exception{check(player,"transfer");owner(player);Team team=requireTeam(player);chests.revokeTeam(team.id());ensureChanged(store.transferOwnership(team.id(),team.owner(),target));refreshTeam(team.id());success(player,"&aOWNER_UPDATED");menus.team(player);}
    public synchronized void disband(ServerPlayer player)throws Exception{check(player,"disband");owner(player);Team team=requireTeam(player);List<Member> before=store.members(team.id());chests.revokeTeam(team.id());ensureChanged(store.deleteTeam(team.id(),team.owner()));for(Member member:before)invalidate(member.uuid());success(player,"&aDISBANDED");menus.team(player);}
    public synchronized void setting(ServerPlayer player,String field,Object value)throws Exception {
        check(player,"settings");elevated(player);Team team=requireTeam(player);
        if(field.equals("tag")&&(!value.toString().matches("[A-Za-z0-9_]+")||value.toString().length()>settings.integer("settings.max_tag_length",6)))throw new Refusal("INVALID_TAG");
        if(field.equals("description")&&value.toString().length()>settings.integer("settings.max_description_length",64))throw new Refusal("DESCRIPTION_TOO_LONG");
        ensureChanged(store.updateTeam(team.id(),team.owner(),Collections.singletonMap(field,value)));refreshTeam(team.id());success(player,"&aSETTING_UPDATED");
    }
    public synchronized void bank(ServerPlayer player,boolean deposit,double amount,String opid)throws Exception {
        check(player,"bank");Team team=requireTeam(player);if(!settings.bool("team_bank.enabled",true))throw new Refusal("BANK_DISABLED");
        BankService.Result result=bank.transfer(opid,player.getUUID(),deposit,amount,0,Access.bypass(player,"bank.withdraw"));
        success(player,(result.success()?"&a":"&c")+result.code()+" op="+result.opid());refreshTeam(team.id());if(result.success())menus.bank(player);
    }
    public synchronized void upgrade(ServerPlayer player)throws Exception {
        check(player,"upgrade");owner(player);Team team=requireTeam(player);if(!settings.bool("team_upgrades.enabled",true))throw new Refusal("UPGRADES_DISABLED");
        int next=team.tier()+1;Object configured=settings.value("team_upgrades.tiers."+next);if(configured==null)throw new Refusal("MAX_TIER");
        ensureChanged(store.upgrade(team.id(),team.tier(),next,settings.number("team_upgrades.tiers."+next+".cost",-1)));refreshTeam(team.id());success(player,"&aUPGRADED");menus.upgrades(player);
    }
    public synchronized void setHome(ServerPlayer player,boolean delete)throws Exception {
        check(player,delete?"delhome":"sethome");flag(player,"can_set_home","home");Team team=requireTeam(player);
        if(delete)ensureChanged(store.updateTeam(team.id(),team.owner(),nullable("home_location",null,"home_server",null)));
        else ensureChanged(paid(player,"sethome",true,()->store.updateTeam(team.id(),team.owner(),Map.of("home_location",serialize(player),"home_server",serverName()))));
        refreshTeam(team.id());success(player,"&aHOME_UPDATED");
    }
    public synchronized void setWarp(ServerPlayer player,String name,String password)throws Exception {
        check(player,"setwarp");elevated(player);Team team=requireTeam(player);
        ensureChanged(paid(player,"setwarp",true,()->store.setWarp(team.id(),name,serialize(player),serverName(),password,maxWarps(team))));success(player,"&aWARP_UPDATED");
    }
    public synchronized void deleteWarp(ServerPlayer player,String name)throws Exception {check(player,"delwarp");Team team=requireTeam(player);if(!Access.elevated(requireMember(player))&&!name.equalsIgnoreCase(player.getGameProfile().getName()))throw new Refusal("DENIED");ensureChanged(store.deleteWarp(team.id(),name));success(player,"&aWARP_DELETED");}
    public synchronized void teleport(ServerPlayer player,String kind,String name,String password)throws Exception {
        check(player,kind);Team team=requireTeam(player);String location,serverId;
        if(kind.equals("home")){flag(player,"can_use_home","home");location=team.location();serverId=(String)team.field("home_server");}
        else {Map<String,Object> warp=store.rows("team_warps","team_id",team.id()).stream().filter(row->name.equalsIgnoreCase(row.get("warp_name").toString())).findFirst().orElseThrow(()->new Refusal("NO_WARP"));location=(String)warp.get("location");serverId=(String)warp.get("server_name");String expected=(String)warp.get("password");if(expected!=null&&!expected.isEmpty()&&!expected.equals(password))throw new Refusal("WARP_PASSWORD_REQUIRED");}
        if(location==null||location.isEmpty())throw new Refusal("NO_HOME");if(serverId!=null&&!serverId.isEmpty()&&!serverId.equals(serverName()))throw new Refusal("REMOTE_SERVER_UNAVAILABLE");
        String[] fields=location.split(",",-1);if(fields.length!=6)throw new Refusal("INVALID_LOCATION");ServerLevel target=resolveWorld(fields[0]);if(target==null)throw new Refusal("WORLD_UNAVAILABLE");
        double x=Double.parseDouble(fields[1]),y=Double.parseDouble(fields[2]),z=Double.parseDouble(fields[3]);float yaw=Float.parseFloat(fields[4]),pitch=Float.parseFloat(fields[5]);
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(x)>29_999_984||Math.abs(z)>29_999_984)throw new Refusal("INVALID_LOCATION");
        boolean bypass=Access.bypass(player,kind+".cooldown");String key=kind+":"+player.getUUID();if(!bypass&&cooldowns.getOrDefault(key,0L)>System.currentTimeMillis())throw new Refusal("TELEPORT_COOLDOWN");
        if(!paid(player,kind,true,()->true))return;
        int delay=bypass?0:settings.integer(kind.equals("home")?"team_home.warmup_seconds":"team_warps.warmup_seconds",5)*20;
        warmups.put(player.getUUID(),new Warmup(ticks+delay,player.serverLevel(),player.position(),target,x,y,z,yaw,pitch,kind));success(player,"&eTELEPORT_WARMUP");
    }
    public String serialize(ServerPlayer player){return worldName(player.serverLevel())+","+player.getX()+","+player.getY()+","+player.getZ()+","+player.getYRot()+","+player.getXRot();}
    public String worldName(ServerLevel level) {
        String id=level.dimension().location().toString();
        try {
            Class<?> worlds=Class.forName("com.pixelclub.worlds.Worlds");Object config=worlds.getMethod("config").invoke(null);Object entry=config.getClass().getMethod("get",String.class).invoke(config,id);
            if(entry!=null){Object value=entry.getClass().getMethod("bukkitName").invoke(entry);if(value!=null&&!value.toString().isEmpty())return value.toString();}
            return (String)Class.forName("com.pixelclub.worlds.WorldNames").getMethod("defaultBukkitName",String.class).invoke(null,id);
        }catch(ReflectiveOperationException e){return switch(id){case "minecraft:overworld"->"world";case "minecraft:the_nether"->"world_nether";case "minecraft:the_end"->"world_the_end";default->id;};}
    }
    public ServerLevel resolveWorld(String name){for(ServerLevel level:server.getAllLevels())if(level.dimension().location().toString().equals(name)||worldName(level).equals(name))return level;return null;}
    public synchronized void prompt(ServerPlayer player,String label,Consumer<String> callback){player.closeContainer();prompts.put(player.getUUID(),new Prompt(System.currentTimeMillis()+60000,callback));success(player,"&e"+label+" &7(cancel)");}
    public synchronized boolean chat(ServerPlayer player,String text) {
        Prompt prompt=prompts.remove(player.getUUID());if(prompt!=null){if(prompt.expires()>System.currentTimeMillis()&&!text.equalsIgnoreCase("cancel")&&!text.equals("ยกเลิก")){if(authenticated(player))prompt.callback().accept(text);}return true;}
        String marker=settings.text("team_chat.character","#");boolean prefixed=settings.bool("team_chat.character_enabled",true)&&text.startsWith(marker)&&(!settings.bool("team_chat.require_space",false)||text.startsWith(marker+" "));
        if(!prefixed&&!chatMode.contains(player.getUUID()))return false;
        String body=prefixed?text.substring(marker.length()).stripLeading():text;safely(player,()->message(player,body));return true;
    }
    public synchronized boolean wantsChat(ServerPlayer player,String text){String marker=settings.text("team_chat.character","#");return prompts.containsKey(player.getUUID())||chatMode.contains(player.getUUID())||settings.bool("team_chat.character_enabled",true)&&text.startsWith(marker)&&(!settings.bool("team_chat.require_space",false)||text.startsWith(marker+" "));}
    public synchronized void message(ServerPlayer player,String message)throws Exception {
        check(player,"chat");Team team=requireTeam(player);if(message.isBlank()||message.length()>200)throw new Refusal("INVALID_MESSAGE");
        String lower=message.toLowerCase(Locale.ROOT);if(lower.contains("password")||lower.contains("/login")||lower.contains("/register"))throw new Refusal("PRIVATE_MESSAGE_REJECTED");
        String format=settings.message("team_chat_format","&8[&a<team_name>&8] &f<player>&7: <message>");
        String rendered=format.replace("<team_name>",team.name()).replace("<team_tag>",team.tag()).replace("<team_color>",Objects.toString(team.field("color"),"")).replace("<player>",player.getGameProfile().getName()).replace("<message>",message).replace("<prefix>","").replace("<player_prefix>","").replace("<suffix>","").replace("<player_suffix>","");
        Set<UUID> members=new HashSet<>();for(Member member:store.members(team.id()))members.add(member.uuid());
        Component component=Texts.of(rendered);for(ServerPlayer recipient:server.getPlayerList().getPlayers())if(members.contains(recipient.getUUID())||spies.contains(recipient.getUUID())&&Access.node(recipient,"justteams.chatspy",false))recipient.sendSystemMessage(component);
    }
    public synchronized void toggleChat(ServerPlayer player)throws Exception{check(player,"chat");requireTeam(player);if(!chatMode.remove(player.getUUID()))chatMode.add(player.getUUID());success(player,"&aCHAT="+chatMode.contains(player.getUUID()));}
    public synchronized void toggleSpy(ServerPlayer player)throws Exception{if(!Access.node(player,"justteams.chatspy",false))throw new Refusal("DENIED");if(!spies.remove(player.getUUID()))spies.add(player.getUUID());success(player,"&aCHATSPY="+spies.contains(player.getUUID()));}
    public synchronized boolean blockDamage(ServerPlayer attacker,ServerPlayer victim)throws Exception {
        Team a=store.teamOf(attacker.getUUID()).orElse(null),b=store.teamOf(victim.getUUID()).orElse(null);if(a==null||b==null)return false;
        boolean same=a.id()==b.id();boolean ally=store.rows("team_allies",null,null).stream().anyMatch(row->Math.min(a.id(),b.id())==((Number)row.get("team_id_1")).intValue()&&Math.max(a.id(),b.id())==((Number)row.get("team_id_2")).intValue());
        boolean deny=same&&!b.pvp()||ally;if(!deny&&settings.bool("team_pvp.disable_fly_on_combat",true)){disableFlight(attacker);disableFlight(victim);}return deny;
    }
    private void disableFlight(ServerPlayer player){if(player.isCreative()||player.isSpectator())return;player.getAbilities().flying=false;player.getAbilities().mayfly=false;player.onUpdateAbilities();}
    public synchronized float damage(ServerPlayer attacker,ServerPlayer victim,float amount)throws Exception {if(amount<=0||!settings.bool("team_upgrades.enabled",true))return amount;Team team=store.teamOf(attacker.getUUID()).orElse(null),other=store.teamOf(victim.getUUID()).orElse(null);if(team==null||other!=null&&team.id()==other.id())return amount;double bonus=Math.min(15,Math.max(0,settings.number("team_upgrades.tiers."+team.tier()+".damage_bonus_percent",0)));return (float)(amount*(1+bonus/100));}
    public synchronized void damageTaken(ServerPlayer player){if(warmups.remove(player.getUUID())!=null)success(player,"&c"+settings.message("teleport_cancelled_damage","TELEPORT_CANCELLED_DAMAGE"));}
    public synchronized void death(LivingEntity entity,DamageSource source)throws Exception {
        if(entity instanceof ServerPlayer victim){Optional<Team> team=store.teamOf(victim.getUUID());if(team.isPresent()){Team t=team.get();store.updateTeam(t.id(),t.owner(),Map.of("deaths",((Number)t.field("deaths")).intValue()+1));}}
        if(entity instanceof ServerPlayer victim&&victim.getKillCredit() instanceof ServerPlayer killer){Optional<Team> team=store.teamOf(killer.getUUID()),other=store.teamOf(victim.getUUID());if(team.isPresent()&&(other.isEmpty()||team.get().id()!=other.get().id())){Team t=team.get();store.updateTeam(t.id(),t.owner(),Map.of("kills",((Number)t.field("kills")).intValue()+1));}}
    }
    public synchronized void tick()throws Exception {
        ticks++;long now=System.currentTimeMillis();prompts.entrySet().removeIf(entry->entry.getValue().expires()<now);
        Iterator<Map.Entry<UUID,Warmup>> iterator=warmups.entrySet().iterator();while(iterator.hasNext()){
            Map.Entry<UUID,Warmup> entry=iterator.next();ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());Warmup warm=entry.getValue();
            if(player==null||player.serverLevel()!=warm.origin()||player.position().distanceToSqr(warm.position())>1||!authenticated(player)){iterator.remove();if(player!=null)success(player,"&cTELEPORT_CANCELLED");continue;}
            if(ticks>=warm.at()){iterator.remove();try{check(player,warm.kind());if(warm.kind().equals("home"))flag(player,"can_use_home","home");player.teleportTo(warm.target(),warm.x(),warm.y(),warm.z(),warm.yaw(),warm.pitch());cooldowns.put(warm.kind()+":"+player.getUUID(),now+settings.integer(warm.kind().equals("home")?"team_home.cooldown_seconds":"team_warps.cooldown_seconds",5)*1000L);success(player,"&aTELEPORTED");}catch(Exception e){refuse(player,e);}}
        }
        chests.tick();
        if(ticks%200==0)for(ServerPlayer player:server.getPlayerList().getPlayers())refresh(player.getUUID());
        if(ticks%12000==0)TeamsMod.LOG.info("[pxc-teams] watchdog ready={} pending={} open-chests={} provider={}",ready,store.pendingOperations().size(),chests.openCount(),bank.available());
    }
    private void registerPlaceholders(){if(FabricLoader.getInstance().isModLoaded("pxc-placeholders"))TeamPlaceholders.register(this);}
    public synchronized String status()throws Exception{return "ready="+ready+" teams="+store.rows("teams",null,null).size()+" members="+store.rows("team_members",null,null).size()+" pending="+store.pendingOperations().size()+" chests="+chests.openCount()+" provider="+bank.available();}
    public synchronized void reload()throws Exception{for(ServerPlayer p:server.getPlayerList().getPlayers()){chests.close(p);p.closeContainer();}prompts.clear();warmups.clear();settings.load();store.validateSchema();chests.validateConvertedDonor();}
    @Override public synchronized void close(){ready=false;for(ServerPlayer player:server.getPlayerList().getPlayers())chests.close(player);data.close();}
    private static Map<String,Object> nullable(String a,Object av,String b,Object bv){Map<String,Object> result=new HashMap<>();result.put(a,av);result.put(b,bv);return result;}
}
