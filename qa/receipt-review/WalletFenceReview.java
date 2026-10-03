import com.pixelclub.teams.money.WalletFence;
import java.util.*;
import java.util.concurrent.*;
import net.impactdev.impactor.api.economy.accounts.Account;

public final class WalletFenceReview {
    public record FixtureAccount(UUID owner,boolean virtual) implements Account {}
    public static void main(String[] args)throws Exception {
        int n=0;
        FixtureAccount first=new FixtureAccount(UUID.randomUUID(),false);
        long anchor=WalletFence.checkpoint();var ticket=WalletFence.reserve(first);
        if(WalletFence.quiet(first.owner(),false)||WalletFence.awaitQuiet(first.owner(),false,anchor,1))throw new AssertionError("reserved enqueue invisible");n++;
        var save=new CompletableFuture<Void>();WalletFence.bind(ticket,save);save.complete(null);
        if(!WalletFence.awaitQuiet(first.owner(),false,anchor,1))throw new AssertionError("success not released");n++;
        anchor=WalletFence.checkpoint();ticket=WalletFence.reserve(first);WalletFence.fail(ticket,new IllegalStateException("fixture"));
        if(WalletFence.awaitQuiet(first.owner(),false,anchor,1))throw new AssertionError("new failure accepted");n++;
        anchor=WalletFence.checkpoint();ticket=WalletFence.reserve(first);WalletFence.bind(ticket,CompletableFuture.completedFuture(null));
        if(!WalletFence.awaitQuiet(first.owner(),false,anchor,1))throw new AssertionError("past failure poisons fresh op");n++;
        FixtureAccount virtual=new FixtureAccount(first.owner(),true);var virtualTicket=WalletFence.reserve(virtual);
        if(!WalletFence.quiet(first.owner(),false)||WalletFence.quiet(first.owner(),true))throw new AssertionError("virtual separation");
        WalletFence.fail(virtualTicket,new IllegalStateException("fixture"));n++;
        System.out.println("WalletFence actual-source synthetic checks: passed="+n+" enqueue/success/new-failure/checkpoint/virtual-identity");
    }
}
