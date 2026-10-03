import com.pixelclub.teams.store.*;
import java.sql.*;
import java.util.*;

/** H2-only reproduction of the exact boot quarantine loop and claim eligibility predicate. */
public final class ChestEscrowRestart {
    public static void main(String[] args) throws Exception {
        String url="jdbc:h2:mem:moneyreview_escrow;MODE=MySQL;DB_CLOSE_DELAY=-1";
        TeamStore store=new TeamStore(()->DriverManager.getConnection(url),"donut_");
        store.createFixtureSchema();store.provisionAdditiveTables();
        UUID player=UUID.randomUUID();Team team=store.create("Escrow Fixture","EF",player,"Fixture","isolated");
        store.beginOperation("escrow_fixture_01",team.id(),player,"CHEST",0,"ESCROW","{\"cursorEscrow\":\"fixture item\"}");
        long claimableBefore=store.pendingOperations().stream().filter(op->Objects.equals(op.player(),player)&&op.phase().equals("ESCROW")).count();
        for(TeamStore.Operation pending:store.pendingOperations())store.freezeOperation(pending.opid(),"RESTART_PENDING_"+pending.phase());
        long claimableAfter=store.pendingOperations().stream().filter(op->Objects.equals(op.player(),player)&&op.phase().equals("ESCROW")).count();
        System.out.println("claimableBefore="+claimableBefore+" claimableAfter="+claimableAfter+" phase="+store.operation("escrow_fixture_01").orElseThrow().phase()+" frozen="+store.isFrozen(team.id(),player));
        if(claimableBefore!=1||claimableAfter!=0||!store.isFrozen(team.id(),player))throw new AssertionError("candidate behavior changed");
    }
}
