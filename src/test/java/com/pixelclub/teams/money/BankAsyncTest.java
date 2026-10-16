package com.pixelclub.teams.money;

import com.pixelclub.teams.store.ConnectionFactory;
import com.pixelclub.teams.store.Team;
import com.pixelclub.teams.store.TeamStore;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K-1 (+k1a1): bank deposit / withdraw under pxc-ledger with the player's leg asked off the server thread. The fake
 * ledger answers only when the test releases an ask (its "ledger thread"); {@link Main} stands in for server.execute.
 */
class BankAsyncTest {
    @TempDir Path temporary;
    private ConnectionFactory factory;
    private TeamStore store;
    private Team team;
    private UUID owner;
    private Ledger ledger;
    private Main main;
    private BankService bank;

    /** One ask; answered on release. */
    static final class Ask {
        final UUID player; final double delta; final String key; final long deadline;
        final CompletableFuture<String> future = new CompletableFuture<>();
        Ask(UUID player, double delta, String key, long deadline) { this.player = player; this.delta = delta; this.key = key; this.deadline = deadline; }
    }

    /** pxc-ledger seen through adjustAsync: wallets, keyed rows (key before floor), per-wallet unsettled marker. */
    static final class Ledger implements BankService.AsyncLedger {
        boolean active = true;
        final Map<UUID, Double> wallet = new HashMap<>();
        final Map<String, Object[]> keys = new HashMap<>();
        final Map<String, Integer> applied = new HashMap<>();
        final Set<UUID> unsettled = new HashSet<>();
        final Deque<Ask> open = new ArrayDeque<>();
        final List<Ask> sent = new ArrayList<>();
        final Map<String, Deque<String>> forced = new HashMap<>();
        @Override public boolean active() { return active; }
        @Override public CompletableFuture<String> adjust(UUID player, double delta, String key, long deadlineMs) {
            Ask a = new Ask(player, delta, key, deadlineMs); open.add(a); sent.add(a); return a.future;
        }
        void force(String key, String... answers) { forced.computeIfAbsent(key, k -> new ArrayDeque<>()).addAll(Arrays.asList(answers)); }
        String real(Ask a) {
            if (unsettled.contains(a.player)) return "FAILED"; // the real ledger: null before any I/O while UNSETTLED
            Object[] k = keys.get(a.key);
            if (k != null) return k[0].equals(a.player) && (double) k[1] == a.delta ? "REPLAYED" : "KEY_CONFLICT";
            double after = wallet.getOrDefault(a.player, 0d) + a.delta;
            if (a.delta < 0 && after < 0) return "BELOW_FLOOR";
            wallet.put(a.player, after); keys.put(a.key, new Object[]{a.player, a.delta}); applied.merge(a.key, 1, Integer::sum);
            return "SUCCESS";
        }
        void release() {
            Ask a = open.poll();
            assertNotNull(a, "an ask is open");
            Deque<String> f = forced.get(a.key);
            String forcedAnswer = f != null && !f.isEmpty() ? f.poll() : null;
            String r;
            if ("LATE_COMMIT".equals(forcedAnswer)) { r = real(a); unsettled.add(a.player); r = "SUCCESS".equals(r) ? "UNSETTLED" : r; }
            else r = forcedAnswer != null ? forcedAnswer : real(a);
            a.future.complete(r);
        }
        void releaseAll() { while (!open.isEmpty()) release(); }
        long asks(String key) { return sent.stream().filter(a -> a.key.equals(key)).count(); }
    }

    static final class Main implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable r) { tasks.add(r); }
        void tick() { while (!tasks.isEmpty()) tasks.poll().run(); }
    }

    /** Under the ledger the async path must not touch the old provider API at all (it may wait on MySQL). */
    static final class NoProviderCalls implements DurableEconomy {
        @Override public boolean available() { return true; }
        @Override public boolean hasAccount(UUID player) { throw new AssertionError("hasAccount on the server thread"); }
        @Override public double balance(UUID player) { throw new AssertionError("balance on the server thread"); }
        @Override public boolean change(UUID player, double delta, String reason) { throw new AssertionError("synchronous change"); }
        @Override public boolean flushConfirmed(UUID player, double expected) { throw new AssertionError("flushConfirmed"); }
    }

    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:bankasync_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        factory = () -> DriverManager.getConnection(url);
        store = new TeamStore(factory, "donut_");
        store.createFixtureSchema();
        store.validateSchema();
        owner = UUID.randomUUID();
        team = store.create("AsyncFixture", "AF", owner, "FixtureOwner", "fixture");
        ledger = new Ledger();
        ledger.wallet.put(owner, 100d);
        main = new Main();
        bank = new BankService(store, new NoProviderCalls(), temporary.resolve("bank.wal"), 1000, ledger, main);
    }

    private AtomicReference<BankService.Result> move(String opid, boolean deposit, double amount) {
        AtomicReference<BankService.Result> out = new AtomicReference<>();
        bank.transferAsync(opid, owner, deposit, amount, 0, false, out::set);
        return out;
    }
    private void openingBank(double value) throws Exception { sql("UPDATE donut_teams SET balance=" + value + " WHERE id=" + team.id()); }
    private double bankBalance() throws Exception {
        try (Connection c = factory.open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT balance FROM donut_teams WHERE id=" + team.id())) {
            assertTrue(r.next()); return r.getDouble(1);
        }
    }
    private void sql(String fixture) throws Exception { try (Connection c = factory.open(); Statement s = c.createStatement()) { s.execute(fixture); } }
    private String phase(String opid) throws Exception { return store.operation(opid).orElseThrow().phase(); }
    private String payload(String opid, String field) throws Exception {
        var p = JsonParser.parseString(store.operation(opid).orElseThrow().payload()).getAsJsonObject();
        return p.has(field) ? p.get(field).getAsString() : null;
    }
    private double wallet() { return ledger.wallet.get(owner); }

    @Test void decisionMapping() {
        Map<String, LedgerAsks.Outcome[]> t = new HashMap<>();
        t.put("SUCCESS", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.APPLIED, LedgerAsks.Outcome.APPLIED});
        t.put("REPLAYED", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.APPLIED, LedgerAsks.Outcome.APPLIED});
        t.put("BELOW_FLOOR", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.REFUSED, LedgerAsks.Outcome.REFUSED});
        t.put("KEY_CONFLICT", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.REFUSED, LedgerAsks.Outcome.REFUSED});
        t.put("FAILED", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.REFUSED, LedgerAsks.Outcome.PENDING});
        t.put("CANCELLED", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.REFUSED, LedgerAsks.Outcome.PENDING});
        t.put("INVALID", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.REFUSED, LedgerAsks.Outcome.PENDING});
        t.put("INACTIVE", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.REFUSED, LedgerAsks.Outcome.PENDING});
        t.put("BUSY", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.PENDING, LedgerAsks.Outcome.PENDING});
        t.put("UNSETTLED", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.PENDING, LedgerAsks.Outcome.PENDING});
        t.put("EXCEPTION X", new LedgerAsks.Outcome[]{LedgerAsks.Outcome.PENDING, LedgerAsks.Outcome.PENDING});
        t.put(null, new LedgerAsks.Outcome[]{LedgerAsks.Outcome.PENDING, LedgerAsks.Outcome.PENDING});
        for (var e : t.entrySet()) {
            assertEquals(e.getValue()[0], LedgerAsks.first(e.getKey()), "first " + e.getKey());
            assertEquals(e.getValue()[1], LedgerAsks.later(e.getKey()), "later " + e.getKey());
            assertEquals(e.getValue()[0], LedgerAsks.decide(new String[]{e.getKey(), "1"}));
            assertEquals(e.getValue()[1], LedgerAsks.decide(new String[]{e.getKey(), "2"}));
        }
        assertEquals(0, LedgerAsks.FIRST_DEADLINE_MS, "first ask = one attempt");
    }

    @Test void depositNeverWaitsAndFinishesOnTheNextTick() throws Exception {
        openingBank(50);
        var r = move("async_dep_01", true, 30);
        assertNull(r.get(), "returned before any ledger answer");
        assertEquals("PLAYER_INTENT", phase("async_dep_01"));
        assertEquals(1, ledger.open.size());
        assertEquals(BankService.playerKey("async_dep_01"), ledger.sent.get(0).key);
        assertEquals(-30, ledger.sent.get(0).delta, 1e-9);
        ledger.release();
        assertNull(r.get(), "the answer waits for the server thread");
        main.tick();
        assertTrue(r.get().success(), r.get().code());
        assertEquals("DONE", phase("async_dep_01"));
        assertEquals(70, wallet(), 1e-9);
        assertEquals(80, bankBalance(), 1e-9);
        assertTrue(store.pendingOperations().isEmpty());
    }

    @Test void withdrawSucceeds() throws Exception {
        openingBank(50);
        var r = move("async_wd_01", false, 20);
        assertEquals(30, bankBalance(), 1e-9, "bank leg first (server thread, team store)");
        ledger.releaseAll(); main.tick();
        assertTrue(r.get().success(), r.get().code());
        assertEquals(120, wallet(), 1e-9);
        assertEquals(20, ledger.sent.get(0).delta, 1e-9);
        assertEquals("DONE", phase("async_wd_01"));
    }

    @Test void firstFailedDepositIsCancelledNotFrozen() throws Exception {
        openingBank(50);
        ledger.force(BankService.playerKey("async_dep_fail"), "FAILED");
        var r = move("async_dep_fail", true, 30);
        ledger.releaseAll(); main.tick();
        assertFalse(r.get().success()); assertFalse(r.get().pending());
        assertEquals("PLAYER_DEBIT_REFUSED", r.get().code());
        assertEquals("CANCELLED", phase("async_dep_fail"));
        assertEquals(1, ledger.asks(BankService.playerKey("async_dep_fail")), "no second ask after a first FAILED");
        assertEquals(100, wallet(), 1e-9); assertEquals(50, bankBalance(), 1e-9);
        assertFalse(store.isFrozen(team.id(), owner));
        var next = move("async_dep_next", true, 10); ledger.releaseAll(); main.tick();
        assertTrue(next.get().success(), "the next bank op is not blocked");
    }

    @Test void depositBelowFloorIsInsufficientFunds() throws Exception {
        var r = move("async_dep_poor", true, 500);
        ledger.releaseAll(); main.tick();
        assertEquals("INSUFFICIENT_PLAYER", r.get().code());
        assertEquals("CANCELLED", phase("async_dep_poor"));
        assertEquals(100, wallet(), 1e-9); assertEquals(0, bankBalance(), 1e-9);
    }

    @Test void firstFailedWithdrawCreditPutsTheBankMoneyBack() throws Exception {
        openingBank(50);
        ledger.force(BankService.playerKey("async_wd_fail"), "FAILED");
        var r = move("async_wd_fail", false, 20);
        ledger.releaseAll(); main.tick();
        assertEquals("PLAYER_CREDIT_REFUSED", r.get().code());
        assertFalse(r.get().pending());
        assertEquals("COMPENSATED", phase("async_wd_fail"));
        assertEquals(50, bankBalance(), 1e-9); assertEquals(100, wallet(), 1e-9);
        assertFalse(store.isFrozen(team.id(), owner));
    }

    @Test void unsettledThenReplayedIsDoneOnce() throws Exception {
        openingBank(0);
        String key = BankService.playerKey("async_dep_late");
        ledger.force(key, "LATE_COMMIT"); // committed, answer lost
        var r = move("async_dep_late", true, 40);
        ledger.release();                   // UNSETTLED -> second ask (same key)
        assertEquals(1, ledger.open.size());
        assertTrue(main.tasks.isEmpty());
        ledger.unsettled.remove(owner);     // the ledger settles meanwhile
        ledger.release(); main.tick();      // REPLAYED
        assertTrue(r.get().success(), r.get().code());
        assertEquals(1, ledger.applied.get(key)); assertEquals(60, wallet(), 1e-9); assertEquals(40, bankBalance(), 1e-9);
        assertEquals(2, ledger.asks(key));
        assertEquals(LedgerAsks.FIRST_DEADLINE_MS, ledger.sent.get(0).deadline);
        assertEquals(LedgerAsks.RETRY_DEADLINE_MS, ledger.sent.get(1).deadline);
    }

    /** Open at the deadline (UNSETTLED, then FAILED while it waits for its settle): FROZEN, then retried with the SAME key. */
    @Test void deadlinePendingFreezesThenTheSameKeySettlesIt() throws Exception {
        openingBank(0);
        String opid = "async_dep_pending", key = BankService.playerKey(opid);
        ledger.force(key, "LATE_COMMIT");
        var r = move(opid, true, 40);
        ledger.release(); ledger.release(); main.tick(); // second ask: FAILED (wallet unsettled) = NOT "not applied"
        assertTrue(r.get().pending()); assertEquals("LEDGER_PENDING", r.get().code());
        assertEquals("FROZEN", phase(opid));
        assertEquals(Set.of(opid), bank.retrying());
        assertEquals(60, wallet(), 1e-9, "the debit did land"); assertEquals(0, bankBalance(), 1e-9, "bank not credited yet");
        List<BankService.Result> told = new ArrayList<>();
        bank.onRetried((p, res) -> { assertEquals(owner, p); told.add(res); });
        bank.tickRetries(Long.MAX_VALUE);
        assertEquals(1, ledger.open.size(), "retry asked");
        ledger.release(); main.tick(); // still unsettled: FAILED -> stays frozen
        assertEquals("FROZEN", phase(opid)); assertTrue(told.get(0).pending());
        ledger.unsettled.remove(owner);
        bank.tickRetries(Long.MAX_VALUE); ledger.release(); main.tick(); // REPLAYED
        assertEquals("DONE", phase(opid));
        assertTrue(told.get(told.size() - 1).success());
        assertEquals(1, ledger.applied.get(key)); assertEquals(60, wallet(), 1e-9); assertEquals(40, bankBalance(), 1e-9);
        assertTrue(bank.retrying().isEmpty());
        for (Ask a : ledger.sent) assertEquals(key, a.key, "every ask of the op used its one key");
        assertFalse(store.isFrozen(team.id(), owner));
    }

    @Test void pendingThatNeverAppliedIsAppliedOnceByTheRetry() throws Exception {
        openingBank(0);
        String opid = "async_dep_busy", key = BankService.playerKey(opid);
        ledger.force(key, "BUSY", "FAILED");
        var r = move(opid, true, 25);
        ledger.releaseAll(); main.tick();
        assertEquals("LEDGER_PENDING", r.get().code());
        bank.tickRetries(Long.MAX_VALUE); ledger.releaseAll(); main.tick();
        assertEquals("DONE", phase(opid));
        assertEquals(75, wallet(), 1e-9); assertEquals(25, bankBalance(), 1e-9); assertEquals(1, ledger.applied.get(key));
        bank.tickRetries(Long.MAX_VALUE);
        assertTrue(ledger.open.isEmpty(), "nothing left to retry");
    }

    /** Restart with the op FROZEN (pending or killed mid-ask): the new BankService adopts it and finishes it by the same key. */
    @Test void restartAdoptsAndFinishesByTheSameKey() throws Exception {
        openingBank(50);
        String opid = "async_wd_restart", key = BankService.playerKey(opid);
        var r = move(opid, false, 20);           // bank debited, credit asked ... and the server stops before the answer
        assertNull(r.get());
        assertEquals("PLAYER_INTENT", phase(opid));
        for (var op : store.pendingOperationsOwnedHere()) store.freezeOperation(op.opid(), "RESTART_PENDING_" + op.phase()); // TeamsCore boot
        // an xs6-era frozen op (no ledger step) must stay with staff
        store.beginOperation("legacy_frozen_01", team.id(), owner, "DEPOSIT", 5, "PLAYER_INTENT", "{}");
        store.freezeOperation("legacy_frozen_01", "PLAYER_OUTCOME_UNKNOWN");
        Main main2 = new Main();
        BankService restarted = new BankService(store, new NoProviderCalls(), temporary.resolve("bank.wal"), 1000, ledger, main2);
        restarted.adopt(store.pendingOperationsOwnedHere());
        assertEquals(Set.of(opid), restarted.retrying());
        ledger.open.clear(); // the old JVM's ask is gone with it
        restarted.tickRetries(Long.MAX_VALUE); ledger.releaseAll(); main2.tick();
        assertEquals("DONE", phase(opid));
        assertEquals(120, wallet(), 1e-9); assertEquals(30, bankBalance(), 1e-9); assertEquals(1, ledger.applied.get(key));
        assertEquals("FROZEN", phase("legacy_frozen_01"));
    }

    /** Deposit debited but the bank refuses (cap lowered meanwhile): the player is refunded under the refund key. */
    @Test void bankRefusalAfterDebitRefundsByTheRefundKey() throws Exception {
        openingBank(50);
        String opid = "async_dep_cap";
        var r = move(opid, true, 30);
        bank.setCap(60);
        ledger.release(); main.tick();          // debit SUCCESS -> bank refuses (CAP) -> REFUND step asked
        assertEquals("REFUND_INTENT", phase(opid));
        assertEquals("REFUND", payload(opid, "ledgerStep"));
        assertEquals(BankService.refundKey(opid), ledger.open.peek().key);
        assertEquals(30, ledger.open.peek().delta, 1e-9);
        ledger.release(); main.tick();
        assertEquals("COMPENSATED", r.get().code());
        assertEquals("COMPENSATED", phase(opid));
        assertEquals(100, wallet(), 1e-9); assertEquals(50, bankBalance(), 1e-9);
    }

    /** A refund that is refused without the key being looked at must still land: pending, retried, applied once. */
    @Test void refundMustLand() throws Exception {
        openingBank(50);
        String opid = "async_dep_refund_fail";
        var r = move(opid, true, 30);
        bank.setCap(60);
        ledger.force(BankService.refundKey(opid), "FAILED");
        ledger.release(); main.tick(); ledger.release(); main.tick();
        assertEquals("LEDGER_PENDING", r.get().code());
        assertEquals("FROZEN", phase(opid));
        bank.tickRetries(Long.MAX_VALUE); ledger.releaseAll(); main.tick();
        assertEquals("COMPENSATED", phase(opid));
        assertEquals(100, wallet(), 1e-9);
        assertEquals(1, ledger.applied.get(BankService.refundKey(opid)));
    }

    @Test void keyConflictGoesToStaffAndIsNeverRetried() throws Exception {
        openingBank(50);
        String opid = "async_dep_conflict";
        ledger.force(BankService.playerKey(opid), "UNSETTLED", "KEY_CONFLICT");
        var r = move(opid, true, 30);
        ledger.releaseAll(); main.tick();
        assertTrue(r.get().pending());
        assertEquals("FROZEN", phase(opid)); assertEquals("STAFF", payload(opid, "ledgerStep"));
        assertTrue(bank.retrying().isEmpty());
        BankService restarted = new BankService(store, new NoProviderCalls(), temporary.resolve("bank.wal"), 1000, ledger, main);
        restarted.adopt(store.pendingOperationsOwnedHere());
        assertTrue(restarted.retrying().isEmpty(), "boot does not adopt a staff freeze");
    }

    @Test void keysAreStablePerOperation() {
        assertEquals("pxc:team-bank:op_abcdefgh", BankService.playerKey("op_abcdefgh"));
        assertEquals("pxc:team-bank-refund:op_abcdefgh", BankService.refundKey("op_abcdefgh"));
        assertNotEquals(BankService.playerKey("op_abcdefgh"), BankService.refundKey("op_abcdefgh"));
    }

    @Test void withoutTheLedgerTheSynchronousPathRunsBeforeReturning() throws Exception {
        ledger.active = false;
        Map<UUID, Double> money = new HashMap<>(Map.of(owner, 100d));
        DurableEconomy sync = new DurableEconomy() {
            @Override public boolean available() { return true; }
            @Override public boolean hasAccount(UUID p) { return money.containsKey(p); }
            @Override public double balance(UUID p) { return money.get(p); }
            @Override public boolean change(UUID p, double d, String reason) { money.merge(p, d, Double::sum); return true; }
            @Override public boolean flushConfirmed(UUID p, double expected) { return PackEconomy.same(money.get(p), expected); }
        };
        BankService plain = new BankService(store, sync, temporary.resolve("bank2.wal"), 1000, ledger, main);
        AtomicReference<BankService.Result> out = new AtomicReference<>();
        plain.transferAsync("sync_dep_0001", owner, true, 10, 0, false, out::set);
        assertNotNull(out.get(), "done ran before transferAsync returned");
        assertTrue(out.get().success());
        assertTrue(ledger.sent.isEmpty() && main.tasks.isEmpty());
        assertEquals(90, money.get(owner), 1e-9);
    }
}
