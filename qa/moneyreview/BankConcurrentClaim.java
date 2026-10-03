import com.pixelclub.teams.store.*;
import com.pixelclub.teams.money.*;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Two independent authorities, one H2 memory database and one synthetic durable wallet. */
public final class BankConcurrentClaim {
    private static final class Wallet implements DurableEconomy {
        private final AtomicReference<Double> value=new AtomicReference<>(100d);
        private final AtomicInteger reads=new AtomicInteger(),changes=new AtomicInteger();
        private final CyclicBarrier initialReads=new CyclicBarrier(2);
        public boolean available(){return true;}
        public boolean hasAccount(UUID owner){return true;}
        public double balance(UUID owner)throws Exception {
            double snapshot=value.get();
            if(reads.incrementAndGet()<=2)initialReads.await(5,TimeUnit.SECONDS);
            return snapshot;
        }
        public boolean change(UUID owner,double delta,String reason){changes.incrementAndGet();value.updateAndGet(x->x+delta);return true;}
        public boolean flushConfirmed(UUID owner,double expected){return PackEconomy.same(value.get(),expected);}
    }
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        int trials=0;
        for(String scenario:List.of("deposit","withdraw","charge-personal","charge-bank-first")) {
            for(int repeat=0;repeat<10;repeat++) {
                String url="jdbc:h2:mem:claims_"+scenario.replace('-','_')+repeat+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000";
                ConnectionFactory factory=()->DriverManager.getConnection(url);
                TeamStore first=new TeamStore(factory,"donut_");first.createFixtureSchema();first.provisionAdditiveTables();
                UUID player=UUID.randomUUID();Team team=first.create("Fixture","FX",player,"Fixture","isolated");
                if(scenario.equals("withdraw")||scenario.equals("charge-bank-first"))try(Connection c=factory.open();PreparedStatement s=c.prepareStatement("UPDATE donut_teams SET balance=20 WHERE id=?")){s.setInt(1,team.id());s.executeUpdate();}
                Wallet wallet=new Wallet();
                BankService a=new BankService(first,wallet,root.resolve(scenario+repeat+"-a.jsonl"),1000);
                BankService b=new BankService(new TeamStore(factory,"donut_"),wallet,root.resolve(scenario+repeat+"-b.jsonl"),1000);
                String op="concurrent_claim_"+scenario.replace('-','_')+repeat;
                Callable<BankService.Result> taskA=()->scenario.startsWith("charge")?a.charge(op,player,10,scenario.equals("charge-bank-first")):a.transfer(op,player,scenario.equals("deposit"),10,0,false);
                Callable<BankService.Result> taskB=()->scenario.startsWith("charge")?b.charge(op,player,10,scenario.equals("charge-bank-first")):b.transfer(op,player,scenario.equals("deposit"),10,0,false);
                ExecutorService executor=Executors.newFixedThreadPool(2);
                try {
                    Future<BankService.Result> x=executor.submit(taskA),y=executor.submit(taskB);
                    BankService.Result rx=x.get(10,TimeUnit.SECONDS),ry=y.get(10,TimeUnit.SECONDS);
                    int expectedCalls=scenario.equals("charge-bank-first")?0:1;
                    double expectedPlayer=scenario.equals("withdraw")?110:scenario.equals("charge-bank-first")?100:90;
                    double expectedBank=scenario.equals("charge-personal")?0:10;
                    if(wallet.changes.get()!=expectedCalls||!PackEconomy.same(wallet.value.get(),expectedPlayer)||!PackEconomy.same(first.team(team.id()).orElseThrow().balance(),expectedBank)||(!rx.success()&&!ry.success()))throw new AssertionError(scenario+": calls="+wallet.changes+" player="+wallet.value+" a="+rx.code()+" b="+ry.code());
                    trials++;
                }finally{executor.shutdownNow();}
            }
            System.out.println(scenario+" n=10 authorities=2 providerCallsPerOp="+(scenario.equals("charge-bank-first")?0:1)+" conserved=true PASS");
        }
        System.out.println("trials="+trials+" requestPairs="+trials+" PASS");
    }
}
