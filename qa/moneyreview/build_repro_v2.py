from pathlib import Path
import subprocess

root = Path('F:/Pixelclub/justteams-fabric-pxc')
out = root / 'qa/moneyreview'
source = (root / 'src/main/java/com/pixelclub/teams/TeamChests.java').read_text(encoding='utf-8')
core_source = (root / 'src/main/java/com/pixelclub/teams/TeamsCore.java').read_text(encoding='utf-8')
def method(first, following):
    start = source.index(first)
    return source[start:source.index(following, start)]
transaction = method('    private void transaction(', '    private void finish(')
append = method('    private void append(', '    private void quarantine(')
quarantine = method('    private void quarantine(', '    private void restoreQuarantine(')
joined_start = core_source.index('    public synchronized void joined(')
joined = core_source[joined_start:core_source.index('    public synchronized void departed(', joined_start)]
prefix = r'''import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import com.google.gson.*;
public class ChestFailureControlFlowV2 {
  static class ItemStack { int count; ItemStack(int n){count=n;} ItemStack copy(){return new ItemStack(count);} boolean isEmpty(){return count==0;} static final ItemStack EMPTY=new ItemStack(0); }
  static class ListTag { int count; public String toString(){return Integer.toString(count);} }
  static class Inventory { int count=3; ListTag save(ListTag x){x.count=count;return x;} void clearContent(){count=0;} }
  static class Connection { boolean disconnected; void disconnect(Object text){disconnected=true;} }
  static class Profile { String getName(){return "Fixture";} }
  static class ServerPlayer { final Inventory inventory=new Inventory(); final UUID id=UUID.randomUUID(); final Connection connection=new Connection(); Inventory getInventory(){return inventory;} UUID getUUID(){return id;} Profile getGameProfile(){return new Profile();} }
  static class NativeChestCodec { static final int MAX_JSON_CHARS=16777216; static String encode(List<ItemStack> x,Object r){return Integer.toString(x.getFirst().count);} }
  static class Texts { static Object of(String x){return x;} }
  static class Menu { ItemStack carried=ItemStack.EMPTY; ItemStack getCarried(){return carried;} void setCarried(ItemStack x){carried=x;} }
  static class ChestSessionContainer { final List<ItemStack> items=new ArrayList<>(List.of(new ItemStack(64))); boolean revoked; List<ItemStack> snapshot(){return items.stream().map(ItemStack::copy).toList();} void revoke(){revoked=true;} }
  static class Lease { int team=1; long generation=0; String original; boolean open=true; String previousOp; ChestSessionContainer inventory=new ChestSessionContainer(); Menu menu=new Menu(); }
  record Operation(String phase){}
  record Team(int id){}
  static class Store { Operation row; int durableChest=64; boolean rejectCas,cancelFailure; int lookupCalls;
    void beginOperation(String o,int t,UUID p,String k,int a,String ph,String pl){row=new Operation(ph);}
    void updateOperation(String o,String ph,String pl)throws Exception{if(cancelFailure&&ph.equals("CANCELLED"))throw new java.sql.SQLException("fixture cancellation unavailable");row=new Operation(ph);}
    boolean saveChest(int t,int v,String a,String h,long g)throws Exception{if(rejectCas)return false;throw new java.sql.SQLException("fixture unavailable during save");}
    Optional<Operation> operation(String o)throws Exception{lookupCalls++;throw new java.sql.SQLException("fixture unavailable during lookup");}
    void freezeOperation(String o,String r)throws Exception{throw new java.sql.SQLException("fixture unavailable during quarantine");}
    Optional<Team> teamOf(UUID p){return Optional.of(new Team(1));}
    void updateRows(String t,String k,UUID p,Map<String,Object> v){}
    List<Map<String,Object>> rows(String t,String k,UUID p){return List.of();}
    void insertRow(String t,Map<String,Object> v){}
  }
  static class Server { Object registryAccess(){return null;} }
  static class TeamsCore { static class Refusal extends RuntimeException {Refusal(String s){super(s);}} final Store store=new Store(); final Server server=new Server(); void success(ServerPlayer p,String s){} }
  final TeamsCore core=new TeamsCore(); final Store store=core.store; final ChestFailureControlFlowV2 chests=this;
  final Map<Integer,Lease> leases=new HashMap<>(); final Set<Integer> frozenTeams=new HashSet<>(); final Set<UUID> frozenPlayers=new HashSet<>(); final Path wal;
  ChestFailureControlFlowV2(Path x){wal=x;}
  boolean quarantined(UUID p,int t){return frozenPlayers.contains(p)||t>0&&frozenTeams.contains(t);}
  void refresh(UUID p){}
  String cursor(ItemStack x){return Integer.toString(x.count);}
  boolean savePlayerConfirmed(ServerPlayer p,ListTag i){return true;}
  void restore(ServerPlayer p,Lease l,List<ItemStack> c,ListTag i,ItemStack u){l.inventory.items.clear();for(ItemStack x:c)l.inventory.items.add(x.copy());p.inventory.count=i.count;l.menu.setCarried(u.copy());}
'''
suffix = r'''
  public static void main(String[] args)throws Exception {
    Path root=Paths.get(args[0]);Files.createDirectories(root);
    for(int scenario=0;scenario<3;scenario++) {
      Path wal=root.resolve("scenario-"+scenario+".jsonl");Files.deleteIfExists(wal);
      ChestFailureControlFlowV2 test=new ChestFailureControlFlowV2(wal);
      test.store.rejectCas=scenario>0;test.store.cancelFailure=scenario==2;
      ServerPlayer player=new ServerPlayer();Lease lease=new Lease();test.leases.put(lease.team,lease);
      try {test.transaction(player,lease,()->{lease.menu.carried=lease.inventory.items.getFirst();lease.inventory.items.set(0,ItemStack.EMPTY);});}
      catch(Exception expected) {}
      List<String> lines=Files.readAllLines(wal);JsonObject last=JsonParser.parseString(lines.getLast()).getAsJsonObject();
      boolean protectedNow=!lease.open&&lease.inventory.revoked&&lease.menu.carried.isEmpty()&&player.inventory.count==0&&player.connection.disconnected&&test.frozenTeams.contains(1)&&test.frozenPlayers.contains(player.id);
      if(scenario==1) {
        if(!lease.open||lease.inventory.revoked||lease.menu.carried.count!=0||player.inventory.count!=3||!last.get("phase").getAsString().equals("CANCELLED"))throw new AssertionError("CAS false did not restore safely");
      }else if(!protectedNow)throw new AssertionError("failure cleanup remains bypassable");
      System.out.println("scenario="+scenario+" leaseOpen="+lease.open+" cursor="+lease.menu.carried.count+" playerInventory="+player.inventory.count+" disconnected="+player.connection.disconnected+" walPhase="+last.get("phase").getAsString()+" sqlLookups="+test.store.lookupCalls);
      if(scenario==0) {
        ServerPlayer innocent=new ServerPlayer();innocent.inventory.count=11;test.joined(innocent);
        boolean ownReceipt=Files.readString(wal).contains(innocent.id.toString());
        System.out.println("innocentTeamMember inventoryAfterJoin="+innocent.inventory.count+" disconnected="+innocent.connection.disconnected+" ownReceipt="+ownReceipt);
        if(innocent.inventory.count!=0||!innocent.connection.disconnected||ownReceipt)throw new AssertionError("innocent confiscation trigger changed; update review");
      }
    }
  }
}
'''
(out / 'ChestFailureControlFlowV2.java').write_text(prefix + transaction + append + quarantine + joined + suffix, encoding='utf-8')
gson = next(Path('C:/Users/Kraus/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson').rglob('gson-*.jar'))
jdk = Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin')
subprocess.run([str(jdk / 'javac.exe'), '-cp', str(gson), '-d', str(out), str(out / 'ChestFailureControlFlowV2.java')], check=True)
result = subprocess.run([str(jdk / 'java.exe'), '-cp', f'{out};{gson}', 'ChestFailureControlFlowV2', str(out / 'fixture-wal')], check=True, capture_output=True, text=True)
(out / 'chest-failure-output-v2.txt').write_text(result.stdout, encoding='utf-8')
print(result.stdout, end='')
