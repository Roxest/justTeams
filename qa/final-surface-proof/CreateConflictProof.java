import com.pixelclub.teams.money.BankService;
import com.pixelclub.teams.money.DurableEconomy;
import com.pixelclub.teams.store.ConnectionFactory;
import com.pixelclub.teams.store.Team;
import com.pixelclub.teams.store.TeamStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated synthetic proof: real current JDBC store/bank; no MC/Core/Auth/real wallet. */
public final class CreateConflictProof {
    static final class Wallet implements DurableEconomy {
        final UUID actor;
        double memory=100, durable=100;
        Wallet(UUID actor){this.actor=actor;}
        public boolean available(){return true;}
        public boolean hasAccount(UUID id){return actor.equals(id);}
        public double balance(UUID id){return memory;}
        public boolean change(UUID id,double delta,String reason){memory+=delta;return true;}
        public boolean flushConfirmed(UUID id,double expected){durable=memory;return durable==expected;}
    }
    static final class Fixture {
        final AtomicBoolean failRollback=new AtomicBoolean();
        final UUID owner=UUID.randomUUID(),actor=UUID.randomUUID();
        final ConnectionFactory factory;
        final TeamStore store;
        final Wallet wallet=new Wallet(actor);
        final BankService bank;
        final Team existing;
        Fixture()throws Exception{
            String url="jdbc:h2:mem:surface_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
            factory=()->{
                Connection delegate=DriverManager.getConnection(url);
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("rollback")&&failRollback.get())throw new SQLException("synthetic rollback refusal");
                    try{return method.invoke(delegate,args);}catch(InvocationTargetException e){throw e.getCause();}
                });
            };
            store=new TeamStore(factory,"donut_");store.createFixtureSchema();store.validateSchema();
            existing=store.create("Existing","EX",owner,"Owner","fixture");
            Path folder=Files.createTempDirectory(Path.of("qa/final-surface-proof"),"runtime-");
            bank=new BankService(store,wallet,folder.resolve("intent.jsonl"),1000);
        }
    }
    static void need(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    static void run(String label,String name,String tag,boolean ownerConflict,boolean rollbackFailure,boolean sqlFailure)throws Exception{
        Fixture f=new Fixture();
        if(ownerConflict){f.store.invite(f.existing.id(),f.owner,f.actor,System.currentTimeMillis()+60000);need(f.store.accept(f.actor,f.existing.id(),10),"actor setup");}
        if(sqlFailure)try(Connection c=f.factory.open();var s=c.createStatement()){s.execute("ALTER TABLE donut_teams ADD CONSTRAINT synthetic_fail CHECK(name <> 'StorageFail')");}
        String op="create_"+label;
        need(f.bank.charge(op,f.actor,25,false).success(),"charge");
        f.failRollback.set(rollbackFailure);
        boolean changed=false,unknown=false;
        // Same typed callback decision as TeamsCore.create, without the MC wrapper.
        try{
            try{changed=f.store.create(name,tag,f.actor,"Actor","fixture")!=null;}
            catch(TeamStore.CreateConflict conflict){if(conflict.getSuppressed().length>0)throw conflict;changed=false;}
        }catch(Exception failure){unknown=true;}
        finally{f.failRollback.set(false);}
        if(unknown)f.bank.unknownCharge(op,"SYNTHETIC_EXECUTION_UNKNOWN");
        else need(f.bank.finishCharge(op,changed),"settle");
        String phase=f.store.operation(op).orElseThrow().phase();
        if(rollbackFailure||sqlFailure){
            need(unknown&&phase.equals("FROZEN")&&f.wallet.memory==75&&f.wallet.durable==75,"ambiguous path must freeze, never refund");
        }else{
            need(!changed&&!unknown&&phase.equals("COMPENSATED"),"known conflict compensation");
            need(f.wallet.memory==100&&f.wallet.durable==100,"wallet conservation");
            need(f.store.pendingOperations().isEmpty(),"no pending intent");
            need(!f.store.isFrozen(ownerConflict?f.existing.id():0,f.actor),"not frozen");
        }
        need(f.store.rows("teams",null,null).size()==1,"no half-created team");
        need(f.store.member(f.actor).isPresent()==ownerConflict,"membership unchanged");
        System.out.println("PASS "+label+" phase="+phase+" wallet="+f.wallet.durable+" teams=1");
    }
    public static void main(String[] args)throws Exception{
        run("name","Existing","NW",false,false,false);
        run("tag","NewTeam","EX",false,false,false);
        run("owner","NewTeam","NW",true,false,false);
        run("rollback_unknown","Existing","NW",false,true,false);
        run("sql_unknown","StorageFail","NW",false,false,true);
    }
}
