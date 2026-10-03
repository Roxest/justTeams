from pathlib import Path
import subprocess

root = Path('F:/Pixelclub/justteams-fabric-pxc')
out = root / 'qa/moneyreview'
source = (root / 'src/main/java/com/pixelclub/teams/TeamChests.java').read_text(encoding='utf-8')
start = source.index('    private void transaction(')
end = source.index('    private void finish(', start)
method = source[start:end]
prefix = r'''import java.util.*;
import com.google.gson.JsonObject;
public class ChestFailureControlFlow {
  static class ItemStack { int count; ItemStack(int n){count=n;} ItemStack copy(){return new ItemStack(count);} boolean isEmpty(){return count==0;} static final ItemStack EMPTY=new ItemStack(0); }
  static class ListTag { public String toString(){return "[]";} }
  static class Inventory { ListTag save(ListTag x){return x;} }
  static class ServerPlayer { final Inventory inventory=new Inventory(); final UUID id=UUID.randomUUID(); Inventory getInventory(){return inventory;} UUID getUUID(){return id;} }
  static class NativeChestCodec { static final int MAX_JSON_CHARS=16777216; static String encode(List<ItemStack> x,Object r){return Integer.toString(x.getFirst().count);} }
  static class Menu { ItemStack carried=ItemStack.EMPTY; ItemStack getCarried(){return carried;} void setCarried(ItemStack x){carried=x;} }
  static class ChestSessionContainer { final List<ItemStack> items=new ArrayList<>(List.of(new ItemStack(64))); boolean revoked; List<ItemStack> snapshot(){return items.stream().map(ItemStack::copy).toList();} void revoke(){revoked=true;} }
  static class Lease { int team=1; long generation=0; String original; boolean open=true; String previousOp; ChestSessionContainer inventory=new ChestSessionContainer(); Menu menu=new Menu(); }
  record Operation(String phase){}
  static class Store { Operation row; int durableChest=64; boolean failureInRead;
    void beginOperation(String o,int t,UUID p,String k,int a,String ph,String pl){row=new Operation(ph);}
    void updateOperation(String o,String ph,String pl){row=new Operation(ph);}
    boolean saveChest(int t,int v,String a,String h,long g)throws Exception{throw new java.sql.SQLException("fixture unavailable during save");}
    Optional<Operation> operation(String o)throws Exception{if(failureInRead)throw new java.sql.SQLException("fixture unavailable during lookup");return Optional.ofNullable(row);}
    void freezeOperation(String o,String r)throws Exception{throw new java.sql.SQLException("fixture unavailable during quarantine");}
  }
  static class Server { Object registryAccess(){return null;} }
  static class TeamsCore { static class Refusal extends RuntimeException {Refusal(String s){super(s);}} final Store store=new Store(); final Server server=new Server(); }
  final TeamsCore core=new TeamsCore(); final Map<Integer,Lease> leases=new HashMap<>();
  String cursor(ItemStack x){return Integer.toString(x.count);}
  boolean savePlayerConfirmed(ServerPlayer p,ListTag i){return true;}
  void restore(ServerPlayer p,Lease l,List<ItemStack> c,ListTag i,ItemStack u){throw new AssertionError("unused fixture branch");}
'''
suffix = r'''
  public static void main(String[] args)throws Exception {
    for(boolean lookupFailure:new boolean[]{false,true}) {
      ChestFailureControlFlow test=new ChestFailureControlFlow();
      test.core.store.failureInRead=lookupFailure;
      ServerPlayer player=new ServerPlayer(); Lease lease=new Lease(); test.leases.put(lease.team,lease);
      try {test.transaction(player,lease,()->{lease.menu.carried=lease.inventory.items.getFirst();lease.inventory.items.set(0,ItemStack.EMPTY);});}
      catch(Exception expected) {}
      boolean vulnerable=lease.open && !lease.inventory.revoked && lease.menu.carried.count==64 && test.core.store.durableChest==64 && test.leases.containsKey(1);
      System.out.println("failure="+(lookupFailure?"operation-read":"freeze-write")+" leaseOpen="+lease.open+" revoked="+lease.inventory.revoked+" cursor="+lease.menu.carried.count+" durableChest="+test.core.store.durableChest+" phase="+test.core.store.row.phase()+" vulnerable="+vulnerable);
      if(!vulnerable)throw new AssertionError("candidate no longer reproduces; update report");
    }
  }
}
'''
(out / 'ChestFailureControlFlow.java').write_text(prefix + method + suffix, encoding='utf-8')
gson = next(Path('C:/Users/Kraus/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson').rglob('gson-*.jar'))
jdk = Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin')
subprocess.run([str(jdk / 'javac.exe'), '-cp', str(gson), '-d', str(out), str(out / 'ChestFailureControlFlow.java')], check=True)
result = subprocess.run([str(jdk / 'java.exe'), '-cp', f'{out};{gson}', 'ChestFailureControlFlow'], check=True, capture_output=True, text=True)
(out / 'chest-failure-output.txt').write_text(result.stdout, encoding='utf-8')
print(result.stdout, end='')
mc = Path('C:/Users/Kraus/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar')
dump = subprocess.run([str(jdk / 'javap.exe'), '-classpath', str(mc), '-c', '-p', 'net.minecraft.world.inventory.AbstractContainerMenu'], check=True, capture_output=True, text=True).stdout
lines = dump.splitlines()
first = next(i for i, line in enumerate(lines) if '595: sipush' in line and '-999' in line)
last = next(i for i in range(first + 1, len(lines)) if '658:' in lines[i])
(out / 'vanilla-drop-bytecode.txt').write_text('MC 1.21.1 official Mojmaps AbstractContainerMenu.doClick\n' + '\n'.join(lines[first:last]) + '\n', encoding='utf-8')
assert any('Player.drop:' in line for line in lines[first:last])
print('vanilla PICKUP outside-slot branch calls Player.drop and empties cursor: confirmed by pinned bytecode')
