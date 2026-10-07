package com.pixelclub.teams;

import com.google.gson.*;
import com.pixelclub.teams.chest.*;
import com.pixelclub.teams.mixin.PlayerSaveAccess;
import com.pixelclub.teams.store.*;
import com.pixelclub.teams.store.TeamStore.Chest;
import com.pixelclub.teams.store.TeamStore.Operation;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Team-id leases, generation CAS and paired inventory receipts. Recovery never replays an unknown transfer. */
public final class TeamChests {
    private final TeamsCore core;
    private final Map<Integer,Lease> leases=new HashMap<>();
    private final Set<Integer> frozenTeams=new HashSet<>();
    /** Team chests whose stored items do not decode on this server's registries: only that chest is refused, the team keeps working. */
    private final Set<Integer> undecodable=new HashSet<>();
    private final Set<UUID> frozenPlayers=new HashSet<>();
    private final ChestJournal journal;
    private static final class Lease {
        int team;UUID player;long generation;String original;boolean open=true,admin;String previousOp;ChestSessionContainer inventory;Menu menu;
    }
    public TeamChests(TeamsCore core){this.core=core;journal=new ChestJournal(core.settings.folder().resolve("chest-intents.jsonl"),(long)core.settings.number("pxc.chest_journal.compact_after_bytes",ChestJournal.DEFAULT_COMPACT_AFTER_BYTES));}
    public Set<Integer> undecodable(){return Set.copyOf(undecodable);}
    public boolean quarantined(UUID player,int team){return frozenPlayers.contains(player)||team>0&&frozenTeams.contains(team);}
    public boolean actorQuarantined(UUID player){return frozenPlayers.contains(player);}
    public int openCount(){return leases.size();}
    public long journalBytes(){return journal.bytes();}
    public void validateConvertedDonor()throws Exception {
        restoreQuarantine();
        undecodable.clear();
        for(Map<String,Object> row:core.store.rows("team_enderchest",null,null)) {
            String original=Objects.toString(row.get("inventory_data"),"");if(original.isEmpty())continue;
            int id=((Number)row.get("team_id")).intValue();Chest chest=core.store.chest(id).orElseThrow(()->new TeamsCore.Refusal("CHEST_CONVERSION_MISSING"));
            if(!sha(original).equals(chest.originalSha256()))throw new TeamsCore.Refusal("CHEST_DONOR_HASH_CHANGED");
            try{var report=NativeChestCodec.decodeReport(chest.inventoryJson(),core.server.registryAccess());if(!report.aeCanonicalized().isEmpty())TeamsMod.LOG.info("[pxc-teams] team {} chest: legacy AE item(s) in slot(s) {} canonicalized by AdvancedEnchantments, saved in that form on next close",id,report.aeCanonicalized());}
            catch(RuntimeException e){undecodable.add(id);TeamsMod.LOG.error("[pxc-teams] team {} chest does not decode here - chest locked, team usable: {}",id,e.toString());}
        }
    }
    public void open(ServerPlayer player)throws Exception {
        core.check(player,"enderchest");core.flag(player,"can_use_enderchest","enderchest");Team team=core.requireTeam(player);
        openFor(player,team,false);
    }
    public void openAdministrative(ServerPlayer player,Team team)throws Exception{
        adminAccess(player);openFor(player,team,true);
    }
    private void adminAccess(ServerPlayer player){if(!core.ready()||!TeamsCore.authenticated(player)||!Access.admin(player.createCommandSourceStack())||!Access.node(player,"justteams.admin.enderchest",false))throw new TeamsCore.Refusal("DENIED");}
    private void openFor(ServerPlayer player,Team team,boolean admin)throws Exception{
        if(quarantined(player.getUUID(),team.id()))throw new TeamsCore.Refusal("CHEST_RECOVERY_REQUIRED");
        if(undecodable.contains(team.id()))throw new TeamsCore.Refusal("CHEST_RECOVERY_REQUIRED");
        if(!core.settings.bool("team_enderchest.enabled",true))throw new TeamsCore.Refusal("CHEST_DISABLED");
        if(core.store.isFrozen(team.id(),player.getUUID()))throw new TeamsCore.Refusal("CHEST_RECOVERY_REQUIRED");
        if(leases.containsKey(team.id()))throw new TeamsCore.Refusal("CHEST_IN_USE");
        Chest record=core.store.chest(team.id()).orElse(null);List<ItemStack> items;String original=null;long generation=0;
        if(record!=null){items=new ArrayList<>(NativeChestCodec.decode(record.inventoryJson(),core.server.registryAccess()));original=record.originalSha256();generation=record.generation();}
        else {
            if(core.store.rows("team_enderchest","team_id",team.id()).stream().anyMatch(row->!Objects.toString(row.get("inventory_data"),"").isEmpty()))throw new TeamsCore.Refusal("CHEST_CONVERSION_MISSING");
            items=new ArrayList<>();
        }
        int rows=core.settings.integer("team_upgrades.tiers."+team.tier()+".enderchest_rows",core.settings.integer("team_enderchest.rows",3));
        // Paid tier upgrades add empty capacity to persisted chests. A later configuration decrease must not discard owned items.
        while(items.size()<Math.max(1,Math.min(6,rows))*9)items.add(ItemStack.EMPTY);
        List<ItemStack> inventory=items;String sourceHash=original;long sourceGeneration=generation;
        java.util.concurrent.Callable<Boolean> action=()->{
            // The fee callback must not replace another lease acquired while external money was settling.
            if(leases.containsKey(team.id()))return false;
            Lease lease=new Lease();lease.team=team.id();lease.player=player.getUUID();lease.generation=sourceGeneration;lease.original=sourceHash;lease.admin=admin;
            lease.inventory=new ChestSessionContainer(lease.team,lease.player,lease.generation,inventory,()->lease.open&&leases.get(lease.team)==lease);
            leases.put(lease.team,lease);
            try{player.openMenu(new SimpleMenuProvider((id,inv,p)->{lease.menu=new Menu(id,inv,lease);return lease.menu;},Texts.of(core.settings.message("team_enderchest_title","Team chest"))));}
            catch(RuntimeException error){lease.open=false;lease.inventory.revoke();leases.remove(lease.team,lease);throw error;}
            if(player.containerMenu==lease.menu)return true;
            // Another mod may refuse the screen; an unopened lease must not lock the team chest while its holder stays online.
            lease.open=false;lease.inventory.revoke();leases.remove(lease.team,lease);return false;
        };core.ensureChanged(admin?action.call():core.paid(player,"enderchest",true,action));
    }
    private final class Menu extends ChestMenu {
        final Lease lease;
        Menu(int id,Inventory player,Lease lease){super(type(lease.inventory.getContainerSize()/9),id,player,lease.inventory,lease.inventory.getContainerSize()/9);this.lease=lease;}
        @Override public boolean stillValid(Player player){return core.ready()&&lease.inventory.stillValid(player);}
        @Override public void clicked(int slot,int button,ClickType type,Player actor) {
            if(!(actor instanceof ServerPlayer player)||!lease.player.equals(player.getUUID())||!lease.open)return;
            if(type==ClickType.THROW||type==ClickType.PICKUP&&slot==-999&&!getCarried().isEmpty()){core.success(player,"&cCHEST_DROP_DISABLED_USE_INVENTORY");return;}
            if(type==ClickType.SWAP&&slot>=0&&slot<slots.size()&&(button>=0&&button<=8||button==40)){ItemStack hotbar=player.getInventory().getItem(button);Slot target=slots.get(slot);if(target.hasItem()&&!hotbar.isEmpty()&&hotbar.getCount()>target.getMaxStackSize(hotbar)){core.success(player,"&cCHEST_OVERSIZED_SWAP_USE_QUICK_MOVE");return;}}
            try{
                if(lease.admin)adminAccess(player);else{core.check(player,"enderchest");core.flag(player,"can_use_enderchest","enderchest");if(core.requireTeam(player).id()!=lease.team)throw new TeamsCore.Refusal("STALE_CHEST");}
                if(quarantined(player.getUUID(),lease.team)||core.store.isFrozen(lease.team,player.getUUID()))throw new TeamsCore.Refusal("STALE_CHEST");
                transaction(player,lease,()->super.clicked(slot,button,type,actor));
            }catch(Exception e){core.refuse(player,e);if(!lease.open)player.closeContainer();}
        }
        @Override public void removed(Player actor){
            if(actor instanceof ServerPlayer player)finish(player,lease);
            super.removed(actor);
        }
    }
    private void transaction(ServerPlayer player,Lease lease,Runnable mutation)throws Exception {
        String op=UUID.randomUUID().toString();List<ItemStack> before=lease.inventory.snapshot();ListTag beforePlayer=player.getInventory().save(new ListTag());ItemStack beforeCursor=lease.menu.getCarried().copy();
        JsonObject payload=new JsonObject();payload.addProperty("generationBefore",lease.generation);payload.addProperty("chestBefore",NativeChestCodec.encode(before,core.server.registryAccess()));
        payload.addProperty("playerBefore",beforePlayer.toString());payload.addProperty("cursorBefore",cursor(beforeCursor));if(lease.previousOp!=null)payload.addProperty("previousOp",lease.previousOp);
        if(payload.toString().length()>NativeChestCodec.MAX_JSON_CHARS)throw new TeamsCore.Refusal("CHEST_JOURNAL_SIZE_LIMIT");
        append(op,lease.team,player.getUUID(),"PREPARED",payload);
        try{core.store.beginOperation(op,lease.team,player.getUUID(),"CHEST",0,"PREPARED",payload.toString());}catch(Exception e){append(op,lease.team,player.getUUID(),"CANCELLED",payload);throw e;}
        boolean chestCommitted=false,chestAttempted=false;
        try {
            mutation.run();String after=NativeChestCodec.encode(lease.inventory.snapshot(),core.server.registryAccess());ListTag afterPlayer=player.getInventory().save(new ListTag());ItemStack afterCursor=lease.menu.getCarried().copy();
            payload.addProperty("chestAfter",after);payload.addProperty("playerAfter",afterPlayer.toString());payload.addProperty("cursorAfter",cursor(afterCursor));payload.addProperty("generationAfter",lease.generation+1);
            append(op,lease.team,player.getUUID(),"CHEST_INTENT",payload);
            core.store.updateOperation(op,"CHEST_INTENT",payload.toString());
            chestAttempted=true;
            if(!core.store.saveChest(lease.team,1,after,lease.original,lease.generation)){
                restore(player,lease,before,beforePlayer,beforeCursor);core.store.updateOperation(op,"CANCELLED",payload.toString());append(op,lease.team,player.getUUID(),"CANCELLED",payload);core.success(player,"&cCHEST_GENERATION_CHANGED");return;
            }
            chestCommitted=true;lease.generation++;
            core.store.updateOperation(op,"PLAYER_INTENT",payload.toString());
            if(!savePlayerConfirmed(player,afterPlayer))throw new TeamsCore.Refusal("PLAYER_SAVE_UNVERIFIED");
            core.store.updateOperation(op,afterCursor.isEmpty()?"DONE":"CURSOR_HELD",payload.toString());
            append(op,lease.team,player.getUUID(),afterCursor.isEmpty()?"DONE":"CURSOR_HELD",payload);
            if(lease.previousOp!=null)core.store.updateOperation(lease.previousOp,"DONE","{\"supersededBy\":\""+op+"\"}");
            if(lease.previousOp!=null)append(lease.previousOp,lease.team,player.getUUID(),"DONE",payload);
            lease.previousOp=afterCursor.isEmpty()?null:op;
        }catch(Exception error) {
            payload.addProperty("sqlCommitUnknown",chestAttempted&&!chestCommitted);
            quarantine(player,lease,lease.team,op,payload,error);
            throw error;
        }
    }
    private void finish(ServerPlayer player,Lease lease) {
        if(!lease.open){leases.remove(lease.team,lease);return;}
        String activeOp=lease.previousOp;JsonObject closePayload=new JsonObject();
        try {
            ItemStack carried=lease.menu==null?ItemStack.EMPTY:lease.menu.getCarried().copy();
            if(!carried.isEmpty()) {
                // A full inventory keeps the value in a recipient-owned receipt instead of an untracked world drop.
                String op=lease.previousOp!=null?lease.previousOp:UUID.randomUUID().toString();
                JsonObject payload=new JsonObject();payload.addProperty("playerBefore",player.getInventory().save(new ListTag()).toString());payload.addProperty("cursorBefore",cursor(carried));
                activeOp=op;closePayload=payload;
                append(op,lease.team,player.getUUID(),"CLOSE_INTENT",payload);
                if(core.store.operation(op).isEmpty())core.store.beginOperation(op,lease.team,player.getUUID(),"CHEST",0,"PREPARED",payload.toString());
                core.store.updateOperation(op,"CLOSE_INTENT",payload.toString());
                ItemStack remaining=carried.copy();OwnedInventoryReturn.insert(player.getInventory(),remaining);lease.menu.setCarried(ItemStack.EMPTY);
                payload.addProperty("playerAfter",player.getInventory().save(new ListTag()).toString());payload.addProperty("cursorEscrow",cursor(remaining));
                core.store.updateOperation(op,"PLAYER_INTENT",payload.toString());
                if(!savePlayerConfirmed(player,player.getInventory().save(new ListTag())))throw new TeamsCore.Refusal("PLAYER_SAVE_UNVERIFIED");
                if(!remaining.isEmpty()){core.store.updateOperation(op,"ESCROW",payload.toString());append(op,lease.team,player.getUUID(),"ESCROW",payload);core.success(player,"&eCHEST_ITEM_IN_ESCROW op="+op+" (/team claim)");}
                else {core.store.updateOperation(op,"DONE",payload.toString());append(op,lease.team,player.getUUID(),"DONE",payload);}
                lease.previousOp=null;
            }
        }catch(Exception error){if(activeOp==null)activeOp=UUID.randomUUID().toString();quarantine(player,lease,lease.team,activeOp,closePayload,error);core.refuse(player,error);}
        finally{lease.open=false;lease.inventory.revoke();leases.remove(lease.team,lease);}
    }
    public void claim(ServerPlayer player)throws Exception {
        core.check(player,"claim");
        for(Operation operation:core.store.pendingOperations()){
            if(!operation.kind().equals("CHEST")||!Objects.equals(operation.player(),player.getUUID())||!operation.phase().equals("ESCROW"))continue;
            JsonObject payload=JsonParser.parseString(operation.payload()).getAsJsonObject();ItemStack item=NativeChestCodec.decode(payload.get("cursorEscrow").getAsString(),core.server.registryAccess()).getFirst();
            if(item.isEmpty()){core.store.updateOperation(operation.opid(),"DONE",payload.toString());continue;}
            String before=player.getInventory().save(new ListTag()).toString();payload.addProperty("claimBefore",before);core.store.updateOperation(operation.opid(),"CLAIM_INTENT",payload.toString());
            append(operation.opid(),operation.teamId(),player.getUUID(),"CLAIM_INTENT",payload);
            try {
                OwnedInventoryReturn.insert(player.getInventory(),item);payload.addProperty("claimAfter",player.getInventory().save(new ListTag()).toString());payload.addProperty("cursorEscrow",cursor(item));
                core.store.updateOperation(operation.opid(),"PLAYER_INTENT",payload.toString());
                if(!savePlayerConfirmed(player,player.getInventory().save(new ListTag())))throw new TeamsCore.Refusal("PLAYER_SAVE_UNVERIFIED");
                core.store.updateOperation(operation.opid(),item.isEmpty()?"DONE":"ESCROW",payload.toString());
                append(operation.opid(),operation.teamId(),player.getUUID(),item.isEmpty()?"DONE":"ESCROW",payload);
            }catch(Exception error){quarantine(player,null,operation.teamId(),operation.opid(),payload,error);throw error;}
        }
    }
    public void close(ServerPlayer player){Lease lease=leases.values().stream().filter(value->value.player.equals(player.getUUID())).findFirst().orElse(null);if(lease!=null){finish(player,lease);if(player.containerMenu==lease.menu)player.closeContainer();}}
    public void revoke(UUID player){ServerPlayer online=core.server.getPlayerList().getPlayer(player);if(online!=null)close(online);}
    public void revokeTeam(int team){Lease lease=leases.get(team);if(lease!=null)revoke(lease.player);}
    public void tick()throws Exception {for(Lease lease:new ArrayList<>(leases.values())){ServerPlayer player=core.server.getPlayerList().getPlayer(lease.player);if(player==null){lease.open=false;lease.inventory.revoke();leases.remove(lease.team,lease);continue;}if(lease.admin){try{adminAccess(player);}catch(TeamsCore.Refusal e){close(player);}continue;}Optional<Member> member=core.store.member(lease.player);if(member.isEmpty()||member.get().teamId()!=lease.team||!member.get().flag("can_use_enderchest")&&!Access.bypass(player,"enderchest"))close(player);}}
    private boolean savePlayerConfirmed(ServerPlayer player,ListTag expected)throws Exception {
        ((PlayerSaveAccess)core.server.getPlayerList()).pxcTeamsSave(player);
        Path root=core.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();Path target=root.resolve(player.getUUID()+".dat").normalize();if(!target.startsWith(root)||!Files.isRegularFile(target)||Files.isSymbolicLink(target))return false;
        try(FileChannel channel=FileChannel.open(target,StandardOpenOption.WRITE)){channel.force(true);}
        CompoundTag actual=NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap());return actual.getList("Inventory",Tag.TAG_COMPOUND).equals(expected);
    }
    private void restore(ServerPlayer player,Lease lease,List<ItemStack> chest,ListTag inventory,ItemStack cursor){for(int i=0;i<chest.size();i++)lease.inventory.getItems().set(i,chest.get(i).copy());player.getInventory().load(inventory);lease.menu.setCarried(cursor.copy());lease.menu.broadcastFullState();}
    private String cursor(ItemStack item){List<ItemStack> holder=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));holder.set(0,item.copy());return NativeChestCodec.encode(holder,core.server.registryAccess());}
    private void append(String op,int team,UUID player,String phase,JsonObject payload)throws Exception {journal.append(op,team,player,phase,payload);}
    private void quarantine(ServerPlayer player,Lease lease,int team,String op,JsonObject payload,Exception error) {
        // This state transition is unconditional: neither a failed SQL lookup nor a failed journal write can bypass it.
        if(team>0)frozenTeams.add(team);frozenPlayers.add(player.getUUID());
        try {
            payload.addProperty("quarantinedInventory",player.getInventory().save(new ListTag()).toString());
            if(lease!=null&&lease.menu!=null)payload.addProperty("cursorEscrow",cursor(lease.menu.getCarried()));
            append(op,team,player.getUUID(),"FROZEN",payload);
        }catch(Exception recordError){error.addSuppressed(recordError);}
        try{core.store.freezeOperation(op,"CHEST_"+error.getClass().getSimpleName());core.store.updateOperation(op,"FROZEN",payload.toString());}catch(Exception recordError){error.addSuppressed(recordError);}
        finally {
            if(lease!=null){if(lease.menu!=null)lease.menu.setCarried(ItemStack.EMPTY);lease.open=false;lease.inventory.revoke();leases.remove(lease.team,lease);}
            // Preserve every inventory byte; disconnect and the durable actor/team block prevent further use.
            try{savePlayerConfirmed(player,player.getInventory().save(new ListTag()));}catch(Exception saveError){error.addSuppressed(saveError);}
            player.connection.disconnect(Texts.of("justTeams inventory quarantined; retained in journal op="+op));
        }
    }
    /**
     * Operator recovery of one FROZEN chest operation, actor offline. The rightful actor state is derived from where the
     * stored chest actually is: still at chestBefore/generationBefore (commit absent, or a close-path op that never touches
     * the chest) -> playerBefore + cursorBefore; at chestAfter/generationAfter (commit landed) -> playerAfter + cursorAfter.
     * The offline player.dat inventory must still equal the quarantined inventory. Anything else is refused. The cursor
     * stack becomes one claimable ESCROW, never a second copy. Dry-run unless apply.
     */
    public synchronized String recover(String opid,boolean apply)throws Exception {
        JsonObject record=latestJournal(opid);if(record==null)throw new TeamsCore.Refusal("RECOVERY_NO_JOURNAL");
        if(!record.get("phase").getAsString().equals("FROZEN"))throw new TeamsCore.Refusal("RECOVERY_NOT_FROZEN");
        UUID actor=UUID.fromString(record.get("player").getAsString());int team=record.get("team").getAsInt();JsonObject payload=record.getAsJsonObject("payload").deepCopy();
        Operation operation=core.store.operation(opid).orElse(null);
        if(operation!=null&&(!operation.kind().equals("CHEST")||!Objects.equals(operation.player(),actor)||Set.of("DONE","CANCELLED","ESCROW","COMPLETED","COMPENSATED","FAILED","REJECTED").contains(operation.phase())))throw new TeamsCore.Refusal("RECOVERY_SQL_STATE");
        if(core.server.getPlayerList().getPlayer(actor)!=null)throw new TeamsCore.Refusal("RECOVERY_ACTOR_ONLINE");
        if(team>0&&leases.containsKey(team))throw new TeamsCore.Refusal("CHEST_IN_USE");
        if(!payload.has("playerBefore")||!payload.has("cursorBefore")||!payload.has("quarantinedInventory")||payload.has("claimBefore"))throw new TeamsCore.Refusal("RECOVERY_UNSUPPORTED_PAYLOAD");
        String decision,inventory,cursorJson;
        if(payload.has("generationBefore")){
            Chest stored=core.store.chest(team).orElse(null);long storedGeneration=stored==null?0:stored.generation();String storedJson=stored==null?null:stored.inventoryJson();
            boolean before=storedGeneration==payload.get("generationBefore").getAsLong()&&sameChest(storedJson,payload.get("chestBefore").getAsString());
            boolean after=payload.has("chestAfter")&&payload.has("generationAfter")&&storedGeneration==payload.get("generationAfter").getAsLong()&&sameChest(storedJson,payload.get("chestAfter").getAsString());
            if(before==after)throw new TeamsCore.Refusal("RECOVERY_CHEST_UNRECOGNISED");
            if(after&&(!payload.has("playerAfter")||!payload.has("cursorAfter")))throw new TeamsCore.Refusal("RECOVERY_UNSUPPORTED_PAYLOAD");
            decision=before?"COMMIT_ABSENT":"COMMIT_LANDED";inventory=payload.get(before?"playerBefore":"playerAfter").getAsString();cursorJson=payload.get(before?"cursorBefore":"cursorAfter").getAsString();
        }else{decision="CLOSE_PATH";inventory=payload.get("playerBefore").getAsString();cursorJson=payload.get("cursorBefore").getAsString();}
        Path root=core.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();Path target=root.resolve(actor+".dat").normalize();
        if(!target.startsWith(root)||!Files.isRegularFile(target)||Files.isSymbolicLink(target))throw new TeamsCore.Refusal("RECOVERY_PLAYER_DATA_MISSING");
        CompoundTag data=NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap());
        ListTag quarantined=listTag(payload.get("quarantinedInventory").getAsString()),rightful=listTag(inventory);
        if(!data.getList("Inventory",Tag.TAG_COMPOUND).equals(quarantined))throw new TeamsCore.Refusal("RECOVERY_PLAYER_DATA_CHANGED");
        ItemStack cursor=NativeChestCodec.decode(cursorJson,core.server.registryAccess()).getFirst();
        String report="op="+opid+" team="+team+" decision="+decision+" inventoryChange="+!rightful.equals(quarantined)+" escrow="+(cursor.isEmpty()?"none":cursor.getCount()+"x"+net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(cursor.getItem()));
        if(!apply)return "DRY-RUN "+report;
        payload.addProperty("recovery",decision);payload.addProperty("recoveryInventory",inventory);payload.addProperty("recoveryAt",System.currentTimeMillis());
        append(opid,team,actor,"RECOVERY_INTENT",payload);if(operation!=null)core.store.updateOperation(opid,"RECOVERY_INTENT",payload.toString());
        data.put("Inventory",rightful.copy());Path temporary=root.resolve(actor+".dat.pxcteams-recovery");NbtIo.writeCompressed(data,temporary);
        try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
        Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        if(!NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()).getList("Inventory",Tag.TAG_COMPOUND).equals(rightful))throw new TeamsCore.Refusal("RECOVERY_PLAYER_SAVE_UNVERIFIED");
        String phase=!cursor.isEmpty()?"ESCROW":decision.equals("COMMIT_LANDED")?"DONE":"CANCELLED";payload.addProperty("cursorEscrow",cursor(cursor));
        if(operation==null)core.store.beginOperation(opid,team,actor,"CHEST",0,phase,payload.toString());else core.store.updateOperation(opid,phase,payload.toString());
        append(opid,team,actor,phase,payload);
        if(payload.has("previousOp")){String previous=payload.get("previousOp").getAsString();if(core.store.operation(previous).map(o->!o.phase().equals("DONE")).orElse(false)){core.store.updateOperation(previous,"DONE","{\"supersededBy\":\""+opid+"\"}");append(previous,team,actor,"DONE",payload);}}
        Set<UUID> players=new HashSet<>();Set<Integer> teams=new HashSet<>();frozenFromJournal(players,teams);
        if(!players.contains(actor))frozenPlayers.remove(actor);if(team>0&&!teams.contains(team))frozenTeams.remove(team);
        return "APPLIED "+report+" phase="+phase+" actorFrozen="+frozenPlayers.contains(actor)+" teamFrozen="+frozenTeams.contains(team);
    }
    private boolean sameChest(String stored,String journalled)throws Exception {
        List<ItemStack> a=stored==null?new ArrayList<>():new ArrayList<>(NativeChestCodec.decode(stored,core.server.registryAccess())),b=new ArrayList<>(NativeChestCodec.decode(journalled,core.server.registryAccess()));
        // Paid-tier padding appends EMPTY slots only; compare after padding the shorter list.
        while(a.size()<b.size())a.add(ItemStack.EMPTY);while(b.size()<a.size())b.add(ItemStack.EMPTY);
        for(int i=0;i<a.size();i++)if(!ItemStack.matches(a.get(i),b.get(i)))return false;return true;
    }
    private static ListTag listTag(String snbt)throws Exception {
        Tag tag=new TagParser(new com.mojang.brigadier.StringReader(snbt)).readValue();if(!(tag instanceof ListTag list))throw new TeamsCore.Refusal("RECOVERY_INVENTORY_INVALID");return list;
    }
    private JsonObject latestJournal(String opid)throws Exception {return journal.latestRecord(opid);}
    private void frozenFromJournal(Set<UUID> players,Set<Integer> teams)throws Exception {
        for(JsonObject record:journal.latestRecords())if(!Set.of("DONE","CANCELLED","ESCROW").contains(record.get("phase").getAsString())){players.add(UUID.fromString(record.get("player").getAsString()));int team=record.get("team").getAsInt();if(team>0)teams.add(team);}
    }
    private void restoreQuarantine()throws Exception {
        for(ChestJournal.Head head:journal.load().values()){String phase=head.phase().getAsString();if(!Set.of("DONE","CANCELLED","ESCROW").contains(phase)){UUID actor=UUID.fromString(head.player().getAsString());int team=head.team().getAsInt();frozenPlayers.add(actor);if(team>0)frozenTeams.add(team);}}
    }
    private static String sha(String input)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
    private static MenuType<?> type(int rows){return switch(rows){case 1->MenuType.GENERIC_9x1;case 2->MenuType.GENERIC_9x2;case 3->MenuType.GENERIC_9x3;case 4->MenuType.GENERIC_9x4;case 5->MenuType.GENERIC_9x5;default->MenuType.GENERIC_9x6;};}
}
