import com.pixelclub.teams.store.*;
import com.pixelclub.teams.store.TeamStore.Operation;
import com.google.gson.*;
import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.sql.*;
public final class ChestReceiptReview {
 static class ItemStack {int count;ItemStack(int n){count=n;}ItemStack copy(){return new ItemStack(count);}boolean isEmpty(){return count==0;}static final ItemStack EMPTY=new ItemStack(0);}
 static class ListTag {int count;public String toString(){return Integer.toString(count);}}
 static class Inventory {int count,capacity;Inventory(int n,int c){count=n;capacity=c;}ListTag save(ListTag x){x.count=count;return x;}boolean add(ItemStack item){int n=Math.min(item.count,Math.max(0,capacity-count));count+=n;item.count-=n;return item.isEmpty();}}
 static class Connection {boolean disconnected;void disconnect(Object t){disconnected=true;}}
 static class ServerPlayer {final UUID id=UUID.randomUUID();final Inventory inventory;final Connection connection=new Connection();int savedCount;ServerPlayer(int n,int c){inventory=new Inventory(n,c);savedCount=n;}Inventory getInventory(){return inventory;}UUID getUUID(){return id;}}
 static class Container {final List<ItemStack> items=new ArrayList<>();boolean revoked;Container(int n){items.add(new ItemStack(n));}List<ItemStack> snapshot(){return items.stream().map(ItemStack::copy).toList();}void revoke(){revoked=true;}}
 static class Menu {ItemStack carried=ItemStack.EMPTY;ItemStack getCarried(){return carried;}void setCarried(ItemStack s){carried=s;}}
 static class Lease {int team;UUID player;long generation;String original,previousOp;boolean open=true;Container inventory;Menu menu=new Menu();}
 static class NativeChestCodec {static final int MAX_JSON_CHARS=16777216;static String encode(List<ItemStack> items,Object r){return Integer.toString(items.getFirst().count);}static List<ItemStack> decode(String x,Object r){return List.of(new ItemStack(Integer.parseInt(x)));}}
 static class Server {Object registryAccess(){return null;}}
 static class TeamsCore {static class Refusal extends RuntimeException{Refusal(String s){super(s);}}final TeamStore store;final Server server=new Server();TeamsCore(TeamStore s){store=s;}void check(ServerPlayer p,String a){}void success(ServerPlayer p,String x){}void refuse(ServerPlayer p,Exception e){}}
 static class Texts {static Object of(String x){return x;}}
 final TeamsCore core;final Path wal;final Map<Integer,Lease> leases=new HashMap<>();final Set<Integer> frozenTeams=new HashSet<>();final Set<UUID> frozenPlayers=new HashSet<>();
 boolean saveAllowed=true;
 ChestReceiptReview(TeamStore s,Path p){core=new TeamsCore(s);wal=p;}
 boolean savePlayerConfirmed(ServerPlayer p,ListTag expected){if(!saveAllowed)return false;p.savedCount=p.inventory.count;return p.savedCount==expected.count;}
 String cursor(ItemStack s){return Integer.toString(s.count);}
 void restore(ServerPlayer p,Lease l,List<ItemStack> c,ListTag i,ItemStack u){l.inventory.items.clear();l.inventory.items.addAll(c.stream().map(ItemStack::copy).toList());p.inventory.count=i.count;l.menu.setCarried(u.copy());}
 String phase(String op)throws Exception{return core.store.operation(op).orElseThrow().phase();}
 static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
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
                ItemStack remaining=carried.copy();player.getInventory().add(remaining);lease.menu.setCarried(ItemStack.EMPTY);
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
        core.check(player,"enderchest");
        for(Operation operation:core.store.pendingOperations()){
            if(!Objects.equals(operation.player(),player.getUUID())||!operation.phase().equals("ESCROW"))continue;
            JsonObject payload=JsonParser.parseString(operation.payload()).getAsJsonObject();ItemStack item=NativeChestCodec.decode(payload.get("cursorEscrow").getAsString(),core.server.registryAccess()).getFirst();
            if(item.isEmpty()){core.store.updateOperation(operation.opid(),"DONE",payload.toString());continue;}
            String before=player.getInventory().save(new ListTag()).toString();payload.addProperty("claimBefore",before);core.store.updateOperation(operation.opid(),"CLAIM_INTENT",payload.toString());
            append(operation.opid(),operation.teamId(),player.getUUID(),"CLAIM_INTENT",payload);
            try {
                player.getInventory().add(item);payload.addProperty("claimAfter",player.getInventory().save(new ListTag()).toString());payload.addProperty("cursorEscrow",cursor(item));
                core.store.updateOperation(operation.opid(),"PLAYER_INTENT",payload.toString());
                if(!savePlayerConfirmed(player,player.getInventory().save(new ListTag())))throw new TeamsCore.Refusal("PLAYER_SAVE_UNVERIFIED");
                core.store.updateOperation(operation.opid(),item.isEmpty()?"DONE":"ESCROW",payload.toString());
                append(operation.opid(),operation.teamId(),player.getUUID(),item.isEmpty()?"DONE":"ESCROW",payload);
            }catch(Exception error){quarantine(player,null,operation.teamId(),operation.opid(),payload,error);throw error;}
        }
    }
    private void append(String op,int team,UUID player,String phase,JsonObject payload)throws Exception {
        Files.createDirectories(wal.toAbsolutePath().getParent());JsonObject record=new JsonObject();record.addProperty("opid",op);record.addProperty("team",team);record.addProperty("player",player.toString());record.addProperty("phase",phase);record.addProperty("at",System.currentTimeMillis());record.add("payload",payload.deepCopy());
        ByteBuffer bytes=ByteBuffer.wrap((record+System.lineSeparator()).getBytes(StandardCharsets.UTF_8));try(FileChannel channel=FileChannel.open(wal,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}
    }
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
    private void restoreQuarantine()throws Exception {
        if(!Files.exists(wal))return;Map<String,JsonObject> latest=new LinkedHashMap<>();
        try(var reader=Files.newBufferedReader(wal,StandardCharsets.UTF_8)){String line;while((line=reader.readLine())!=null){if(line.isBlank())continue;JsonObject record=JsonParser.parseString(line).getAsJsonObject();latest.put(record.get("opid").getAsString(),record);}}
        for(JsonObject record:latest.values()){String phase=record.get("phase").getAsString();if(!Set.of("DONE","CANCELLED","ESCROW").contains(phase)){UUID actor=UUID.fromString(record.get("player").getAsString());int team=record.get("team").getAsInt();frozenPlayers.add(actor);if(team>0)frozenTeams.add(team);}}
    }

 public static void main(String[] args)throws Exception {
  Path root=Paths.get(args[0]);Files.createDirectories(root);Path wal=root.resolve("chest-normal.jsonl");Files.deleteIfExists(wal);
  TeamStore store=new TeamStore(()->DriverManager.getConnection("jdbc:h2:mem:chestreceipts;MODE=MySQL;DB_CLOSE_DELAY=-1"),"donut_");store.createFixtureSchema();store.provisionAdditiveTables();
  ServerPlayer player=new ServerPlayer(3,3);Team team=store.create("ReceiptFixture","RF",player.id,"Fixture","local");
  check(store.saveChest(team.id(),1,"64",null,0),"seed chest");
  ChestReceiptReview test=new ChestReceiptReview(store,wal);Lease lease=new Lease();lease.team=team.id();lease.player=player.id;lease.generation=1;lease.inventory=new Container(64);test.leases.put(team.id(),lease);
  test.transaction(player,lease,()->{lease.menu.carried=lease.inventory.items.getFirst().copy();lease.inventory.items.set(0,ItemStack.EMPTY);});
  String op=lease.previousOp;check(test.phase(op).equals("CURSOR_HELD")&&player.savedCount==3&&store.chest(team.id()).orElseThrow().inventoryJson().equals("0"),"withdraw cursor receipt");
  test.finish(player,lease);check(test.phase(op).equals("ESCROW")&&player.inventory.count==3&&lease.menu.carried.isEmpty()&&!lease.open,"full inventory escrow");
  for(Operation pending:store.pendingOperations())if(!(pending.kind().equals("CHEST")&&pending.phase().equals("ESCROW")))store.freezeOperation(pending.opid(),"RESTART_PENDING_"+pending.phase());
  ChestReceiptReview restart=new ChestReceiptReview(store,wal);restart.restoreQuarantine();check(!restart.frozenPlayers.contains(player.id)&&!store.isFrozen(team.id(),player.id),"restart claim permitted");
  player.inventory.capacity=13;restart.claim(player);check(restart.phase(op).equals("ESCROW")&&player.inventory.count==13&&player.savedCount==13,"partial claim inventory receipt");
  JsonObject payload=JsonParser.parseString(store.operation(op).orElseThrow().payload()).getAsJsonObject();check(payload.get("cursorEscrow").getAsString().equals("54"),"partial claim escrow count");
  player.inventory.capacity=100;restart.claim(player);check(restart.phase(op).equals("DONE")&&player.inventory.count==67&&player.savedCount==67&&store.pendingOperations().isEmpty(),"final claim done");
  ChestReceiptReview finalRestart=new ChestReceiptReview(store,wal);finalRestart.restoreQuarantine();check(finalRestart.frozenPlayers.isEmpty()&&finalRestart.frozenTeams.isEmpty(),"final WAL state settled");
  System.out.println("Chest exact-method/H2 fixture: CURSOR_HELD -> ESCROW -> restart -> partial ESCROW(54) -> DONE; inventory=67 saved=67 pending=0 frozen=0");
  Path chainWal=root.resolve("chest-chain.jsonl");Files.deleteIfExists(chainWal);ServerPlayer receiver=new ServerPlayer(0,100);Team chainTeam=store.create("ChainFixture","CF",receiver.id,"Receiver","local");
  check(store.saveChest(chainTeam.id(),1,"17",null,0),"seed chain");ChestReceiptReview chain=new ChestReceiptReview(store,chainWal);Lease next=new Lease();next.team=chainTeam.id();next.player=receiver.id;next.generation=1;next.inventory=new Container(17);chain.leases.put(next.team,next);
  chain.transaction(receiver,next,()->{next.menu.carried=next.inventory.items.getFirst().copy();next.inventory.items.set(0,ItemStack.EMPTY);});String held=next.previousOp;
  chain.transaction(receiver,next,()->{receiver.inventory.add(next.menu.getCarried());next.menu.setCarried(ItemStack.EMPTY);});
  check(next.previousOp==null&&chain.phase(held).equals("DONE")&&store.pendingOperations().isEmpty()&&receiver.savedCount==17,"paired completion chain");chain.finish(receiver,next);
  System.out.println("Chest exact-method/H2 fixture: previous cursor op superseded -> DONE; inventory=17 saved=17 pending=0");
 }
}
