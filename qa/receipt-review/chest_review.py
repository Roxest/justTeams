from pathlib import Path
import subprocess

root=Path('F:/Pixelclub/justteams-fabric-pxc')
out=root/'qa/receipt-review'
source=(root/'src/main/java/com/pixelclub/teams/TeamChests.java').read_text(encoding='utf-8')
def method(a,b):
    start=source.index(a)
    return source[start:source.index(b,start)]
methods = method('    private void transaction(', '    public void close(')
methods += method('    private void append(', '    private static String sha(')
prefix=r'''import com.pixelclub.teams.store.*;
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
'''
suffix=r'''
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
'''
(out/'ChestReceiptReview.java').write_text(prefix+methods+suffix,encoding='utf-8')
cache=Path('C:/Users/Kraus/.gradle/caches/modules-2/files-2.1')
gson=next((cache/'com.google.code.gson/gson/2.11.0').rglob('gson-2.11.0.jar'))
h2=next((cache/'com.h2database/h2/2.1.214').rglob('h2-2.1.214.jar'))
jdk=Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin')
classes=out/'classes';classpath=f'{classes};{gson};{h2}'
subprocess.run([str(jdk/'javac.exe'),'-cp',classpath,'-d',str(classes),str(out/'ChestReceiptReview.java')],check=True)
r=subprocess.run([str(jdk/'java.exe'),'-cp',classpath,'ChestReceiptReview',str(out/'fixtures')],capture_output=True,text=True)
(out/'chest-output.txt').write_text(r.stdout+r.stderr,encoding='utf-8')
print(r.stdout+r.stderr)
raise SystemExit(r.returncode)
