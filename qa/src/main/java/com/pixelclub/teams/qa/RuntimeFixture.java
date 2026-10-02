package com.pixelclub.teams.qa;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Test addon, console-only, explicit synthetic names, strict marked loopback fixture. */
public final class RuntimeFixture implements ModInitializer {
    private static final Set<String> NAMES=Set.of("JTMoneyOwner","JTMoneyMember","JTMoneyCo");
    private static final Class<?>[] NONE=new Class<?>[0];
    private static final long RUN=System.currentTimeMillis();
    private static int sequence;
    private static volatile boolean closeSqlAfterPrepared;
    private static final Map<UUID,List<Boolean>> SAVE_TRACE=new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void onInitialize(){
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)->dispatcher.register(
            Commands.literal("jtqa").requires(source->source.getEntity()==null).then(
                Commands.argument("args",StringArgumentType.greedyString()).executes(context->{
                    try {
                        guard();String args=StringArgumentType.getString(context,"args");
                        Object result=execute(context.getSource().getServer(),args);
                        write(args,result,null);
                        context.getSource().sendSuccess(()->Component.literal("JTQA PASS "+args),false);return 1;
                    }catch(Throwable error){Throwable cause=unwrap(error);try{write("error",null,cause);}catch(Exception ignored){}
                        context.getSource().sendFailure(Component.literal("JTQA FAIL "+cause.getClass().getName()+": "+cause.getMessage()));return 0;}
                }))));
    }
    private static void guard()throws Exception {
        Path root=FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        if(!Boolean.getBoolean("pxcteams.testActors")||!Files.isRegularFile(root.resolve(".justteams-synthetic-fixture")))throw new IllegalStateException("marked testActors fixture required");
        Properties properties=new Properties();try(var reader=Files.newBufferedReader(root.resolve("server.properties"))){properties.load(reader);}
        if(!"127.0.0.1".equals(properties.getProperty("server-ip")))throw new IllegalStateException("strict loopback required");
    }
    private static Object core()throws Exception {
        Object core=Class.forName("com.pixelclub.teams.TeamsMod").getMethod("core").invoke(null);
        if(core==null)throw new IllegalStateException("core absent: "+Class.forName("com.pixelclub.teams.TeamsMod").getMethod("problem").invoke(null));
        return core;
    }
    private static Object call(Object target,String method,Class<?>[] types,Object...args)throws Exception{return target.getClass().getMethod(method,types).invoke(target,args);}
    private static Object field(Object target,String name)throws Exception{return target.getClass().getField(name).get(target);}
    private static ServerPlayer player(net.minecraft.server.MinecraftServer server,String name){
        if(!NAMES.contains(name))throw new IllegalArgumentException("synthetic name required");
        ServerPlayer player=server.getPlayerList().getPlayerByName(name);
        if(player==null||!player.getClass().getName().equals("carpet.patches.EntityPlayerMPFake"))throw new IllegalArgumentException("synthetic Carpet actor absent");
        return player;
    }
    private static Object execute(net.minecraft.server.MinecraftServer server,String args)throws Exception {
        String[] parts=args.split("\\s+",4);
        if(parts[0].equals("settings")){
            Object settings=Class.forName("com.pixelclub.teams.Settings").getConstructor(Path.class).newInstance(FabricLoader.getInstance().getConfigDir().resolve("JustTeams"));
            call(settings,"load",NONE);return "settings loaded";
        }
        Object core=core();
        switch(parts[0]){
            case "status":return call(core,"status",NONE);
            case "seedprofiles":{
                for(String name:NAMES){UUID uuid=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));server.getProfileCache().add(new GameProfile(uuid,name));}
                return "three explicit synthetic offline profiles cached before Carpet spawn";
            }
            case "fence":{
                ServerPlayer player=player(server,parts[1]);Class<?> api=Class.forName("com.pixelclub.teams.money.WalletFence");
                return Map.of("sequence",api.getMethod("checkpoint").invoke(null),"quiet",api.getMethod("quiet",UUID.class,boolean.class).invoke(null,player.getUUID(),false),
                    "awaitSince",api.getMethod("awaitQuiet",UUID.class,boolean.class,long.class,long.class).invoke(null,player.getUUID(),false,Long.parseLong(parts[2]),3000L));
            }
            case "snapshot":return snapshot(server,core,parts.length>1?parts[1]:"snapshot");
            case "papi":{
                Class<?> api=Class.forName("com.pixelclub.placeholders.api.PxcPlaceholders"),subjectApi=Class.forName("com.pixelclub.placeholders.api.PlaceholderSubject");
                Object subject=parts[1].equals("null")?null:subjectApi.getMethod("offline",net.minecraft.server.MinecraftServer.class,UUID.class,String.class).invoke(null,server,offline(parts[1]),parts[1]);
                Map<String,Object> values=new LinkedHashMap<>();values.put("registered",api.getMethod("isRegistered",String.class).invoke(null,"justteams"));
                for(String key:parts[2].split(",")){String token="%justteams_"+key+"%";values.put(key,Map.of("engine",String.valueOf(api.getMethod("parse",subjectApi,String.class).invoke(null,subject,token)),"resolver",String.valueOf(Class.forName("com.pixelclub.teams.TeamPlaceholders").getMethod("resolve",core.getClass(),subjectApi,String.class).invoke(null,core,subject,key))));}return values;
            }
            case "policy":{
                UUID a=parts[1].equals("null")?null:offline(parts[1]),b=parts[2].equals("null")?null:offline(parts[2]);
                return String.valueOf(Class.forName("com.pixelclub.teams.api.TeamsDamagePolicy").getMethod("evaluate",UUID.class,UUID.class).invoke(null,a,b));
            }
            case "cache":{
                Object snapshots=field(core,"snapshots");switch(parts[1]){case "failed"->call(snapshots,"failed",NONE);case "full"->call(snapshots,"refreshAll",NONE);case "team"->call(snapshots,"refreshTeam",new Class<?>[]{int.class},1);default->{}}
                return Map.of("healthy",call(snapshots,"healthy",NONE));
            }
            case "nodes":{
                Map<String,Object> nodes=new LinkedHashMap<>();for(String name:List.of("team","guild","g","clan","party","partymsg")){var node=server.getCommands().getDispatcher().getRoot().getChild(name);nodes.put(name,node==null?"absent":Map.of("identity",System.identityHashCode(node),"commandClass",node.getCommand()==null?"null":node.getCommand().getClass().getName()));}return nodes;
            }
            case "incumbent":{
                if(!Set.of("party","guild").contains(parts[1]))throw new IllegalArgumentException("fixture alias only");
                var root=server.getCommands().getDispatcher().getRoot();for(String name:List.of("children","literals","arguments")){Field f=com.mojang.brigadier.tree.CommandNode.class.getDeclaredField(name);f.setAccessible(true);((Map<?,?>)f.get(root)).remove(parts[1]);}
                server.getCommands().getDispatcher().register(Commands.literal(parts[1]).executes(context->777));return "synthetic incumbent installed";
            }
            case "perms":{
                ServerPlayer p=player(server,parts[1]);Class<?> provider=Class.forName("net.luckperms.api.LuckPermsProvider"),lp=Class.forName("net.luckperms.api.LuckPerms"),um=Class.forName("net.luckperms.api.model.user.UserManager");Object api=provider.getMethod("get").invoke(null),manager=lp.getMethod("getUserManager").invoke(api);((CompletableFuture<?>)um.getMethod("loadUser",UUID.class).invoke(manager,p.getUUID())).get(5,TimeUnit.SECONDS);
                Class<?> access=Class.forName("com.pixelclub.teams.Access");return Map.of("admin",access.getMethod("admin",net.minecraft.commands.CommandSourceStack.class).invoke(null,p.createCommandSourceStack()),"feeMaximum",access.getMethod("permissionMoneyMaximum",ServerPlayer.class,String.class,double.class).invoke(null,p,"justteams.joinfee.",10000d));
            }
            case "gui":{
                ServerPlayer p=player(server,parts[1]);List<Object> slots=new ArrayList<>();for(int i=0;i<p.containerMenu.slots.size();i++){var item=p.containerMenu.slots.get(i).getItem();var name=item.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);var lore=item.get(net.minecraft.core.component.DataComponents.LORE);slots.add(Map.of("slot",i,"item",item.toString(),"name",name==null?"":name.getString(),"nameTree",name==null?"":name.toString(),"lore",lore==null?List.of():lore.lines().stream().map(Component::getString).toList(),"loreTree",lore==null?List.of():lore.lines().stream().map(Object::toString).toList()));}return slots;
            }
            case "core":return invokeCore(server,core,parts[1],parts.length>2?args.substring(args.indexOf(parts[2])):"");
            case "control":return control(core,parts[1],parts.length>2?args.substring(args.indexOf(parts[2])):"");
            case "fill":{
                ServerPlayer p=player(server,parts[1]);for(int i=0;i<36;i++)p.getInventory().setItem(i,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64));return snapshot(server,core,"synthetic-full-inventory");
            }
            case "oversword":{
                ServerPlayer p=player(server,"JTMoneyOwner");var stack=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD,2);stack.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,1);p.getInventory().setItem(0,stack);return Map.of("count",stack.getCount(),"maximum",stack.getMaxStackSize());
            }
            case "codecfloat":{
                var item=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,1);var tag=new net.minecraft.nbt.CompoundTag();tag.putFloat("tinyFloat",1e-5f);tag.putFloat("largeFloat",1e20f);tag.putDouble("tinyDouble",1e-30d);item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
                List<net.minecraft.world.item.ItemStack> slots=new ArrayList<>(Collections.nCopies(9,net.minecraft.world.item.ItemStack.EMPTY));slots.set(0,item);
                Class<?> codec=Class.forName("com.pixelclub.teams.chest.NativeChestCodec");String encoded=(String)codec.getMethod("encode",List.class,net.minecraft.core.RegistryAccess.class).invoke(null,slots,server.registryAccess());
                Object decoded=codec.getMethod("decode",String.class,net.minecraft.core.RegistryAccess.class).invoke(null,encoded,server.registryAccess());String again=(String)codec.getMethod("encode",List.class,net.minecraft.core.RegistryAccess.class).invoke(null,decoded,server.registryAccess());return Map.of("exactCanonicalRoundtrip",encoded.equals(again),"encoded",encoded,"reencoded",again);
            }
            case "armsql":closeSqlAfterPrepared=true;return "actual pool closes after next real CHEST PREPARED SQL commit";
            case "savetrace":{
                ServerPlayer player=player(server,parts[1]);return List.copyOf(SAVE_TRACE.getOrDefault(player.getUUID(),List.of()));
            }
            case "savetracereset":SAVE_TRACE.remove(player(server,parts[1]).getUUID());return "trace reset";
            case "inspecthome":{
                ServerPlayer player=player(server,"JTMoneyOwner");
                return Map.of("serialized",call(core,"serialize",new Class<?>[]{ServerPlayer.class},player),"world",call(core,"worldName",new Class<?>[]{net.minecraft.server.level.ServerLevel.class},player.serverLevel()));
            }
            case "op":return String.valueOf(call(field(core,"store"),"operation",new Class<?>[]{String.class},parts[1]));
            case "wallet":{
                ServerPlayer player=player(server,parts[1]);double balance=Double.parseDouble(parts[2]);
                if(!Double.isFinite(balance)||balance<0||balance>100_000_000_000d)throw new IllegalArgumentException("fixture balance outside public cap");
                Class<?> api=Class.forName("net.impactdev.impactor.api.economy.EconomyService"), accountApi=Class.forName("net.impactdev.impactor.api.economy.accounts.Account");
                Object service=api.getMethod("instance").invoke(null);
                Object account=((CompletableFuture<?>)api.getMethod("account",UUID.class).invoke(service,player.getUUID())).get(5,TimeUnit.SECONDS);
                Object transaction=accountApi.getMethod("set",BigDecimal.class).invoke(account,BigDecimal.valueOf(balance));
                ((CompletableFuture<?>)api.getMethod("save",accountApi).invoke(service,account)).get(5,TimeUnit.SECONDS);
                Object fence=Class.forName("com.pixelclub.teams.money.WalletFence").getMethod("handle",UUID.class,boolean.class).invoke(null,player.getUUID(),false);
                return Map.of("balance",accountApi.getMethod("balance").invoke(account).toString(),"transaction",transaction.toString(),"ioHandle",String.valueOf(fence));
            }
            case "cmd":{
                ServerPlayer player=player(server,parts[1]);String command=args.substring(args.indexOf(parts[1])+parts[1].length()).strip();
                if(!(command.startsWith("team ")||command.equals("team")||command.startsWith("teammsg ")||command.startsWith("guild ")))throw new IllegalArgumentException("team command required");
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),command);return snapshot(server,core,"command");
            }
            case "click":{
                ServerPlayer player=player(server,parts[1]);String[] click=parts[3].split("\\s+");
                String inventoryBefore=player.getInventory().save(new ListTag()).toString(),cursorBefore=player.containerMenu.getCarried().toString();
                player.containerMenu.clicked(Integer.parseInt(parts[2]),Integer.parseInt(click[1]),ClickType.valueOf(click[0]),player);
                @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)snapshot(server,core,"native-handler-click");
                result.put("acted",Map.of("name",parts[1],"inventoryBefore",inventoryBefore,"inventoryAfter",player.getInventory().save(new ListTag()).toString(),"cursorBefore",cursorBefore,"cursorAfter",player.containerMenu.getCarried().toString(),"menuAfter",player.containerMenu.getClass().getName()));
                return result;
            }
            case "close":{
                player(server,parts[1]).closeContainer();return snapshot(server,core,"native-close-handler");
            }
            case "hit":{
                ServerPlayer attacker=player(server,parts[1]),victim=player(server,parts[2]);float before=victim.getHealth();
                boolean accepted=victim.hurt(victim.damageSources().playerAttack(attacker),Float.parseFloat(parts[3]));
                return Map.of("accepted",accepted,"healthBefore",before,"healthAfter",victim.getHealth(),"state",snapshot(server,core,"native-damage-mixin"));
            }
            case "chat":{
                ServerPlayer player=player(server,parts[1]);String text=args.substring(args.indexOf(parts[1])+parts[1].length()).strip();
                Object intercepted=call(core,"chat",new Class<?>[]{ServerPlayer.class,String.class},player,text);return Map.of("intercepted",intercepted,"state",snapshot(server,core,"chat-prompt"));
            }
            case "bank":{
                ServerPlayer player=player(server,parts[1]);String[] bank=parts[3].split("\\s+");
                call(core,"bank",new Class<?>[]{ServerPlayer.class,boolean.class,double.class,String.class},player,parts[2].equals("deposit"),Double.parseDouble(bank[0]),bank[1]);
                return snapshot(server,core,"core-stable-intent");
            }
            case "setbank":{
                double balance=Double.parseDouble(parts[1]);if(!Double.isFinite(balance)||balance<0||balance>1_000_000_000)throw new IllegalArgumentException("fixture team balance outside cap");
                Field data=core.getClass().getDeclaredField("data");data.setAccessible(true);
                try(java.sql.Connection connection=(java.sql.Connection)call(data.get(core),"open",NONE)){
                    if(!connection.getMetaData().getURL().startsWith("jdbc:h2:file:"))throw new IllegalStateException("fixture H2 file required");
                    try(var update=connection.prepareStatement("UPDATE donut_teams SET balance=? WHERE id=1")){update.setDouble(1,balance);if(update.executeUpdate()!=1)throw new IllegalStateException("synthetic seed row missing");}
                }
                return "synthetic baseline control set; no player transfer";
            }
            case "pvp":{
                ServerPlayer owner=player(server,"JTMoneyOwner"),member=player(server,"JTMoneyMember");
                return Map.of("deny",call(core,"blockDamage",new Class<?>[]{ServerPlayer.class,ServerPlayer.class},owner,member),"damage",call(core,"damage",new Class<?>[]{ServerPlayer.class,ServerPlayer.class,float.class},owner,member,10f));
            }
            default:throw new IllegalArgumentException("unknown fixture verb");
        }
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object store,String table)throws Exception{return (List<Map<String,Object>>)call(store,"rows",new Class<?>[]{String.class,String.class,Object.class},table,null,null);}
    private static Object snapshot(net.minecraft.server.MinecraftServer server,Object core,String label)throws Exception {
        Map<String,Object> state=new LinkedHashMap<>();state.put("label",label);state.put("utc",Instant.now().toString());
        try{state.put("status",call(core,"status",NONE));}catch(Exception error){state.put("statusError",unwrap(error).getClass().getName());}
        Object store=field(core,"store");for(String table:List.of("teams","team_members","team_invites","join_requests","team_warps","team_enderchest"))try{state.put(table,rows(store,table));}catch(Exception error){state.put(table+"Error",unwrap(error).getClass().getName());}
        try{state.put("pending",String.valueOf(call(store,"pendingOperations",NONE)));}catch(Exception error){state.put("pendingError",unwrap(error).getClass().getName());}
        Optional<?> chest=Optional.empty();try{chest=(Optional<?>)call(store,"chest",new Class<?>[]{int.class},1);}catch(Exception error){state.put("chestError",unwrap(error).getClass().getName());}
        if(chest.isPresent()){
            Map<String,Object> data=new LinkedHashMap<>();for(String field:List.of("teamId","formatVersion","inventoryJson","originalSha256","generation","updatedAt"))data.put(field,call(chest.get(),field,NONE));state.put("chest",data);
        }
        Object chests=field(core,"chests");state.put("openChests",call(chests,"openCount",NONE));
        List<Object> quarantine=new ArrayList<>();for(String name:NAMES){UUID uuid=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));quarantine.add(Map.of("name",name,"actorQuarantined",call(chests,"actorQuarantined",new Class<?>[]{UUID.class},uuid),"teamQuarantined",call(chests,"quarantined",new Class<?>[]{UUID.class,int.class},uuid,1)));}state.put("quarantine",quarantine);
        List<Object> actors=new ArrayList<>();for(String name:NAMES){ServerPlayer player=server.getPlayerList().getPlayerByName(name);if(player==null)continue;
            Map<String,Object> actor=new LinkedHashMap<>();actor.put("name",name);actor.put("uuid",player.getUUID().toString());actor.put("class",player.getClass().getName());
            actor.put("menu",player.containerMenu.getClass().getName());actor.put("slots",player.containerMenu.slots.size());actor.put("inventory",player.getInventory().save(new ListTag()).toString());
            actor.put("cursor",player.containerMenu.getCarried().toString());actor.put("position",player.position().toString());actor.put("dimension",player.serverLevel().dimension().location().toString());
            Path playerFile=server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID()+".dat");
            if(Files.isRegularFile(playerFile)){var nbt=NbtIo.readCompressed(playerFile,NbtAccounter.unlimitedHeap());actor.put("diskInventory",nbt.getList("Inventory",Tag.TAG_COMPOUND).toString());actor.put("diskInventoryMatches",nbt.getList("Inventory",Tag.TAG_COMPOUND).equals(player.getInventory().save(new ListTag())));}
            actor.put("authenticated",Class.forName("com.pixelclub.teams.TeamsCore").getMethod("authenticated",ServerPlayer.class).invoke(null,player));
            try{Class<?> api=Class.forName("net.impactdev.impactor.api.economy.EconomyService"), accountApi=Class.forName("net.impactdev.impactor.api.economy.accounts.Account");Object service=api.getMethod("instance").invoke(null);
                boolean exists=Boolean.TRUE.equals(((CompletableFuture<?>)api.getMethod("hasAccount",UUID.class).invoke(service,player.getUUID())).get(3,TimeUnit.SECONDS));actor.put("accountExists",exists);
                if(exists){Object account=((CompletableFuture<?>)api.getMethod("account",UUID.class).invoke(service,player.getUUID())).get(3,TimeUnit.SECONDS);actor.put("wallet",accountApi.getMethod("balance").invoke(account).toString());}
            }catch(Exception error){actor.put("walletError",unwrap(error).getClass().getName());}
            actors.add(actor);
        }state.put("actors",actors);return state;
    }
    public static void observeOriginalSave(Object account){
        try{
            guard();Class<?> api=Class.forName("net.impactdev.impactor.api.economy.accounts.Account");UUID owner=(UUID)api.getMethod("owner").invoke(account);
            boolean synthetic=NAMES.stream().anyMatch(name->UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(owner));if(!synthetic)return;
            boolean reserved=!Boolean.TRUE.equals(Class.forName("com.pixelclub.teams.money.WalletFence").getMethod("quiet",UUID.class,boolean.class).invoke(null,owner,false));
            SAVE_TRACE.computeIfAbsent(owner,ignored->Collections.synchronizedList(new ArrayList<>())).add(reserved);
        }catch(Exception error){throw new IllegalStateException("QA save reservation probe failed",error);}
    }
    public static void afterPrepared(String kind,String phase){
        if(!closeSqlAfterPrepared||!kind.equals("CHEST")||!phase.equals("PREPARED"))return;
        try{guard();closeSqlAfterPrepared=false;Object core=core();Field data=core.getClass().getDeclaredField("data");data.setAccessible(true);call(data.get(core),"close",NONE);}
        catch(Exception error){throw new IllegalStateException("QA actual SQL pool close failed",error);}
    }
    private static void write(String action,Object result,Throwable error)throws Exception {
        Path folder=FabricLoader.getInstance().getGameDir().resolve("qa-evidence");Files.createDirectories(folder);
        Map<String,Object> row=new LinkedHashMap<>();row.put("utc",Instant.now().toString());row.put("action",action);row.put("rung","state");row.put("result",result);
        if(error!=null){row.put("error",error.getClass().getName());row.put("message",String.valueOf(error.getMessage()));row.put("frames",Arrays.stream(error.getStackTrace()).limit(8).map(Object::toString).toList());}
        Files.writeString(folder.resolve(String.format("%d-%04d.json",RUN,++sequence)),new GsonBuilder().setPrettyPrinting().create().toJson(row));
    }
    private static Throwable unwrap(Throwable error){while((error instanceof InvocationTargetException||error instanceof java.util.concurrent.ExecutionException)&&error.getCause()!=null)error=error.getCause();return error;}
    private static UUID offline(String name){return UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static Object invokeCore(net.minecraft.server.MinecraftServer server,Object core,String method,String arguments)throws Exception{
        String[] a=arguments.split("\\s+");ServerPlayer p=player(server,a[0]);
        return switch(method){
            case "role"->call(core,method,new Class<?>[]{ServerPlayer.class,UUID.class,boolean.class},p,offline(a[1]),Boolean.parseBoolean(a[2]));
            case "kick"->call(core,method,new Class<?>[]{ServerPlayer.class,UUID.class},p,offline(a[1]));
            case "editFlag"->call(core,method,new Class<?>[]{ServerPlayer.class,UUID.class,String.class,boolean.class},p,offline(a[1]),a[2],Boolean.parseBoolean(a[3]));
            case "member"->call(field(core,"menus"),method,new Class<?>[]{ServerPlayer.class,UUID.class},p,offline(a[1]));
            case "adminPoints"->call(core,method,new Class<?>[]{ServerPlayer.class,int.class,String.class,long.class},p,Integer.parseInt(a[1]),a[2],Long.parseLong(a[3]));
            case "adminBalance"->call(core,method,new Class<?>[]{ServerPlayer.class,int.class,double.class,String.class},p,Integer.parseInt(a[1]),Double.parseDouble(a[2]),a[3]);
            case "adminStats"->call(core,method,new Class<?>[]{ServerPlayer.class,int.class,int.class,int.class},p,Integer.parseInt(a[1]),Integer.parseInt(a[2]),Integer.parseInt(a[3]));
            case "adminSetting"->call(core,method,new Class<?>[]{ServerPlayer.class,int.class,String.class,Object.class},p,Integer.parseInt(a[1]),a[2],Set.of("kills","deaths").contains(a[2])?Integer.parseInt(a[3]):a[3]);
            case "acceptRequest"->call(core,method,new Class<?>[]{ServerPlayer.class,UUID.class},p,offline(a[1]));
            case "claim"->call(field(core,"chests"),method,new Class<?>[]{ServerPlayer.class},p);
            default->throw new IllegalArgumentException("unapproved fixture core method");
        };
    }
    private static Object control(Object core,String method,String arguments)throws Exception{
        Object store=field(core,"store");String[] a=arguments.split("\\s+");
        switch(method){
            case "flag":call(store,"setMemberFlags",new Class<?>[]{int.class,UUID.class,Map.class},1,offline(a[0]),Map.of(a[1],Boolean.parseBoolean(a[2])));call(core,"refreshTeam",new Class<?>[]{int.class},1);return "synthetic flag control";
            case "team":Object value=Set.of("pvp_enabled","is_public","glow_enabled","join_fee_enabled").contains(a[0])?Boolean.parseBoolean(a[1]):Set.of("points").contains(a[0])?Long.parseLong(a[1]):Set.of("join_fee_amount").contains(a[0])?Double.parseDouble(a[1]):arguments.substring(arguments.indexOf(a[1]));call(store,"updateTeam",new Class<?>[]{int.class,UUID.class,Map.class},1,offline("JTMoneyOwner"),Map.of(a[0],value));call(core,"refreshTeam",new Class<?>[]{int.class},1);return "synthetic team control";
            case "ally":if(Boolean.parseBoolean(a[0]))call(store,"insertRow",new Class<?>[]{String.class,Map.class},"team_allies",Map.of("team_id_1",1,"team_id_2",2));else call(store,"deleteRows",new Class<?>[]{String.class,Map.class},"team_allies",Map.of("team_id_1",1,"team_id_2",2));call(field(core,"snapshots"),"refreshAllies",NONE);return "synthetic ally control";
            default:throw new IllegalArgumentException("unapproved fixture control");
        }
    }
}
