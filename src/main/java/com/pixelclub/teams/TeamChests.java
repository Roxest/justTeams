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
    private static final class Lease {
        int team;UUID player;long generation;String original;boolean open=true;String previousOp;ChestSessionContainer inventory;Menu menu;
    }
    public TeamChests(TeamsCore core){this.core=core;}
    public int openCount(){return leases.size();}
    public void validateConvertedDonor()throws Exception {
        for(Map<String,Object> row:core.store.rows("team_enderchest",null,null)) {
            String original=Objects.toString(row.get("inventory_data"),"");if(original.isEmpty())continue;
            int id=((Number)row.get("team_id")).intValue();Chest chest=core.store.chest(id).orElseThrow(()->new TeamsCore.Refusal("CHEST_CONVERSION_MISSING"));
            if(!sha(original).equals(chest.originalSha256()))throw new TeamsCore.Refusal("CHEST_DONOR_HASH_CHANGED");
            NativeChestCodec.decode(chest.inventoryJson(),core.server.registryAccess());
        }
    }
    public void open(ServerPlayer player)throws Exception {
        core.check(player,"enderchest");core.flag(player,"can_use_enderchest","enderchest");Team team=core.requireTeam(player);
        if(!core.settings.bool("team_enderchest.enabled",true))throw new TeamsCore.Refusal("CHEST_DISABLED");
        if(core.store.isFrozen(team.id(),player.getUUID()))throw new TeamsCore.Refusal("CHEST_RECOVERY_REQUIRED");
        if(leases.containsKey(team.id()))throw new TeamsCore.Refusal("CHEST_IN_USE");
        Chest record=core.store.chest(team.id()).orElse(null);List<ItemStack> items;String original=null;long generation=0;
        if(record!=null){items=NativeChestCodec.decode(record.inventoryJson(),core.server.registryAccess());original=record.originalSha256();generation=record.generation();}
        else {
            if(core.store.rows("team_enderchest","team_id",team.id()).stream().anyMatch(row->!Objects.toString(row.get("inventory_data"),"").isEmpty()))throw new TeamsCore.Refusal("CHEST_CONVERSION_MISSING");
            int rows=core.settings.integer("team_upgrades.tiers."+team.tier()+".enderchest_rows",core.settings.integer("team_enderchest.rows",3));items=new ArrayList<>(Collections.nCopies(Math.max(1,Math.min(6,rows))*9,ItemStack.EMPTY));
        }
        List<ItemStack> inventory=items;String sourceHash=original;long sourceGeneration=generation;
        core.ensureChanged(core.paid(player,"enderchest",true,()->{
            // The fee callback must not replace another lease acquired while external money was settling.
            if(leases.containsKey(team.id()))return false;
            Lease lease=new Lease();lease.team=team.id();lease.player=player.getUUID();lease.generation=sourceGeneration;lease.original=sourceHash;
            lease.inventory=new ChestSessionContainer(lease.team,lease.player,lease.generation,inventory,()->lease.open&&leases.get(lease.team)==lease);
            leases.put(lease.team,lease);
            player.openMenu(new SimpleMenuProvider((id,inv,p)->{lease.menu=new Menu(id,inv,lease);return lease.menu;},Texts.of(core.settings.message("team_enderchest_title","Team chest"))));
            return player.containerMenu==lease.menu;
        }));
    }
    private final class Menu extends ChestMenu {
        final Lease lease;
        Menu(int id,Inventory player,Lease lease){super(type(lease.inventory.getContainerSize()/9),id,player,lease.inventory,lease.inventory.getContainerSize()/9);this.lease=lease;}
        @Override public boolean stillValid(Player player){return core.ready()&&lease.inventory.stillValid(player);}
        @Override public void clicked(int slot,int button,ClickType type,Player actor) {
            if(!(actor instanceof ServerPlayer player)||!lease.player.equals(player.getUUID())||!lease.open)return;
            if(type==ClickType.THROW){core.success(player,"&cCHEST_DROP_DISABLED_USE_INVENTORY");return;}
            try{
                core.check(player,"enderchest");core.flag(player,"can_use_enderchest","enderchest");
                if(core.requireTeam(player).id()!=lease.team||core.store.isFrozen(lease.team,player.getUUID()))throw new TeamsCore.Refusal("STALE_CHEST");
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
        core.store.beginOperation(op,lease.team,player.getUUID(),"CHEST",0,"PREPARED",payload.toString());
        boolean chestCommitted=false;
        try {
            mutation.run();String after=NativeChestCodec.encode(lease.inventory.snapshot(),core.server.registryAccess());ListTag afterPlayer=player.getInventory().save(new ListTag());ItemStack afterCursor=lease.menu.getCarried().copy();
            payload.addProperty("chestAfter",after);payload.addProperty("playerAfter",afterPlayer.toString());payload.addProperty("cursorAfter",cursor(afterCursor));payload.addProperty("generationAfter",lease.generation+1);
            core.store.updateOperation(op,"CHEST_INTENT",payload.toString());
            if(!core.store.saveChest(lease.team,1,after,lease.original,lease.generation)){
                restore(player,lease,before,beforePlayer,beforeCursor);core.store.updateOperation(op,"CANCELLED",payload.toString());throw new TeamsCore.Refusal("CHEST_GENERATION_CHANGED");
            }
            chestCommitted=true;lease.generation++;
            core.store.updateOperation(op,"PLAYER_INTENT",payload.toString());
            if(!savePlayerConfirmed(player,afterPlayer))throw new TeamsCore.Refusal("PLAYER_SAVE_UNVERIFIED");
            core.store.updateOperation(op,afterCursor.isEmpty()?"DONE":"CURSOR_HELD",payload.toString());
            if(lease.previousOp!=null)core.store.updateOperation(lease.previousOp,"DONE","{\"supersededBy\":\""+op+"\"}");
            lease.previousOp=afterCursor.isEmpty()?null:op;
        }catch(Exception error) {
            Optional<Operation> recorded=core.store.operation(op);
            if(recorded.isPresent()&&!recorded.get().phase().equals("CANCELLED")) {
                // SQL exceptions may be lost commit replies. Do not restore the player's source blindly.
                core.store.freezeOperation(op,"CHEST_"+error.getClass().getSimpleName());
                if(!chestCommitted)payload.addProperty("sqlCommitUnknown",true);
                payload.addProperty("cursorEscrow",cursor(lease.menu.getCarried()));core.store.updateOperation(op,"FROZEN",payload.toString());
                lease.menu.setCarried(ItemStack.EMPTY);lease.open=false;lease.inventory.revoke();leases.remove(lease.team,lease);
            }
            throw error;
        }
    }
    private void finish(ServerPlayer player,Lease lease) {
        if(!lease.open){leases.remove(lease.team,lease);return;}
        try {
            ItemStack carried=lease.menu==null?ItemStack.EMPTY:lease.menu.getCarried().copy();
            if(!carried.isEmpty()) {
                // A full inventory keeps the value in a recipient-owned receipt instead of an untracked world drop.
                String op=lease.previousOp!=null?lease.previousOp:UUID.randomUUID().toString();
                JsonObject payload=new JsonObject();payload.addProperty("playerBefore",player.getInventory().save(new ListTag()).toString());payload.addProperty("cursorBefore",cursor(carried));
                if(core.store.operation(op).isEmpty())core.store.beginOperation(op,lease.team,player.getUUID(),"CHEST",0,"PREPARED",payload.toString());
                core.store.updateOperation(op,"CLOSE_INTENT",payload.toString());
                ItemStack remaining=carried.copy();player.getInventory().add(remaining);lease.menu.setCarried(ItemStack.EMPTY);
                payload.addProperty("playerAfter",player.getInventory().save(new ListTag()).toString());payload.addProperty("cursorEscrow",cursor(remaining));
                core.store.updateOperation(op,"PLAYER_INTENT",payload.toString());
                if(!savePlayerConfirmed(player,player.getInventory().save(new ListTag())))throw new TeamsCore.Refusal("PLAYER_SAVE_UNVERIFIED");
                if(!remaining.isEmpty()){core.store.updateOperation(op,"ESCROW",payload.toString());core.success(player,"&eCHEST_ITEM_IN_ESCROW op="+op+" (/team claim)");}
                else core.store.updateOperation(op,"DONE",payload.toString());
                lease.previousOp=null;
            }
        }catch(Exception error){try{if(lease.previousOp!=null)core.store.freezeOperation(lease.previousOp,"CLOSE_UNKNOWN");}catch(Exception ignored){}core.refuse(player,error);if(lease.menu!=null)lease.menu.setCarried(ItemStack.EMPTY);}
        finally{lease.open=false;lease.inventory.revoke();leases.remove(lease.team,lease);}
    }
    public void claim(ServerPlayer player)throws Exception {
        core.check(player,"enderchest");
        for(Operation operation:core.store.pendingOperations()){
            if(!Objects.equals(operation.player(),player.getUUID())||!operation.phase().equals("ESCROW"))continue;
            JsonObject payload=JsonParser.parseString(operation.payload()).getAsJsonObject();ItemStack item=NativeChestCodec.decode(payload.get("cursorEscrow").getAsString(),core.server.registryAccess()).getFirst();
            if(item.isEmpty()){core.store.updateOperation(operation.opid(),"DONE",payload.toString());continue;}
            String before=player.getInventory().save(new ListTag()).toString();payload.addProperty("claimBefore",before);core.store.updateOperation(operation.opid(),"CLAIM_INTENT",payload.toString());
            try {
                player.getInventory().add(item);payload.addProperty("claimAfter",player.getInventory().save(new ListTag()).toString());payload.addProperty("cursorEscrow",cursor(item));
                core.store.updateOperation(operation.opid(),"PLAYER_INTENT",payload.toString());
                if(!savePlayerConfirmed(player,player.getInventory().save(new ListTag())))throw new TeamsCore.Refusal("PLAYER_SAVE_UNVERIFIED");
                core.store.updateOperation(operation.opid(),item.isEmpty()?"DONE":"ESCROW",payload.toString());
            }catch(Exception error){core.store.freezeOperation(operation.opid(),"ESCROW_CLAIM_UNKNOWN");throw error;}
        }
    }
    public void close(ServerPlayer player){Lease lease=leases.values().stream().filter(value->value.player.equals(player.getUUID())).findFirst().orElse(null);if(lease!=null){finish(player,lease);if(player.containerMenu==lease.menu)player.closeContainer();}}
    public void revoke(UUID player){ServerPlayer online=core.server.getPlayerList().getPlayer(player);if(online!=null)close(online);}
    public void revokeTeam(int team){Lease lease=leases.get(team);if(lease!=null)revoke(lease.player);}
    public void tick()throws Exception {for(Lease lease:new ArrayList<>(leases.values())){ServerPlayer player=core.server.getPlayerList().getPlayer(lease.player);if(player==null){lease.open=false;lease.inventory.revoke();leases.remove(lease.team,lease);continue;}Optional<Member> member=core.store.member(lease.player);if(member.isEmpty()||member.get().teamId()!=lease.team||!member.get().flag("can_use_enderchest")&&!Access.bypass(player,"enderchest"))close(player);}}
    private boolean savePlayerConfirmed(ServerPlayer player,ListTag expected)throws Exception {
        ((PlayerSaveAccess)core.server.getPlayerList()).pxcTeamsSave(player);
        Path root=core.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();Path target=root.resolve(player.getUUID()+".dat").normalize();if(!target.startsWith(root)||!Files.isRegularFile(target)||Files.isSymbolicLink(target))return false;
        try(FileChannel channel=FileChannel.open(target,StandardOpenOption.WRITE)){channel.force(true);}
        CompoundTag actual=NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap());return actual.getList("Inventory",Tag.TAG_COMPOUND).equals(expected);
    }
    private void restore(ServerPlayer player,Lease lease,List<ItemStack> chest,ListTag inventory,ItemStack cursor){for(int i=0;i<chest.size();i++)lease.inventory.getItems().set(i,chest.get(i).copy());player.getInventory().load(inventory);lease.menu.setCarried(cursor.copy());lease.menu.broadcastFullState();}
    private String cursor(ItemStack item){List<ItemStack> holder=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));holder.set(0,item.copy());return NativeChestCodec.encode(holder,core.server.registryAccess());}
    private static String sha(String input)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
    private static MenuType<?> type(int rows){return switch(rows){case 1->MenuType.GENERIC_9x1;case 2->MenuType.GENERIC_9x2;case 3->MenuType.GENERIC_9x3;case 4->MenuType.GENERIC_9x4;case 5->MenuType.GENERIC_9x5;default->MenuType.GENERIC_9x6;};}
}
