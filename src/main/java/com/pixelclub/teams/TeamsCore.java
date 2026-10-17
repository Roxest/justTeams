package com.pixelclub.teams;

import com.pixelclub.teams.store.*;
import com.pixelclub.teams.store.TeamStore.Operation;
import com.pixelclub.teams.money.*;
import com.pixelclub.teams.api.TeamsDamagePolicy.Decision;
import com.pixelclub.teams.mixin.EntityFlagsAccess;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
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
    public final TeamSnapshots snapshots;
    public final BankService bank;
    public final TeamMenus menus;
    public final TeamChests chests;
    private final TeamDataSource data;
    private final Set<UUID> chatMode=new HashSet<>(),spies=new HashSet<>();
    /** X4 team chat across backends; null = off (x4.team_chat unset). */
    private X4TeamChat x4chat;
    private final Map<UUID,Prompt> prompts=new HashMap<>();
    private final Map<UUID,Warmup> warmups=new HashMap<>();
    private final Map<String,Long> cooldowns=new HashMap<>();
    private final Map<UUID,ArrayDeque<Long>> chatTimes=new HashMap<>();
    private final Map<UUID,Set<UUID>> glowViews=new HashMap<>();
    private long ticks;
    private volatile boolean ready;
    private record Prompt(long expires,Consumer<String> callback){}
    /** target null = X4 remote: when due, hand off to name (the backend) and run after there. */
    private record Warmup(long at,ServerLevel origin,Vec3 position,ServerLevel target,double x,double y,double z,float yaw,float pitch,String kind,String name,String after){}
    public static final class Refusal extends RuntimeException {final Map<String,String> values;public Refusal(String code){this(code,Map.of());}public Refusal(String code,Map<String,String> values){super(code);this.values=values;}}

    public TeamsCore(MinecraftServer server,Settings settings)throws Exception {
        this.server=server;this.settings=settings;
        data=new TeamDataSource(settings);
        store=new TeamStore(data,settings.first("donut_","storage.table_prefix","storage.mysql.table_prefix","storage.prefix"));
        store.validateSchema();
        store.serverName(serverName());
        // Additive tables only. A narrower replica database is never treated as a donor import.
        store.provisionAdditiveTables();
        int staleLeases=store.releaseOwnChestLeases();if(staleLeases>0)TeamsMod.LOG.warn("[pxc-teams] released {} chest lease(s) left by this server's previous run",staleLeases);
        snapshots=new TeamSnapshots(store);
        bank=new BankService(store,new PackEconomy(FabricLoader.getInstance().getGameDir()),settings.folder().resolve("bank-intents.jsonl"),settings.number("team_bank.max_balance",-1),BankService.PXC_LEDGER,server::execute);
        bank.onRetried(this::bankRetried);
        menus=new TeamMenus(this);chests=new TeamChests(this);
        if(settings.bool("x4.team_chat",false)&&settings.value("x4.server_id")==null)TeamsMod.LOG.error("[pxc-teams] x4.team_chat is on but x4.server_id is unset - team chat stays on this backend");
        else if(settings.bool("x4.team_chat",false)){x4chat=new X4TeamChat(server,data,settings.first("donut_","storage.table_prefix","storage.mysql.table_prefix","storage.prefix"),serverName());x4chat.start();}
        for(Operation pending:store.pendingOperationsOwnedHere())if(!(pending.kind().equals("CHEST")&&pending.phase().equals("ESCROW")))store.freezeOperation(pending.opid(),"RESTART_PENDING_"+pending.phase());
        // +k1a1: async bank ops whose ledger step was written are asked again with the same key (no staff needed)
        bank.adopt(store.pendingOperationsOwnedHere());
        chests.validateConvertedDonor();
        snapshots.refreshAll();
        ready=true;
        for(ServerPlayer player:server.getPlayerList().getPlayers())joined(player);
        registerPlaceholders();
    }
    public boolean ready(){return ready;}
    public long pvpCooldown(int team){return Math.max(0,(cooldowns.getOrDefault("pvp:"+team,0L)-System.currentTimeMillis()+999)/1000);}
    public synchronized void check(ServerPlayer player,String action)throws Exception {
        if(!ready)throw new Refusal("NOT_READY");
        if(TeamsMod.legacyEnabled())throw new Refusal("BukkitJustTeamsEnabled");
        if(!authenticated(player))throw new Refusal("LOGIN_REQUIRED");
        if(chests.quarantined(player.getUUID(),store.teamOf(player.getUUID()).map(Team::id).orElse(0)))throw new Refusal("CHEST_RECOVERY_REQUIRED");
        if(!Access.command(player,action))throw new Refusal("DENIED");
        String feature=switch(action){case "create"->"team_creation";case "disband"->"team_disband";case "rename"->"team_rename";case "transfer"->"team_transfer";case "pvp"->"team_pvp_toggle";case "public"->"team_public_toggle";case "glow"->"team_glow";case "invite","invites","accept","deny"->"team_invites";case "join","requests"->"team_join_requests";case "kick"->"member_kick";case "leave"->"member_leave";case "promote"->"member_promote";case "demote"->"member_demote";case "info"->"team_info";case "tag"->"team_tag";case "description"->"team_description";case "color"->"team_settings";case "setjoinfee"->"economy";case "ally","allies"->"team_allies";case "blacklist","unblacklist"->"team_blacklist";case "top"->"team_leaderboard";case "bank"->"team_bank";case "sethome","delhome"->"team_home_set";case "home"->"team_home_teleport";case "setwarp"->"team_warp_set";case "delwarp"->"team_warp_delete";case "warp"->"team_warp_teleport";case "warps"->"team_warps";case "enderchest","ec"->"team_enderchest";case "chat"->"team_chat";default->null;};
        if(feature!=null&&!settings.enabled(feature))throw new Refusal("FEATURE_DISABLED");
        String worldKey=switch(action){case "sethome","home","setwarp","warp"->action;case "enderchest","ec"->"enderchest";default->null;};
        if(worldKey!=null&&settings.bool("world_restrictions.enabled",true)) {
            Object disabled=settings.value("world_restrictions.disabled_worlds."+worldKey);
            if(disabled instanceof Collection<?> list&&list.stream().anyMatch(v->worldName(player.serverLevel()).equalsIgnoreCase(v.toString())))throw new Refusal("WORLD_DENIED",Map.of("feature",action,"world",worldName(player.serverLevel())));
        }
    }
    public static boolean authenticated(ServerPlayer player) {
        // Server-owned Carpet actors are test/automation identities, never client-supplied subclasses.
        if(player.getClass().getName().equals("carpet.patches.EntityPlayerMPFake")&&testActorsAllowed())return true;
        if(!FabricLoader.getInstance().isModLoaded("pxc-auth")){Boolean hybrid=AuthMeBridge.authenticated(player.getUUID(),player.getGameProfile().getName());return hybrid!=null?hybrid:player.getServer()!=null&&player.getServer().usesAuthentication();}
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
    public void elevated(ServerPlayer player)throws Exception{if(!Access.elevated(requireMember(player)))throw new Refusal("ELEVATED_REQUIRED");}
    public void owner(ServerPlayer player)throws Exception{if(!requireTeam(player).owner().equals(player.getUUID()))throw new Refusal("OWNER_REQUIRED");}
    public void flag(ServerPlayer player,String flag,String bypass)throws Exception{if(!Access.flag(requireMember(player),player,flag,bypass))throw new Refusal("MEMBER_FLAG_DENIED");}
    public void success(ServerPlayer player,String text){player.sendSystemMessage(Texts.of(settings.message("prefix","&7[Team] ")+text));}
    public void storageFailed(Throwable error){for(int depth=0;error!=null&&depth<10;depth++,error=error.getCause())if(error instanceof SQLException){snapshots.failed();return;}}
    public void refuse(ServerPlayer player,Throwable error){storageFailed(error);if(error instanceof Refusal refusal)say(player,refusal.getMessage(),refusal.values,"&c");else success(player,"&cSTORAGE_"+error.getClass().getSimpleName());}
    /** Donor messages.yml text for a port code (Messages), with its placeholders; the bare code only when the donor has no key. */
    public void say(ServerPlayer player,String code,Map<String,String> values,String colour){String key=Messages.key(code,values,k->settings.message(k,null)!=null);String format=key==null?null:settings.message(key,null);if(format==null)format=Messages.fallback(code);if(format==null){success(player,colour+code);return;}player.sendSystemMessage(Texts.template(settings.message("prefix","&7[Team] ")+format,values,Map.of()));}
    public void safely(ServerPlayer player,Throwing task){try{task.run();}catch(Exception e){refuse(player,e);}}
    @FunctionalInterface public interface Throwing{void run()throws Exception;}
    public synchronized void joined(ServerPlayer player)throws Exception {
        if(chests.actorQuarantined(player.getUUID())){player.connection.disconnect(Texts.of("justTeams inventory recovery required; inventory retained"));return;}
        store.updateRows("player_cache","player_uuid",player.getUUID(),Map.of("player_name",player.getGameProfile().getName(),"last_seen",new Timestamp(System.currentTimeMillis())));
        if(store.rows("player_cache","player_uuid",player.getUUID()).isEmpty())store.insertRow("player_cache",Map.of("player_uuid",player.getUUID(),"player_name",player.getGameProfile().getName()));
        snapshots.remember(player.getUUID(),player.getGameProfile().getName());
        refresh(player.getUUID());
    }
    public synchronized void departed(ServerPlayer player){warpNameCache.remove(player.getUUID());prompts.remove(player.getUUID());warmups.remove(player.getUUID());chatMode.remove(player.getUUID());spies.remove(player.getUUID());chests.close(player);}
    public void refresh(UUID player)throws Exception{Team previous=snapshots.subject(player).team();Optional<Team> current=store.teamOf(player);if(previous!=null&&(current.isEmpty()||previous.id()!=current.get().id()))snapshots.refreshTeam(previous.id());if(current.isPresent())snapshots.refreshTeam(current.get().id());}
    public void refreshTeam(int team)throws Exception{snapshots.refreshTeam(team);}
    public void invalidate(UUID player){prompts.remove(player);warmups.remove(player);chatMode.remove(player);chests.revoke(player);ServerPlayer online=server.getPlayerList().getPlayer(player);if(online!=null)online.closeContainer();}
    public UUID resolvePlayer(String name)throws Exception {
        try{return UUID.fromString(name);}catch(IllegalArgumentException ignored){}
        ServerPlayer online=server.getPlayerList().getPlayerByName(name);if(online!=null)return online.getUUID();
        List<Map<String,Object>> found=store.rows("player_cache",null,null).stream().filter(row->name.equalsIgnoreCase(String.valueOf(row.get("player_name")))).toList();
        if(found.size()!=1)throw new Refusal(found.isEmpty()?"UNKNOWN_PLAYER":"AMBIGUOUS_PLAYER");return UUID.fromString(found.getFirst().get("player_uuid").toString());
    }
    public String playerName(UUID player)throws Exception{ServerPlayer online=server.getPlayerList().getPlayer(player);if(online!=null)return online.getGameProfile().getName();return Objects.toString(snapshots.name(player),player.toString());}
    public int capacity(Team team){int base=settings.integer("settings.max_team_size",10);int tier=settings.integer("team_upgrades.tiers."+team.tier()+".max_members",base);int value=Math.max(base,tier);ServerPlayer owner=server.getPlayerList().getPlayer(team.owner());if(owner!=null&&settings.bool("settings.permission_based_team_size",true))value=Math.max(value,Access.permissionMaximum(owner,"justteams.team.size.",1000));return value;}
    public int maxWarps(Team team){return Math.min(100,Math.max(1,settings.integer("team_upgrades.tiers."+team.tier()+".max_warps",settings.integer("team_warps.max_warps",5))));}
    public String serverName(){return settings.first("survival","x4.server_id","server-identifier","server_identifier");}
    /** X4 backend names (= Velocity server names), from x4.servers. */
    Set<String> x4Servers(){Object v=settings.value("x4.servers");Set<String> out=new HashSet<>();if(v instanceof Collection<?> c)for(Object o:c)if(o!=null)out.add(o.toString());return out;}
    /** X4 world name -> backend that has it, from x4.world_servers; used for rows saved before X4 (all "survival"). */
    Map<String,String> x4WorldServers(){Object v=settings.value("x4.world_servers");Map<String,String> out=new HashMap<>();if(v instanceof Map<?,?> m)for(var e:m.entrySet())if(e.getKey()!=null&&e.getValue()!=null)out.put(e.getKey().toString(),e.getValue().toString());return out;}
    public void ensureChanged(boolean changed){if(!changed)throw new Refusal("STATE_REFUSED");}
    public synchronized boolean paid(ServerPlayer player,String feature,boolean bankFirst,Callable<Boolean> action)throws Exception {
        return paidAmount(player,settings.cost(feature),bankFirst,action);
    }
    public synchronized boolean paidAmount(ServerPlayer player,double cost,boolean bankFirst,Callable<Boolean> action)throws Exception {
        if(settings.itemCostEnabled())throw new Refusal("ITEM_COST_CONFIGURATION_REQUIRES_MIGRATION");
        String op=UUID.randomUUID().toString();BankService.Result charge=bank.charge(op,player.getUUID(),cost,bankFirst);
        if(!charge.success()){success(player,"&c"+charge.code()+ (charge.pending()?" op="+charge.opid():""));return false;}
        boolean changed;
        try{changed=action.call();}catch(Exception e){bank.unknownCharge(op,"FEATURE_EXECUTION_UNKNOWN");throw e;}
        if(!bank.finishCharge(op,changed))throw new Refusal("FEATURE_RECOVERY_REQUIRED");return changed;
    }
    public synchronized void create(ServerPlayer player,String name,String tag)throws Exception {
        check(player,"create");if(store.teamOf(player.getUUID()).isPresent())throw new Refusal("ALREADY_IN_TEAM");
        if(!name.matches("[A-Za-z0-9_]+")||name.length()<settings.integer("settings.min_name_length",3)||name.length()>settings.integer("settings.max_name_length",16))throw new Refusal("INVALID_NAME");
        if(tag.length()>255||!Texts.plain(tag).matches("[A-Za-z0-9_]{2,}")||Texts.plain(tag).length()>settings.integer("settings.max_tag_length",6))throw new Refusal("INVALID_TAG");
        if(store.teamNamed(name).isPresent()||store.rows("teams","tag",tag).size()>0)throw new Refusal("NAME_OR_TAG_UNAVAILABLE");String color=settings.text("settings.creation.default_color","<white>");if(color.length()>32||serverName().length()>255)throw new Refusal("INVALID_CREATION_CONFIGURATION");
        if(paid(player,"team_creation",false,()->{try{return store.create(name,tag,player.getUUID(),player.getGameProfile().getName(),serverName(),color)!=null;}catch(TeamStore.CreateConflict conflict){if(conflict.getSuppressed().length>0)throw conflict;return false;}})){
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
        checkAdmission(player,team);ensureChanged(paidAmount(player,joinFee(player,team),false,()->store.accept(player.getUUID(),team.id(),capacity(team))));refresh(player.getUUID());success(player,"&aJOINED "+team.name());menus.team(player);
    }
    public synchronized void join(ServerPlayer player,String name)throws Exception {
        check(player,"join");if(store.teamOf(player.getUUID()).isPresent())throw new Refusal("ALREADY_IN_TEAM");
        Team team=store.teamNamed(name).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));
        checkAdmission(player,team);
        if(!team.isPublic()) {
            String key="request:"+player.getUUID();long now=System.currentTimeMillis();if(cooldowns.getOrDefault(key,0L)>now)throw new Refusal("REQUEST_COOLDOWN");
            if(!store.rows("join_requests","player_uuid",player.getUUID()).stream().filter(row->((Number)row.get("team_id")).intValue()==team.id()).toList().isEmpty())throw new Refusal("ALREADY_REQUESTED");
            if(!store.rows("team_blacklist","player_uuid",player.getUUID()).stream().filter(row->((Number)row.get("team_id")).intValue()==team.id()).toList().isEmpty())throw new Refusal("BLACKLISTED");
            ensureChanged(store.insertRow("join_requests",Map.of("team_id",team.id(),"player_uuid",player.getUUID())));cooldowns.put(key,now+60000);success(player,"&aREQUEST_SENT");return;
        }
        ensureChanged(paidAmount(player,joinFee(player,team),false,()->store.joinPublic(player.getUUID(),team.id(),capacity(team))));refresh(player.getUUID());success(player,"&aJOINED "+team.name());menus.team(player);
    }
    private void checkAdmission(ServerPlayer player,Team team)throws Exception{if(chests.quarantined(player.getUUID(),team.id())||store.isFrozen(team.id(),player.getUUID()))throw new Refusal("TEAM_RECOVERY_REQUIRED");if(store.teamOf(player.getUUID()).isPresent())throw new Refusal("ALREADY_IN_TEAM");if(store.members(team.id()).size()>=capacity(team))throw new Refusal("TEAM_FULL");if(store.rows("team_blacklist","player_uuid",player.getUUID()).stream().anyMatch(row->((Number)row.get("team_id")).intValue()==team.id()))throw new Refusal("BLACKLISTED");}
    private double joinFee(ServerPlayer player,Team team){if(!settings.bool("feature_costs.enabled",true)||!settings.bool("feature_costs.economy.enabled",true))return 0;if(team.flag("join_fee_enabled"))return ((Number)team.field("join_fee_amount")).doubleValue();double fee=settings.cost("team_join");if(settings.bool("feature_costs.permission_based_join_fee.enabled",true)){double cap=settings.number("feature_costs.permission_based_join_fee.max_fee",10000);fee=Math.max(fee,Access.permissionMoneyMaximum(player,"justteams.joinfee.",cap));if(cap>0)fee=Math.min(cap,fee);}return fee;}
    public synchronized void acceptRequest(ServerPlayer player,UUID target)throws Exception {check(player,"requests");elevated(player);Team team=requireTeam(player);ServerPlayer online=server.getPlayerList().getPlayer(target);if(online!=null){checkAdmission(online,team);ensureChanged(paidAmount(online,joinFee(online,team),false,()->store.acceptRequest(team.id(),player.getUUID(),target,capacity(team))));}else ensureChanged(store.acceptRequest(team.id(),player.getUUID(),target,capacity(team)));refresh(target);success(player,"&aREQUEST_ACCEPTED");}
    public synchronized void leave(ServerPlayer player)throws Exception{check(player,"leave");int team=requireTeam(player).id();chests.close(player);ensureChanged(store.leave(player.getUUID()));invalidate(player.getUUID());refreshTeam(team);success(player,"&aLEFT_TEAM");menus.team(player);}
    public synchronized void kick(ServerPlayer player,UUID target)throws Exception {
        check(player,"kick");Team team=requireTeam(player);Member victim=store.member(target).orElseThrow(()->new Refusal("NO_MEMBER"));
        if(victim.teamId()!=team.id()||victim.owner())throw new Refusal("DENIED");
        elevated(player);if(victim.role().equals("CO_OWNER")&&!requireMember(player).owner())throw new Refusal("OWNER_REQUIRED");
        chests.revoke(target);ensureChanged(store.kick(team.id(),target));invalidate(target);refreshTeam(team.id());success(player,"&aKICKED");
    }
    public synchronized void role(ServerPlayer player,UUID target,boolean promote)throws Exception {
        check(player,promote?"promote":"demote");Team team=requireTeam(player);Member member=store.member(target).orElseThrow(()->new Refusal("NO_MEMBER"));
        if(member.teamId()!=team.id()||member.owner())throw new Refusal("DENIED");
        if(promote){Member actor=requireMember(player);if(!actor.owner()&&(!actor.role().equals("CO_OWNER")||!actor.flag("can_promote_to_co_owner")))throw new Refusal("ELEVATED_REQUIRED");}
        else owner(player);
        String role=promote?"CO_OWNER":"MEMBER";
        Map<String,Boolean> flags=new HashMap<>();flags.put("can_withdraw",promote);flags.put("can_use_enderchest",true);flags.put("can_set_home",promote);flags.put("can_use_home",true);
        flags.put("can_edit_members",promote);flags.put("can_edit_co_owners",false);flags.put("can_kick_members",promote);flags.put("can_promote_members",false);flags.put("can_demote_members",false);
        ensureChanged(store.setMemberRoleAndFlags(team.id(),target,role,flags));chests.revoke(target);refreshTeam(team.id());success(player,"&aROLE_UPDATED");
    }
    public synchronized void editFlag(ServerPlayer player,UUID target,String key,boolean value)throws Exception{check(player,"settings");owner(player);Team team=requireTeam(player);Member victim=store.member(target).orElseThrow(()->new Refusal("NO_MEMBER"));if(victim.teamId()!=team.id()||victim.owner()||key.equals("can_promote_to_co_owner")&&!victim.role().equals("CO_OWNER"))throw new Refusal("DENIED");ensureChanged(store.setMemberFlags(team.id(),target,Map.of(key,value)));chests.revoke(target);success(player,"&aFLAG_UPDATED");}
    public synchronized void transfer(ServerPlayer player,UUID target)throws Exception{check(player,"transfer");owner(player);Team team=requireTeam(player);chests.revokeTeam(team.id());ensureChanged(store.transferOwnership(team.id(),team.owner(),target));refreshTeam(team.id());success(player,"&aOWNER_UPDATED");menus.team(player);}
    public synchronized void disband(ServerPlayer player)throws Exception{check(player,"disband");owner(player);Team team=requireTeam(player);List<Member> before=store.members(team.id());chests.revokeTeam(team.id());ensureChanged(store.deleteTeam(team.id(),team.owner()));snapshots.refreshTeam(team.id());snapshots.refreshAllies();for(Member member:before)invalidate(member.uuid());success(player,"&aDISBANDED");menus.team(player);}
    public synchronized void setting(ServerPlayer player,String field,Object value)throws Exception {
        String action=switch(field){case "tag"->"tag";case "description"->"description";case "color","gradient_start","gradient_end"->"color";case "is_public"->"public";case "glow_enabled"->"glow";case "pvp_enabled"->"pvp";default->"settings";};
        check(player,action);elevated(player);Team team=requireTeam(player);
        if(Set.of("name","owner_uuid","balance","tier","color","gradient_start","gradient_end").contains(field))throw new Refusal("DEDICATED_ACTION_REQUIRED");
        if(field.equals("pvp_enabled")&&!Access.bypass(player,"pvp.cooldown")&&cooldowns.getOrDefault("pvp:"+team.id(),0L)>System.currentTimeMillis())throw new Refusal("PVP_COOLDOWN");
        if(field.equals("tag")&&(!Texts.plain(value.toString()).matches("[A-Za-z0-9_]{2,}")||Texts.plain(value.toString()).length()>settings.integer("settings.max_tag_length",6)))throw new Refusal("INVALID_TAG");
        if(field.equals("description")&&value.toString().length()>settings.integer("settings.max_description_length",64))throw new Refusal("DESCRIPTION_TOO_LONG");
        ensureChanged(store.updateTeam(team.id(),team.owner(),Collections.singletonMap(field,value)));if(field.equals("pvp_enabled"))cooldowns.put("pvp:"+team.id(),System.currentTimeMillis()+settings.integer("team_pvp.toggle_cooldown",300)*1000L);refreshTeam(team.id());success(player,"&aSETTING_UPDATED");
    }
    public synchronized void rename(ServerPlayer player,String name)throws Exception {
        check(player,"rename");owner(player);if(!Access.node(player,"justteams.rename",true)&&!Access.admin(player.createCommandSourceStack()))throw new Refusal("DENIED");
        if(!name.matches("[A-Za-z0-9_]+")||name.length()<settings.integer("settings.min_name_length",3)||name.length()>settings.integer("settings.max_name_length",16))throw new Refusal("INVALID_NAME");Team team=requireTeam(player);long cooldown=settings.integer("settings.rename_cooldown",604800);
        List<Map<String,Object>> history=store.rows("team_rename_cooldowns","team_id",team.id());if(!history.isEmpty()&&System.currentTimeMillis()<((Timestamp)history.getFirst().get("last_rename")).getTime()+cooldown*1000L)throw new Refusal("RENAME_COOLDOWN");
        if(team.name().equals(name)||store.teamNamed(name).isPresent())throw new Refusal("NAME_UNAVAILABLE");
        double fee=settings.bool("feature_costs.economy.enabled",true)?settings.number("feature_costs.economy.rename",500):0;ensureChanged(paidAmount(player,fee,false,()->store.rename(team.id(),team.owner(),name,cooldown,System.currentTimeMillis())));refreshTeam(team.id());success(player,"&aRENAMED");
    }
    public synchronized void setColor(ServerPlayer player,String first,String second)throws Exception{
        check(player,"color");elevated(player);if(!Access.node(player,"justteams.setcolor",true))throw new Refusal("DENIED");Team team=requireTeam(player);Map<String,Object> values=new HashMap<>();values.put("color",null);values.put("gradient_start",null);values.put("gradient_end",null);
        if(first.equalsIgnoreCase("reset")){}else if(first.matches("#[0-9a-fA-F]{6}")){if(second!=null&&!second.matches("#[0-9a-fA-F]{6}"))throw new Refusal("INVALID_COLOR");values.put("gradient_start",first);values.put("gradient_end",second==null?first:second);}else{net.minecraft.ChatFormatting color=first.length()==2&&(first.charAt(0)=='&'||first.charAt(0)=='§')?net.minecraft.ChatFormatting.getByCode(first.charAt(1)):net.minecraft.ChatFormatting.getByName(first.toLowerCase(Locale.ROOT));if(color==null||!color.isColor())throw new Refusal("INVALID_COLOR");values.put("color",color.name());}
        ensureChanged(store.updateTeam(team.id(),team.owner(),values));refreshTeam(team.id());success(player,"&aCOLOR_UPDATED");
    }
    public synchronized void ally(ServerPlayer player,String action,String name)throws Exception {
        if(action.equals("toggle")){check(player,"ally");elevated(player);if(!settings.bool("team_allies.allow_request_toggle",true))throw new Refusal("FEATURE_DISABLED");Team own=requireTeam(player);ensureChanged(store.updateTeam(own.id(),own.owner(),Map.of("accept_requests",!own.flag("accept_requests"))));success(player,"&aALLY_REQUESTS_UPDATED");return;}if(action.equals("break"))action="remove";
        check(player,"ally");elevated(player);Team team=requireTeam(player),other=store.teamNamed(name).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));if(!settings.bool("team_allies.enabled",true))throw new Refusal("ALLIES_DISABLED");int max=settings.integer("team_allies.max_allies",10);
        boolean changed=switch(action){case "request","add"->store.allyRequest(team.id(),other.id(),player.getUUID(),max);case "accept"->store.allyAccept(other.id(),team.id(),player.getUUID(),max);case "deny"->store.allyDeny(other.id(),team.id(),player.getUUID());case "remove"->store.allyRemove(team.id(),other.id(),player.getUUID());default->throw new Refusal("ALLY_USAGE");};ensureChanged(changed);snapshots.refreshAllies();success(player,"&aALLY_UPDATED");
    }
    public synchronized void blacklist(ServerPlayer player,String name,String reason,boolean remove)throws Exception {
        check(player,remove?"unblacklist":"blacklist");elevated(player);Team team=requireTeam(player);UUID target=resolvePlayer(name);
        if(remove)ensureChanged(store.deleteRows("team_blacklist",Map.of("team_id",team.id(),"player_uuid",target))>0);
        else{if(server.getPlayerList().getPlayer(target)==null)throw new Refusal("PLAYER_OFFLINE");ensureChanged(store.blacklist(team.id(),player.getUUID(),player.getGameProfile().getName(),target,playerName(target),reason));}success(player,"&aBLACKLIST_UPDATED");
    }
    public synchronized void setJoinFee(ServerPlayer player,double fee)throws Exception{check(player,"setjoinfee");elevated(player);double value=BankService.normalize(fee,true);Team team=requireTeam(player);ensureChanged(store.updateTeam(team.id(),team.owner(),Map.of("join_fee_enabled",value>0,"join_fee_amount",value)));success(player,"&aJOIN_FEE_UPDATED");}
    public void requireAdmin(ServerPlayer player){if(!ready||!authenticated(player)||!Access.admin(player.createCommandSourceStack()))throw new Refusal("DENIED");}
    public synchronized void adminBalance(ServerPlayer player,int id,double amount,String opid)throws Exception{requireAdmin(player);if(!Double.isFinite(amount)||amount<0)throw new Refusal("INVALID_AMOUNT");Team team=store.team(id).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));ensureChanged(store.setBalanceAdmin(id,team.owner(),player.getUUID(),team.balance(),amount,opid));refreshTeam(id);success(player,"&aBANK_UPDATED");}
    public synchronized void adminStats(ServerPlayer player,int id,int kills,int deaths)throws Exception{requireAdmin(player);if(kills<0||deaths<0)throw new Refusal("INVALID_STAT");Team team=store.team(id).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));ensureChanged(store.updateTeam(id,team.owner(),Map.of("kills",kills,"deaths",deaths)));refreshTeam(id);}
    public synchronized void adminDisband(ServerPlayer player,int id)throws Exception{requireAdmin(player);Team team=store.team(id).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));List<Member> members=store.members(id);chests.revokeTeam(id);ensureChanged(store.deleteTeam(id,team.owner()));snapshots.refreshTeam(id);snapshots.refreshAllies();for(Member member:members)invalidate(member.uuid());success(player,"&aDISBANDED");}
    public synchronized void adminPoints(ServerPlayer player,int id,String action,long amount)throws Exception{requireAdmin(player);if(amount<0)throw new Refusal("INVALID_POINTS");Team team=store.team(id).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));long current=((Number)team.field("points")).longValue(),delta=switch(action){case "add"->amount;case "remove"->-Math.min(current,amount);case "set"->Math.subtractExact(amount,current);default->throw new Refusal("POINTS_USAGE");};ensureChanged(store.adjustPoints(id,team.owner(),delta));refreshTeam(id);success(player,"&aPOINTS_UPDATED");}
    public synchronized void adminSetting(ServerPlayer player,int id,String field,Object value)throws Exception{requireAdmin(player);Team team=store.team(id).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));if(!Set.of("name","tag","description","is_public","pvp_enabled","kills","deaths").contains(field))throw new Refusal("DEDICATED_ACTION_REQUIRED");if(field.equals("name")){String name=value.toString();if(!name.matches("[A-Za-z0-9_]+")||name.length()<settings.integer("settings.min_name_length",3)||name.length()>settings.integer("settings.max_name_length",16)||store.teamNamed(name).filter(t->t.id()!=id).isPresent())throw new Refusal("INVALID_NAME");}if(field.equals("tag")&&(!value.toString().matches("[A-Za-z0-9_]+")||value.toString().length()>settings.integer("settings.max_tag_length",6)))throw new Refusal("INVALID_TAG");if(field.equals("description")&&value.toString().length()>settings.integer("settings.max_description_length",64))throw new Refusal("DESCRIPTION_TOO_LONG");if((field.equals("kills")||field.equals("deaths"))&&(!(value instanceof Number n)||n.longValue()<0||n.longValue()>Integer.MAX_VALUE))throw new Refusal("INVALID_STAT");ensureChanged(store.updateTeam(id,team.owner(),Map.of(field,value)));refreshTeam(id);success(player,"&aADMIN_UPDATED");}
    public synchronized void adminHome(ServerPlayer player,int id)throws Exception{requireAdmin(player);Team team=store.team(id).orElseThrow(()->new Refusal("UNKNOWN_TEAM"));String location=team.location();if(location==null)throw new Refusal("NO_HOME");String remote=Objects.toString(team.field("home_server"),"");if(!remote.isEmpty()&&!remote.equals(serverName()))throw new Refusal("REMOTE_SERVER_UNAVAILABLE");String[] fields=location.split(",",-1);if(fields.length!=6)throw new Refusal("INVALID_LOCATION");ServerLevel world=resolveWorld(fields[0]);if(world==null)throw new Refusal("WORLD_UNAVAILABLE");double x=Double.parseDouble(fields[1]),y=Double.parseDouble(fields[2]),z=Double.parseDouble(fields[3]);float yaw=Float.parseFloat(fields[4]),pitch=Float.parseFloat(fields[5]);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(x)>29999984||Math.abs(z)>29999984)throw new Refusal("INVALID_LOCATION");player.teleportTo(world,x,y,z,yaw,pitch);}
    public synchronized void adminMember(ServerPlayer player,UUID target,String action)throws Exception{requireAdmin(player);Member member=store.member(target).orElseThrow(()->new Refusal("NO_MEMBER"));if(action.equals("kick")){if(member.owner())throw new Refusal("OWNER_REQUIRED");chests.revoke(target);ensureChanged(store.kick(member.teamId(),target));invalidate(target);refreshTeam(member.teamId());return;}if(action.startsWith("can_")){ensureChanged(store.setMemberFlags(member.teamId(),target,Map.of(action,!member.flag(action))));chests.revoke(target);return;}if(member.owner())throw new Refusal("OWNER_REQUIRED");boolean promote=action.equals("promote");if(!promote&&!action.equals("demote"))throw new Refusal("ADMIN_MEMBER_USAGE");ensureChanged(store.setMemberRoleAndFlags(member.teamId(),target,promote?"CO_OWNER":"MEMBER",Map.of("can_withdraw",promote,"can_use_enderchest",true,"can_set_home",promote,"can_use_home",true,"can_edit_members",promote,"can_edit_co_owners",false,"can_kick_members",promote,"can_promote_members",false,"can_demote_members",false)));chests.revoke(target);refresh(target);}
    public synchronized void serverAlias(ServerPlayer player,String action,String name,String alias)throws Exception{requireAdmin(player);if(action.equals("list")){for(var row:store.rows("server_aliases",null,null))success(player,Objects.toString(row.get("server_name"),"")+" = "+Objects.toString(row.get("alias"),""));return;}if(name==null||name.isBlank()||name.length()>255)throw new Refusal("INVALID_SERVER");if(action.equals("remove"))ensureChanged(store.deleteRows("server_aliases",Map.of("server_name",name))>0);else if(action.equals("set")){if(alias==null||alias.isBlank()||alias.length()>64)throw new Refusal("INVALID_ALIAS");if(store.rows("server_aliases","server_name",name).isEmpty())ensureChanged(store.insertRow("server_aliases",Map.of("server_name",name,"alias",alias)));else ensureChanged(store.updateRows("server_aliases","server_name",name,Map.of("alias",alias,"updated_at",new Timestamp(System.currentTimeMillis())))>0);}else throw new Refusal("SERVER_ALIAS_USAGE");success(player,"&aALIAS_UPDATED");}
    public String platform(UUID player){try{Class<?> provider=Class.forName("org.geysermc.floodgate.api.FloodgateApi");Object api=provider.getMethod("getInstance").invoke(null);return Boolean.TRUE.equals(provider.getMethod("isFloodgatePlayer",UUID.class).invoke(api,player))?"Bedrock":"Java";}catch(ReflectiveOperationException|RuntimeException e){return "Java";}}
    public synchronized void bank(ServerPlayer player,boolean deposit,double amount,String opid)throws Exception {
        check(player,"bank");Team team=requireTeam(player);if(!settings.bool("team_bank.enabled",true))throw new Refusal("BANK_DISABLED");
        // +k1a1 (K-1): under pxc-ledger the player's leg is asked off the server thread and the result lands on a later tick
        int teamId=team.id();
        bank.transferAsync(opid,player.getUUID(),deposit,amount,0,Access.bypass(player,"bank.withdraw"),result->bankDone(player,teamId,deposit,amount,result));
    }
    private synchronized void bankDone(ServerPlayer player,int teamId,boolean deposit,double amount,BankService.Result result) {
        try {
            if(result.success()){String format=settings.message(deposit?"bank_deposit_success":"bank_withdraw_success",deposit?"&aDeposited <amount>":"&aWithdrew <amount>");player.sendSystemMessage(Texts.template(settings.message("prefix","&7[Team] ")+format,Map.of("amount",Texts.money(BankService.normalize(amount,false)),"balance",Texts.money(store.team(teamId).orElseThrow().balance())),Map.of()));}
            else if(result.code().equals("LEDGER_PENDING"))success(player,"&e"+settings.message("bank_pending","Your bank transfer is being confirmed; it finishes automatically.")+" op="+result.opid());
            else{String message=switch(result.code()){case "NO_FUNDS","PLAYER_FUNDS","INSUFFICIENT_PLAYER"->"bank_insufficient_player_funds";case "UNDERFLOW","INSUFFICIENT_BANK"->"bank_insufficient_funds";case "CAP","BANK_CAP"->"bank_max_balance_reached";case "WITHDRAW_DENIED","DENIED"->"bank_withdraw_no_permission";case "INVALID_AMOUNT"->"bank_invalid_amount";default->"economy_error";};success(player,"&c"+settings.message(message,result.code())+(result.pending()?" op="+result.opid():""));}
            refreshTeam(teamId);if(result.success()&&!player.hasDisconnected())menus.bank(player);
        }catch(Exception e){refuse(player,e);}
    }
    /** A bank op that waited for the ledger (LEDGER_PENDING) has ended: tell its player if online here, refresh the team. */
    private synchronized void bankRetried(UUID who,BankService.Result result) {
        try {
            if(result.pending())return;
            ServerPlayer player=server.getPlayerList().getPlayer(who);
            store.teamOf(who).ifPresent(t->{try{refreshTeam(t.id());}catch(Exception ignored){}});
            if(player!=null)success(player,(result.success()?"&a":"&e")+settings.message("bank_pending_settled","Your pending bank transfer finished: ")+result.code()+" op="+result.opid());
        }catch(Exception e){TeamsMod.LOG.warn("[pxc-teams] bank retry notice failed: {}",e.toString());}
    }
    public synchronized void upgrade(ServerPlayer player)throws Exception {
        check(player,"upgrade");owner(player);Team team=requireTeam(player);if(!settings.bool("team_upgrades.enabled",true))throw new Refusal("UPGRADES_DISABLED");
        int next=team.tier()+1;Object configured=settings.value("team_upgrades.tiers."+next);if(configured==null)throw new Refusal("MAX_TIER");
        ensureChanged(store.upgrade(team.id(),team.tier(),next,settings.number("team_upgrades.tiers."+next+".cost",-1)));refreshTeam(team.id());success(player,"&aUPGRADED");menus.upgrades(player);
    }
    public synchronized void setHome(ServerPlayer player,boolean delete)throws Exception {
        check(player,delete?"delhome":"sethome");flag(player,"can_set_home","home");Team team=requireTeam(player);
        if(delete)ensureChanged(store.updateTeam(team.id(),team.owner(),nullable("home_location",null,"home_server",null)));
        else {String location=serialize(player);if(location.length()>255||serverName().length()>255)throw new Refusal("INVALID_LOCATION");ensureChanged(paid(player,"sethome",true,()->store.updateTeam(team.id(),team.owner(),Map.of("home_location",location,"home_server",serverName()))));}
        refreshTeam(team.id());say(player,delete?"HOME_DELETED":"HOME_UPDATED",Map.of(),"&a");
    }
    public synchronized void setWarp(ServerPlayer player,String name,String password)throws Exception {
        check(player,"setwarp");elevated(player);Team team=requireTeam(player);
        if(name.isBlank()||name.length()>32||password!=null&&password.length()>64||serverName().length()>64)throw new Refusal("INVALID_WARP");String location=serialize(player);if(location.length()>255)throw new Refusal("INVALID_LOCATION");ensureChanged(paid(player,"setwarp",true,()->store.setWarp(team.id(),name,location,serverName(),password,maxWarps(team))));warpNameCache.clear();say(player,"WARP_UPDATED",Map.of("warp",name),"&a");
    }
    public synchronized void deleteWarp(ServerPlayer player,String name)throws Exception {check(player,"delwarp");Team team=requireTeam(player);if(!Access.elevated(requireMember(player))&&!name.equalsIgnoreCase(player.getGameProfile().getName()))throw new Refusal("DENIED");ensureChanged(store.deleteWarp(team.id(),name));warpNameCache.clear();say(player,"WARP_DELETED",Map.of("warp",name),"&a");}
    public synchronized void teleport(ServerPlayer player,String kind,String name,String password)throws Exception {
        check(player,kind);Team team=requireTeam(player);String location,serverId;boolean locked=false;
        if(kind.equals("home")){flag(player,"can_use_home","home");location=team.location();serverId=(String)team.field("home_server");}
        else {Map<String,Object> warp=store.rows("team_warps","team_id",team.id()).stream().filter(row->name.equalsIgnoreCase(row.get("warp_name").toString())).findFirst().orElseThrow(()->new Refusal("NO_WARP",Map.of("warp",name)));location=(String)warp.get("location");serverId=(String)warp.get("server_name");String expected=(String)warp.get("password");if(expected!=null&&!expected.isEmpty()&&!expected.equals(password))throw new Refusal(password==null?"WARP_PASSWORD_REQUIRED":"WARP_PASSWORD_INCORRECT",Map.of("warp",name));locked=expected!=null&&!expected.isEmpty();}
        if(location==null||location.isEmpty())throw new Refusal("NO_HOME");
        if(settings.bool("x4.remote_teleport",false)){if(settings.value("x4.server_id")==null){TeamsMod.LOG.error("[pxc-teams] x4.remote_teleport is on but x4.server_id is unset - remote homes/warps refused");throw new Refusal("REMOTE_SERVER_UNAVAILABLE");}String world=location.split(",",-1)[0];String dest=RemoteTravel.route(serverName(),serverId,x4Servers(),resolveWorld(world)!=null,world,x4WorldServers());if(dest!=null){remoteTeleport(player,kind,name,dest,locked);return;}}
        else if(serverId!=null&&!serverId.isEmpty()&&!serverId.equals(serverName()))throw new Refusal("REMOTE_SERVER_UNAVAILABLE");
        String[] fields=location.split(",",-1);if(fields.length!=6)throw new Refusal("INVALID_LOCATION");ServerLevel target=resolveWorld(fields[0]);if(target==null)throw new Refusal("WORLD_UNAVAILABLE");
        double x=Double.parseDouble(fields[1]),y=Double.parseDouble(fields[2]),z=Double.parseDouble(fields[3]);float yaw=Float.parseFloat(fields[4]),pitch=Float.parseFloat(fields[5]);
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(x)>29_999_984||Math.abs(z)>29_999_984)throw new Refusal("INVALID_LOCATION");
        boolean bypass=Access.bypass(player,kind+".cooldown");String key=kind+":"+player.getUUID();long left=cooldowns.getOrDefault(key,0L)-System.currentTimeMillis();if(!bypass&&left>0)throw new Refusal("TELEPORT_COOLDOWN",Map.of("time",((left+999)/1000)+"s","seconds",String.valueOf((left+999)/1000)));
        if(!paid(player,kind,true,()->true))return;
        int delay=bypass?0:settings.integer(kind.equals("home")?"team_home.warmup_seconds":"team_warps.warmup_seconds",5)*20;
        warmups.put(player.getUUID(),new Warmup(ticks+delay,player.serverLevel(),player.position(),target,x,y,z,yaw,pitch,kind,name==null?"":name,null));say(player,"TELEPORT_WARMUP",Map.of("seconds",String.valueOf(delay/20)),"&e");
    }
    public String serialize(ServerPlayer player){return worldName(player.serverLevel())+","+player.getX()+","+player.getY()+","+player.getZ()+","+player.getYRot()+","+player.getXRot();}
    public String worldName(ServerLevel level) {
        String id=level.dimension().location().toString();
        try {
            Class<?> worlds=Class.forName("com.pixelclub.worlds.Worlds");Object config=worlds.getMethod("config").invoke(null);Object entry=config==null?null:config.getClass().getMethod("get",String.class).invoke(config,id);
            if(entry!=null){Object value=entry.getClass().getMethod("bukkitName").invoke(entry);if(value!=null&&!value.toString().isEmpty())return value.toString();}
            return (String)Class.forName("com.pixelclub.worlds.WorldNames").getMethod("defaultBukkitName",String.class).invoke(null,id);
        }catch(ReflectiveOperationException e){return switch(id){case "minecraft:overworld"->"world";case "minecraft:the_nether"->"world_nether";case "minecraft:the_end"->"world_the_end";default->id;};}
    }
    public ServerLevel resolveWorld(String name){String typed=name==null?"":name.trim();ServerLevel found=null;for(ServerLevel level:server.getAllLevels()){ResourceLocation id=level.dimension().location();if(id.toString().equalsIgnoreCase(typed)||worldName(level).equalsIgnoreCase(typed)||id.getNamespace().equals("minecraft")&&id.getPath().equalsIgnoreCase(typed)){if(found!=null&&found!=level)return null;found=level;}}return found;}
    public synchronized void prompt(ServerPlayer player,String label,Consumer<String> callback){player.closeContainer();prompts.put(player.getUUID(),new Prompt(System.currentTimeMillis()+60000,callback));success(player,"&e"+label+" &7(cancel)");}
    public synchronized boolean chat(ServerPlayer player,String text) {
        Prompt prompt=prompts.remove(player.getUUID());if(prompt!=null){if(prompt.expires()>System.currentTimeMillis()&&!text.equalsIgnoreCase("cancel")&&!text.equals("ยกเลิก")){if(authenticated(player))prompt.callback().accept(text);}return true;}
        String marker=settings.text("team_chat.character","#");boolean prefixed=settings.bool("team_chat.character_enabled",true)&&text.startsWith(marker)&&(!settings.bool("team_chat.require_space",false)||text.startsWith(marker+" "));
        if(!prefixed&&!chatMode.contains(player.getUUID()))return false;
        String body=prefixed?text.substring(marker.length()).stripLeading():text;safely(player,()->message(player,body,true));return true;
    }
    public synchronized boolean wantsChat(ServerPlayer player,String text){String marker=settings.text("team_chat.character","#");return prompts.containsKey(player.getUUID())||chatMode.contains(player.getUUID())||settings.bool("team_chat.character_enabled",true)&&text.startsWith(marker)&&(!settings.bool("team_chat.require_space",false)||text.startsWith(marker+" "));}
    public synchronized void message(ServerPlayer player,String message)throws Exception {
        message(player,message,false);
    }
    private synchronized void message(ServerPlayer player,String message,boolean includeSpies)throws Exception {
        check(player,includeSpies?"chat":"msg");Team team=requireTeam(player);if(message.isBlank()||!includeSpies&&message.length()>200)throw new Refusal("INVALID_MESSAGE");
        String lower=message.toLowerCase(Locale.ROOT);if(lower.contains("password")||lower.contains("/login")||lower.contains("/register"))throw new Refusal("PRIVATE_MESSAGE_REJECTED");
        String format=settings.message("team_chat_format","&8[&a<team_name>&8] &f<player>&7: <message>");
        if(!includeSpies){long now=System.currentTimeMillis();ArrayDeque<Long> recent=chatTimes.computeIfAbsent(player.getUUID(),id->new ArrayDeque<>());while(!recent.isEmpty()&&recent.getFirst()<now-60000)recent.removeFirst();if(!recent.isEmpty()&&now-recent.getLast()<2000||recent.size()>=20)throw new Refusal("CHAT_COOLDOWN");recent.addLast(now);}
        String teamColor=Objects.toString(team.field("color"),"white").replace("<","").replace(">","");if(!teamColor.matches("#[0-9a-fA-F]{6}|[a-z_]+"))teamColor="white";format=format.replace("<team_color>","<"+teamColor+">").replace("</team_color>","</"+teamColor+">");
        Map<String,String> plain=Map.of("team_name",team.name(),"team_tag",team.tag(),"player",player.getGameProfile().getName(),"message",message);
        String prefix=Access.meta(player,true),suffix=Access.meta(player,false);Map<String,String> trusted=Map.of("prefix",prefix,"player_prefix",prefix,"suffix",suffix,"player_suffix",suffix);
        Set<UUID> members=new HashSet<>();for(Member member:store.members(team.id()))members.add(member.uuid());
        Component component=Texts.template(format,plain,trusted);Component asMember=chatTag(component,"team"),asSpy=chatTag(component,"spy");for(ServerPlayer recipient:server.getPlayerList().getPlayers())if(authenticated(recipient)){if(members.contains(recipient.getUUID()))recipient.sendSystemMessage(asMember);else if(includeSpies&&spies.contains(recipient.getUUID())&&Access.node(recipient,"justteams.chatspy",false))recipient.sendSystemMessage(asSpy);}
        if(x4chat!=null)x4chat.publish(team.id(),asMember,includeSpies?asSpy:null);
    }
    /** pxc.7+chat: invisible chat-tab marker (font pixelclub:chattag/<category>) read by the Pixelclub client. */
    private static Component chatTag(Component line,String category){return line.copy().append(Component.literal("").withStyle(net.minecraft.network.chat.Style.EMPTY.withFont(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("pixelclub","chattag/"+category))));}
    public synchronized void toggleChat(ServerPlayer player)throws Exception{check(player,"chat");requireTeam(player);if(!chatMode.remove(player.getUUID()))chatMode.add(player.getUUID());success(player,"&aCHAT="+chatMode.contains(player.getUUID()));}
    public synchronized void toggleSpy(ServerPlayer player)throws Exception{check(player,"chatspy");if(!Access.node(player,"justteams.chatspy",false))throw new Refusal("DENIED");if(!spies.remove(player.getUUID()))spies.add(player.getUUID());success(player,"&aCHATSPY="+spies.contains(player.getUUID()));}
    public Decision damagePolicy(UUID attacker,UUID victim){
        if(!ready||attacker==null||victim==null||TeamsMod.legacyEnabled())return Decision.UNKNOWN;
        return snapshots.policy(attacker,victim);
    }
    public synchronized boolean blockDamage(ServerPlayer attacker,ServerPlayer victim)throws Exception {
        if(attacker==victim)return false;return damagePolicy(attacker.getUUID(),victim.getUUID())!=Decision.ALLOW;
    }
    /** After hurt() returned true: justTeams' PvPListener ran at HIGH with ignoreCancelled, so a hit a region already denied (spawn pvp: deny) never cost anyone their flight. */
    public void pvpLanded(ServerPlayer attacker,ServerPlayer victim){
        if(attacker==victim||damagePolicy(attacker.getUUID(),victim.getUUID())!=Decision.ALLOW||!settings.bool("team_pvp.disable_fly_on_combat",true))return;disableFlight(attacker);disableFlight(victim);
    }
    private void disableFlight(ServerPlayer player){if(player.isCreative()||player.isSpectator())return;player.getAbilities().flying=false;player.getAbilities().mayfly=false;player.onUpdateAbilities();}
    public synchronized float damage(ServerPlayer attacker,ServerPlayer victim,float amount)throws Exception {if(amount<=0||!snapshots.healthy()||!settings.bool("team_upgrades.enabled",true))return amount;Team team=snapshots.subject(attacker.getUUID()).team(),other=snapshots.subject(victim.getUUID()).team();if(team==null||other!=null&&team.id()==other.id())return amount;double bonus=Math.max(0,settings.number("team_upgrades.tiers."+team.tier()+".damage_bonus_percent",0));return Double.isFinite(bonus)?(float)(amount*(1+bonus/100)):amount;}
    public synchronized void damageTaken(ServerPlayer player){if(warmups.remove(player.getUUID())!=null)success(player,"&c"+settings.message("teleport_cancelled_damage","TELEPORT_CANCELLED_DAMAGE"));}
    public synchronized void death(LivingEntity entity,DamageSource source)throws Exception {
        if(entity instanceof ServerPlayer victim){Optional<Team> team=store.teamOf(victim.getUUID());if(team.isPresent()){Team t=team.get();store.updateTeam(t.id(),t.owner(),Map.of("deaths",((Number)t.field("deaths")).intValue()+1));}}
        if(entity instanceof ServerPlayer victim&&victim.getKillCredit() instanceof ServerPlayer killer){Optional<Team> team=store.teamOf(killer.getUUID()),other=store.teamOf(victim.getUUID());if(team.isPresent()&&(other.isEmpty()||team.get().id()!=other.get().id())){Team t=team.get();store.updateTeam(t.id(),t.owner(),Map.of("kills",((Number)t.field("kills")).intValue()+1));}}
    }
    /**
     * X4 (x4.remote_teleport, off = REMOTE_SERVER_UNAVAILABLE as before): a team home/warp on another backend (see
     * RemoteTravel.route) hands the player there through pxc-xfer and runs the same /team home|warp on arrival, where it is local - permission,
     * cost, cooldown and warm-up are decided there, once, and nothing is charged here. A password warp is not sent: the
     * password would ride in the hand-off line and its log (same rule as remote /pw, Kraus 10-13).
     */
    private void remoteTeleport(ServerPlayer player,String kind,String name,String dest,boolean locked)throws Exception{
        RemoteTravel plan=RemoteTravel.plan(true,net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("pxcxfer"),locked,kind,name,dest,settings.text("x4.arrival_command","team"));
        if(plan.refusal()!=null)throw new Refusal(plan.refusal(),Map.of("warp",name==null?"":name));
        // review MED (scrutinize 10-14): the source keeps its warm-up and "moving cancels" (no charge, no cooldown here),
        // so a cross-backend /team home is not an instant combat escape; the destination runs its own as for any local use
        boolean bypass=Access.bypass(player,kind+".cooldown");
        int delay=bypass?0:settings.integer(kind.equals("home")?"team_home.warmup_seconds":"team_warps.warmup_seconds",5)*20;
        warmups.put(player.getUUID(),new Warmup(ticks+delay,player.serverLevel(),player.position(),null,0,0,0,0,0,kind,dest,plan.after()));
        say(player,"TELEPORT_WARMUP",Map.of("seconds",String.valueOf(delay/20)),"&e");
    }
    private void remoteGo(ServerPlayer player,String kind,String dest,String after)throws Exception{
        check(player,kind);if(kind.equals("home"))flag(player,"can_use_home","home");
        String why=XferBridge.go(player,dest,after);
        if(why!=null)throw new Refusal("REMOTE_TRAVEL_REFUSED",Map.of("server",dest,"reason",XferReason.thai(why)));
        TeamsMod.LOG.info("[pxc-teams] X4 {} -> {} for {} (then '{}')",kind,dest,player.getGameProfile().getName(),after);
        say(player,"REMOTE_TRAVEL",Map.of("server",dest),"&e");
    }
    private final Map<UUID,Map.Entry<Long,List<String>>> warpNameCache=new HashMap<>();
    /** Own team's warp names for /team warp tab-completion (donor parity); cached 5 s so typing does not query per key. */
    public synchronized List<String> warpNames(ServerPlayer player){
        long now=System.currentTimeMillis();var hit=warpNameCache.get(player.getUUID());if(hit!=null&&hit.getKey()>now)return hit.getValue();
        List<String> names=List.of();
        try{Optional<Team> team=ready?store.teamOf(player.getUUID()):Optional.empty();if(team.isPresent())names=store.rows("team_warps","team_id",team.get().id()).stream().map(row->String.valueOf(row.get("warp_name"))).toList();}
        catch(Exception e){storageFailed(e);}
        warpNameCache.put(player.getUUID(),Map.entry(now+5000,names));return names;
    }
    public synchronized void tick()throws Exception {
        ticks++;long now=System.currentTimeMillis();prompts.entrySet().removeIf(entry->entry.getValue().expires()<now);
        Iterator<Map.Entry<UUID,Warmup>> iterator=warmups.entrySet().iterator();while(iterator.hasNext()){
            Map.Entry<UUID,Warmup> entry=iterator.next();ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());Warmup warm=entry.getValue();
            if(player==null||player.serverLevel()!=warm.origin()||player.position().distanceToSqr(warm.position())>1||!authenticated(player)){iterator.remove();if(player!=null)say(player,"TELEPORT_CANCELLED",Map.of(),"&c");continue;}
            if(ticks>=warm.at()&&warm.target()==null){iterator.remove();try{remoteGo(player,warm.kind(),warm.name(),warm.after());}catch(Exception e){refuse(player,e);}continue;}
            if(ticks>=warm.at()){iterator.remove();try{check(player,warm.kind());if(warm.kind().equals("home"))flag(player,"can_use_home","home");player.teleportTo(warm.target(),warm.x(),warm.y(),warm.z(),warm.yaw(),warm.pitch());cooldowns.put(warm.kind()+":"+player.getUUID(),now+settings.integer(warm.kind().equals("home")?"team_home.cooldown_seconds":"team_warps.cooldown_seconds",5)*1000L);say(player,warm.kind().equals("home")?"TELEPORTED_HOME":"TELEPORTED_WARP",Map.of("warp",warm.name()),"&a");}catch(Exception e){refuse(player,e);}}
        }
        chests.tick();
        if(ticks%20==0)bank.tickRetries();
        if(x4chat!=null){if(ticks%20==0)x4chat.poll(store::members,TeamsCore::authenticated,p->spies.contains(p.getUUID())&&Access.node(p,"justteams.chatspy",false));if(ticks%6000==0)x4chat.prune();}
        if(ticks%Math.max(1,settings.integer("settings.glow.check_interval",20))==0)glowTick();
        if(ticks%200==0)snapshots.refreshAll();
        if(ticks%12000==0)watchdog();
    }
    /** pxc.9: the pending count is a MySQL SELECT (850 ms once on PRD 2026-10-07, a whole tick); it now runs on its own thread. pendingOperations opens its own connection and takes no lock. */
    private static final java.util.concurrent.ExecutorService WATCHDOG=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"pxc-teams-watchdog");t.setDaemon(true);return t;});
    private void watchdog(){
        boolean r=ready;int open=chests.openCount();boolean provider=bank.available();long journal=chests.journalBytes();
        WATCHDOG.execute(()->{String pending;try{pending=String.valueOf(store.pendingOperations().size());}catch(Exception e){pending="error "+e;}
            TeamsMod.LOG.info("[pxc-teams] watchdog ready={} pending={} open-chests={} provider={} chest-journal={}",r,pending,open,provider,journal);});
    }
    private void glowTick()throws Exception{
        var flags=EntityFlagsAccess.pxcTeams$flags();boolean ownOnly=settings.bool("settings.glow.only_show_own_team",true);
        for(ServerPlayer viewer:server.getPlayerList().getPlayers()){Set<UUID> previous=glowViews.getOrDefault(viewer.getUUID(),Set.of()),current=new HashSet<>(),candidates=new HashSet<>(previous);
            if(ownOnly){for(Member member:snapshots.subject(viewer.getUUID()).members())candidates.add(member.uuid());}else for(ServerPlayer target:server.getPlayerList().getPlayers())candidates.add(target.getUUID());
            for(UUID id:candidates){ServerPlayer target=server.getPlayerList().getPlayer(id);if(target==null||target==viewer)continue;boolean show=shouldGlow(viewer,target);if(show)current.add(id);if(show||previous.contains(id)){byte base=target.getEntityData().get(flags);viewer.connection.send(new ClientboundSetEntityDataPacket(target.getId(),List.of(SynchedEntityData.DataValue.create(flags,show?(byte)(base|0x40):base))));}}
            glowViews.put(viewer.getUUID(),current);
        }
        glowViews.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
    }
    public boolean shouldGlow(ServerPlayer viewer,ServerPlayer target){if(!ready||viewer==target||!snapshots.healthy()||!settings.enabled("team_glow")||!settings.bool("settings.glow.enabled",true)||!authenticated(viewer)||!authenticated(target)||viewer.serverLevel()!=target.serverLevel())return false;Team own=snapshots.subject(viewer.getUUID()).team(),team=snapshots.subject(target.getUUID()).team();double range=settings.number("settings.glow.range",30);return team!=null&&team.flag("glow_enabled")&&(!settings.bool("settings.glow.only_show_own_team",true)||own!=null&&own.id()==team.id())&&Double.isFinite(range)&&range>=0&&viewer.distanceToSqr(target)<=range*range;}
    private void registerPlaceholders(){if(FabricLoader.getInstance().isModLoaded("pxc-placeholders"))TeamPlaceholders.register(this);}
    public synchronized String status()throws Exception{return "ready="+ready+" snapshot-health="+snapshots.healthy()+" teams="+store.rows("teams",null,null).size()+" members="+store.rows("team_members",null,null).size()+" pending="+store.pendingOperations().size()+" chests="+chests.openCount()+" provider="+bank.available();}
    public synchronized void reload()throws Exception{for(ServerPlayer p:server.getPlayerList().getPlayers()){chests.close(p);p.closeContainer();}prompts.clear();warmups.clear();settings.load();bank.setCap(settings.number("team_bank.max_balance",-1));TeamCommands.register(server.getCommands().getDispatcher(),settings);store.validateSchema();chests.validateConvertedDonor();}
    @Override public synchronized void close(){ready=false;if(x4chat!=null)x4chat.stop();for(ServerPlayer player:server.getPlayerList().getPlayers())chests.close(player);data.close();}
    private static Map<String,Object> nullable(String a,Object av,String b,Object bv){Map<String,Object> result=new HashMap<>();result.put(a,av);result.put(b,bv);return result;}
}
