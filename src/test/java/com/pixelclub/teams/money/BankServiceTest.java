package com.pixelclub.teams.money;

import com.pixelclub.teams.store.ConnectionFactory;
import com.pixelclub.teams.store.Team;
import com.pixelclub.teams.store.TeamStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Acceptance fixtures never look up runtime configuration or touch a real economy account. */
class BankServiceTest {
    @TempDir Path temporary;
    private ConnectionFactory factory;
    private TeamStore store;
    private Team team;
    private UUID owner;
    private FakeEconomy economy;
    private BankService bank;
    private Path wal;

    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:bank_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        factory = () -> DriverManager.getConnection(url);
        store = new TeamStore(factory, "donut_");
        store.createFixtureSchema();
        store.validateSchema();
        owner = UUID.randomUUID();
        team = store.create("BankFixture", "BF", owner, "FixtureOwner", "fixture");
        economy = new FakeEconomy();
        economy.account(owner, 100);
        wal = temporary.resolve("bank.wal");
        bank = new BankService(store, economy, wal, 1000);
    }

    @Test void depositConservesPersistedPlayerPlusBank() throws Exception {
        openingBank(17.123456789);
        assertTrue(bank.transfer("deposit_01", owner, true, 20, 0, false).success());
        assertState(owner, 80, 37.123456789);
        assertEquals(117.123456789, economy.durable(owner) + balance(), 1e-9);
        assertEquals("DONE", phase("deposit_01"));
        assertTrue(store.operation("deposit_01").orElseThrow().effectApplied());
        assertTrue(store.pendingOperations().isEmpty());
    }

    @Test void withdrawalConservesPersistedTotalMinusFee() throws Exception {
        openingBank(50);
        assertTrue(bank.transfer("withdraw_01", owner, false, 20, 1.25, false).success());
        assertState(owner, 118.75, 30);
        assertEquals(148.75, economy.durable(owner) + balance(), 1e-9);
        assertEquals("DONE", phase("withdraw_01"));
    }

    @Test void withdrawalPermissionUsesExplicitFlagForOwnerMemberAndCoOwner() throws Exception {
        openingBank(50);
        assertTrue(store.setMemberFlags(team.id(), owner, Map.of("can_withdraw", false)));
        assertRefusedUnchanged("owner_denied", owner, false, 1, 0, "DENIED");
        UUID member = addMember();
        assertRefusedUnchanged("member_denied", member, false, 1, 0, "DENIED");
        assertTrue(store.setMemberRoleAndFlags(team.id(), member, "CO_OWNER", Map.of("can_withdraw", false)));
        assertRefusedUnchanged("coowner_denied", member, false, 1, 0, "DENIED");
        assertTrue(store.setMemberRoleAndFlags(team.id(), member, "MEMBER", Map.of("can_withdraw", true)));
        assertTrue(bank.transfer("member_allowed", member, false, 1, 0, false).success());
        assertState(member, 101, 49);
    }

    @Test void explicitBypassAllowsWithdrawalButDoesNotRemoveBankUnderflowCheck() throws Exception {
        UUID member = addMember();
        openingBank(2);
        assertTrue(bank.transfer("bypass_allow", member, false, 1, 0, true).success());
        assertState(member, 101, 1);
        assertFalse(bank.transfer("bypass_underflow", member, false, 2, 0, true).success());
        assertState(member, 101, 1);
    }

    @Test void insufficientPlayerBankAndCapRefuseBeforeProviderWrites() throws Exception {
        openingBank(25);
        assertRefusedUnchanged("insuff_player", owner, true, 100, 0.01, "INSUFFICIENT_PLAYER");
        assertRefusedUnchanged("insuff_bank", owner, false, 25.01, 0, "INSUFFICIENT_BANK");
        bank = new BankService(store, economy, wal, 25);
        assertRefusedUnchanged("cap_refusal", owner, true, 0.01, 0, "BANK_CAP");
        assertRefusedUnchanged("fee_refusal", owner, false, 1, 1, "FEE_EXCEEDS_AMOUNT");
    }

    @Test void invalidAmountsAndFeesDoNotCreateJournalOrTouchAccounts() throws Exception {
        double[] invalidAmounts = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 0, 0.004};
        int n = 0;
        for (double amount : invalidAmounts) assertRefusedUnchanged("invalid_amt_" + n++, owner, true, amount, 0, "INVALID_AMOUNT");
        double[] invalidFees = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.001};
        for (double fee : invalidFees) assertRefusedUnchanged("invalid_fee_" + n++, owner, true, 1, fee, "INVALID_AMOUNT");
        assertTrue(economy.calls.isEmpty());
    }

    @Test void missingAccountIsNeverCreatedEvenForBankFundedFeature() throws Exception {
        UUID member = addMember();
        economy.memory.remove(member); economy.saved.remove(member);
        openingBank(50);
        assertFalse(bank.transfer("missing_account", member, true, 1, 0, false).success());
        assertFalse(bank.charge("missing_feature", member, 1, true).success());
        assertFalse(economy.memory.containsKey(member));
        assertFalse(economy.saved.containsKey(member));
        assertEquals(0, economy.changes);
        assertEquals(50, balance());
        assertTrue(store.operation("missing_account").isEmpty());
        assertTrue(store.operation("missing_feature").isEmpty());
    }

    @Test void halfCentNormalizesOnceAndPreservesOpeningBankResidue() throws Exception {
        double opening = 10.000123456789;
        openingBank(opening);
        assertTrue(bank.transfer("half_cent_deposit", owner, true, 0.005, 0.005, false).success());
        assertState(owner, 99.98, opening + 0.01);
        assertEquals(0.01, store.operation("half_cent_deposit").orElseThrow().amount());
        assertTrue(bank.transfer("half_cent_withdraw", owner, false, 0.005, 0, false).success());
        assertState(owner, 99.99, opening);
        assertEquals(opening, balance(), 1e-12);
    }

    @Test void settledIdenticalRetryMakesNoAdditionalProviderCalls() throws Exception {
        assertTrue(bank.transfer("repeat_same", owner, true, 20, 1, false).success());
        List<String> calls = List.copyOf(economy.calls);
        BankService.Result repeated = bank.transfer("repeat_same", owner, true, 20, 1, false);
        assertTrue(repeated.success()); assertFalse(repeated.pending());
        assertEquals(calls, economy.calls);
        assertState(owner, 79, 20);
    }

    @Test void operationIdCollisionForActorAmountAndDirectionRefusesWithoutCalls() throws Exception {
        UUID member = addMember();
        assertTrue(bank.transfer("identity_collision", owner, true, 20, 0, false).success());
        List<String> calls = List.copyOf(economy.calls);
        assertEquals("OPID_COLLISION", bank.transfer("identity_collision", member, true, 20, 0, false).code());
        assertEquals("OPID_COLLISION", bank.transfer("identity_collision", owner, true, 21, 0, false).code());
        assertEquals("OPID_COLLISION", bank.transfer("identity_collision", owner, false, 20, 0, false).code());
        assertEquals(calls, economy.calls);
        assertState(owner, 80, 20);
        assertEquals(100, economy.durable(member));
    }

    @Test void providerFalseAfterApplyingFreezesAndCannotReplay() throws Exception {
        economy.outcomes.add(Outcome.FALSE_AFTER);
        assertPendingFrozen(bank.transfer("false_after_apply", owner, true, 20, 0, false), "false_after_apply");
        assertState(owner, 80, 0);
        assertNoReplay("false_after_apply", owner, true, 20, 0);
        assertFalse(bank.transfer("fresh_after_freeze", owner, true, 1, 0, false).success());
        assertState(owner, 80, 0);
    }

    @Test void providerExceptionAfterApplyingFreezesAndCannotReplay() throws Exception {
        economy.outcomes.add(Outcome.THROW_AFTER);
        assertPendingFrozen(bank.transfer("throw_after_apply", owner, true, 20, 0, false), "throw_after_apply");
        assertState(owner, 80, 0);
        assertNoReplay("throw_after_apply", owner, true, 20, 0);
    }

    @Test void failedFlushKeepsMemoryAndDurableDifferenceFrozenWithoutReplay() throws Exception {
        economy.rejectFlushAt = 1;
        assertPendingFrozen(bank.transfer("flush_failure", owner, true, 20, 0, false), "flush_failure");
        assertEquals(80, economy.memory(owner)); assertEquals(100, economy.durable(owner));
        assertEquals(0, balance());
        assertNoReplay("flush_failure", owner, true, 20, 0);
    }

    @Test void outsideBalanceChangeCannotProduceSuccessReceipt() throws Exception {
        economy.afterFlush = () -> { economy.memory.put(owner, 81d); economy.saved.put(owner, 81d); };
        assertPendingFrozen(bank.transfer("outside_change", owner, true, 20, 0, false), "outside_change");
        assertState(owner, 81, 20);
        assertNoReplay("outside_change", owner, true, 20, 0);
    }

    @Test void conditionalBankRejectionAfterDebitUsesConfirmedRefund() throws Exception {
        bank = new BankService(store, economy, wal, 100);
        economy.afterChange = () -> openingBank(100);
        BankService.Result result = bank.transfer("conditional_reject", owner, true, 20, 1, false);
        assertFalse(result.success()); assertFalse(result.pending());
        assertEquals("COMPENSATED", phase("conditional_reject"));
        assertState(owner, 100, 100);
        assertEquals(2, economy.changes); assertEquals(2, economy.flushes);
        assertNoReplay("conditional_reject", owner, true, 20, 1);
    }

    @Test void refundFalseAfterApplyingStillFreezesInsteadOfDeclaringCompensated() throws Exception {
        bank = new BankService(store, economy, wal, 100);
        economy.afterChange = () -> openingBank(100);
        economy.outcomes.add(Outcome.SUCCESS); economy.outcomes.add(Outcome.FALSE_AFTER);
        assertPendingFrozen(bank.transfer("refund_failure", owner, true, 20, 1, false), "refund_failure");
        assertState(owner, 100, 100);
        assertEquals(2, economy.changes);
        assertNoReplay("refund_failure", owner, true, 20, 1);
    }

    @Test void journalFailureAfterPlayerLegRetainsPendingAndDoesNotRepeatDebit() throws Exception {
        sql("ALTER TABLE pxc_teams_operations ADD CONSTRAINT refuse_player_receipt CHECK(phase <> 'PLAYER_APPLIED')");
        assertPendingFrozen(bank.transfer("journal_failure", owner, true, 20, 0, false), "journal_failure");
        assertState(owner, 80, 0);
        assertNoReplay("journal_failure", owner, true, 20, 0);
    }

    @Test void journalFailureAfterBankLegDoesNotPayPlayerOnRetry() throws Exception {
        openingBank(50);
        sql("ALTER TABLE pxc_teams_operations ADD CONSTRAINT refuse_player_intent CHECK(phase <> 'PLAYER_INTENT')");
        assertPendingFrozen(bank.transfer("withdraw_journal", owner, false, 20, 0, false), "withdraw_journal");
        assertState(owner, 100, 30);
        assertEquals(0, economy.changes);
        assertNoReplay("withdraw_journal", owner, false, 20, 0);
    }

    @Test void walFailureAfterFirstLegRetainsDurableObligationWithoutBlindRepeat() throws Exception {
        economy.afterChange = () -> { Files.delete(wal); Files.createDirectory(wal); };
        BankService.Result result = bank.transfer("wal_failure", owner, true, 20, 0, false);
        assertFalse(result.success()); assertTrue(result.pending());
        assertTrue(store.pendingOperations().stream().anyMatch(op -> op.opid().equals("wal_failure")));
        assertTrue(store.isFrozen(team.id(), owner));
        assertState(owner, 80, 0);
        assertNoReplay("wal_failure", owner, true, 20, 0);
    }

    @Test void restartPreservesFrozenObligationAndNeverAutomaticallyPaysOut() throws Exception {
        openingBank(50);
        economy.rejectFlushAt = 1;
        assertPendingFrozen(bank.transfer("restart_frozen", owner, false, 20, 0, false), "restart_frozen");
        assertEquals(120, economy.memory(owner)); assertEquals(100, economy.durable(owner)); assertEquals(30, balance());
        FakeEconomy restartedEconomy = economy.restart();
        TeamStore reopened = new TeamStore(factory, "donut_");
        BankService restarted = new BankService(reopened, restartedEconomy, wal, 1000);
        assertEquals(100, restartedEconomy.durable(owner)); assertTrue(restartedEconomy.calls.isEmpty());
        assertEquals("FROZEN", reopened.operation("restart_frozen").orElseThrow().phase());
        assertTrue(reopened.isFrozen(team.id(), owner));
        assertTrue(restarted.transfer("restart_frozen", owner, false, 20, 0, false).pending());
        assertTrue(restartedEconomy.calls.isEmpty());
        assertFalse(restarted.transfer("restart_new_op", owner, false, 1, 0, false).success());
        assertEquals(0, restartedEconomy.changes); assertEquals(100, restartedEconomy.durable(owner)); assertEquals(30, balance());
    }

    @Test void bankFirstFeatureSplitsOnlyNewCurrencyAmountAndRetainsOpeningResidue() throws Exception {
        openingBank(10.005);
        assertTrue(bank.charge("feature_bank_first", owner, 20, true).success());
        assertState(owner, 90, 0.005);
        assertTrue(bank.finishCharge("feature_bank_first", true));
        assertEquals("DONE", phase("feature_bank_first"));
        assertTrue(store.pendingOperations().isEmpty());
        assertEquals(1, economy.changes);
    }

    @Test void personalOnlyFeatureLeavesBankIntactAndRepeatDoesNotChargeAgain() throws Exception {
        openingBank(50);
        assertTrue(bank.charge("feature_personal", owner, 20, false).success());
        assertState(owner, 80, 50);
        List<String> calls = List.copyOf(economy.calls);
        assertFalse(bank.charge("feature_personal", owner, 20, false).success());
        assertEquals(calls, economy.calls);
        assertTrue(bank.finishCharge("feature_personal", true));
        assertTrue(bank.finishCharge("feature_personal", true));
        assertEquals(calls, economy.calls);
        assertFalse(bank.finishCharge("feature_personal", false));
        assertEquals(calls, economy.calls);
    }

    @Test void bankFullyFundedFeatureDoesNotWritePlayerAccount() throws Exception {
        openingBank(50);
        assertTrue(bank.charge("feature_bank_only", owner, 20, true).success());
        assertState(owner, 100, 30);
        assertEquals(0, economy.changes); assertEquals(0, economy.flushes);
        assertTrue(bank.finishCharge("feature_bank_only", true));
        assertTrue(store.pendingOperations().isEmpty());
    }

    @Test void definiteActionFailureRefundsBothLegsWithConfirmedPlayerPersistence() throws Exception {
        openingBank(10);
        assertTrue(bank.charge("feature_compensate", owner, 20, true).success());
        assertState(owner, 90, 0);
        assertTrue(bank.finishCharge("feature_compensate", false));
        assertState(owner, 100, 10);
        assertEquals("COMPENSATED", phase("feature_compensate"));
        assertTrue(store.pendingOperations().isEmpty());
        int calls = economy.calls.size();
        assertTrue(bank.finishCharge("feature_compensate", false));
        assertEquals(calls, economy.calls.size());
        assertFalse(bank.finishCharge("feature_compensate", true));
        assertEquals(calls, economy.calls.size());
        assertState(owner, 100, 10);
    }

    @Test void failedFeatureRefundFreezesBeforeReturningTeamMoney() throws Exception {
        openingBank(10);
        assertTrue(bank.charge("feature_bad_refund", owner, 20, true).success());
        economy.rejectFlushAt = 2;
        assertFalse(bank.finishCharge("feature_bad_refund", false));
        assertEquals("FROZEN", phase("feature_bad_refund"));
        assertEquals(100, economy.memory(owner)); assertEquals(90, economy.durable(owner)); assertEquals(0, balance());
        int calls = economy.calls.size();
        assertFalse(bank.finishCharge("feature_bad_refund", false));
        assertEquals(calls, economy.calls.size());
    }

    @Test void definiteBankRefundRejectionFreezesWithoutInventingCompensation() throws Exception {
        bank = new BankService(store, economy, wal, 100);
        openingBank(10);
        assertTrue(bank.charge("feature_bank_refund", owner, 10, true).success());
        openingBank(100); // Fixture-only concurrent bank credit consumes the available cap.
        assertFalse(bank.finishCharge("feature_bank_refund", false));
        assertEquals("FROZEN", phase("feature_bank_refund"));
        assertState(owner, 100, 100);
        assertEquals(0, economy.changes);
        assertTrue(store.pendingOperations().stream().anyMatch(op -> op.opid().equals("feature_bank_refund")));
    }

    @Test void featureRefundExceptionRetainsFrozenObligationAndCannotRepeat() throws Exception {
        openingBank(10);
        assertTrue(bank.charge("feature_throw_refund", owner, 20, true).success());
        economy.outcomes.add(Outcome.THROW_AFTER);
        try { bank.finishCharge("feature_throw_refund", false); } catch (Exception expectedAmbiguity) { /* read durable state below */ }
        assertEquals("FROZEN", phase("feature_throw_refund"), "An ambiguous provider refund must quarantine the operation");
        assertState(owner, 100, 0);
        int calls = economy.calls.size();
        assertFalse(bank.finishCharge("feature_throw_refund", false));
        assertEquals(calls, economy.calls.size());
        assertFalse(bank.transfer("after_throw_refund", owner, true, 1, 0, false).success());
    }

    @Test void featureSqlRefundFailureAfterConfirmedPlayerRefundFreezesRemainingBankObligation() throws Exception {
        openingBank(10);
        assertTrue(bank.charge("feature_sql_refund", owner, 20, true).success());
        sql("ALTER TABLE pxc_teams_operations ADD CONSTRAINT fail_bank_refund_receipt CHECK(opid <> 'feature_sql_refund_refund' OR phase <> 'TEAM_APPLIED')");
        assertThrows(Exception.class, () -> bank.finishCharge("feature_sql_refund", false));
        assertEquals("FROZEN", phase("feature_sql_refund"));
        assertState(owner, 100, 0);
        assertTrue(store.isFrozen(team.id(), owner));
        int calls = economy.calls.size();
        assertFalse(bank.finishCharge("feature_sql_refund", false));
        assertEquals(calls, economy.calls.size());
        assertState(owner, 100, 0);
    }

    @Test void featureWalFailureDuringCompensationFreezesEvenWhenMoneyWasRestored() throws Exception {
        openingBank(10);
        assertTrue(bank.charge("feature_wal_refund", owner, 20, true).success());
        economy.afterChange = () -> { Files.delete(wal); Files.createDirectory(wal); };
        assertThrows(Exception.class, () -> bank.finishCharge("feature_wal_refund", false));
        assertEquals("FROZEN", phase("feature_wal_refund"));
        assertState(owner, 100, 10);
        assertTrue(store.isFrozen(team.id(), owner));
        int calls = economy.calls.size();
        assertFalse(bank.finishCharge("feature_wal_refund", false));
        assertEquals(calls, economy.calls.size());
        assertState(owner, 100, 10);
    }

    @Test void featureInvalidCostsAndInsufficientPlayerRefuseWithoutWrites() throws Exception {
        double[] invalid = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.001};
        int n = 0;
        for (double cost : invalid) {
            String opid = "bad_feature_cost_" + n++;
            assertFalse(bank.charge(opid, owner, cost, false).success());
            assertTrue(store.operation(opid).isEmpty());
        }
        openingBank(5);
        assertFalse(bank.charge("feature_insufficient", owner, 106, true).success());
        assertState(owner, 100, 5);
        assertEquals(0, economy.changes);
        assertTrue(store.operation("feature_insufficient").isEmpty());
    }

    @Test void unknownFeaturePlayerOutcomeRetainsAlreadyDebitedBankWithoutReplay() throws Exception {
        openingBank(10);
        economy.outcomes.add(Outcome.FALSE_AFTER);
        assertPendingFrozen(bank.charge("feature_unknown_player", owner, 20, true), "feature_unknown_player");
        assertState(owner, 90, 0);
        List<String> calls = List.copyOf(economy.calls);
        assertFalse(bank.charge("feature_unknown_player", owner, 20, true).success());
        assertFalse(bank.finishCharge("feature_unknown_player", false));
        assertEquals(calls, economy.calls);
        assertState(owner, 90, 0);
    }

    @Test void unknownActionRetainsChargeAndRestartCannotAutomaticallyCompensate() throws Exception {
        openingBank(10);
        assertTrue(bank.charge("feature_unknown", owner, 20, true).success());
        bank.unknownCharge("feature_unknown", "fixture ambiguous action");
        assertEquals("FROZEN", phase("feature_unknown")); assertState(owner, 90, 0);
        FakeEconomy restartedEconomy = economy.restart();
        BankService restarted = new BankService(new TeamStore(factory, "donut_"), restartedEconomy, wal, 1000);
        assertFalse(restarted.finishCharge("feature_unknown", false));
        assertTrue(restartedEconomy.calls.isEmpty());
        assertEquals(90, restartedEconomy.durable(owner)); assertEquals(0, balance());
        assertTrue(store.pendingOperations().stream().anyMatch(op -> op.opid().equals("feature_unknown")));
    }

    @Test void activeFeatureChargeBlocksDestructiveMembershipUntilSettlement() throws Exception {
        UUID member = addMember();
        assertTrue(bank.charge("active_membership", member, 1, false).success());
        assertFalse(store.leave(member)); assertFalse(store.kick(team.id(), member));
        assertFalse(store.transferOwnership(team.id(), owner, member)); assertFalse(store.deleteTeam(team.id(), owner));
        assertEquals(team.id(), store.member(member).orElseThrow().teamId());
        assertEquals(owner, store.team(team.id()).orElseThrow().owner());
        assertTrue(bank.finishCharge("active_membership", true));
        assertTrue(store.leave(member));
    }

    @Test void randomizedSerialTransfersConserveDurableTotalWithCapAndPermissionControls() throws Exception {
        UUID member = addMember();
        openingBank(50);
        bank = new BankService(store, economy, wal, 100);
        Random random = new Random(0xBADC0DEL);
        double expectedPlayer = 100, expectedBank = 50, fees = 0;
        int accepted = 0, rejected = 0;
        for (int i = 0; i < 80; i++) {
            boolean deposit = random.nextBoolean();
            double amount = (random.nextInt(3000) + 1) / 100d;
            double fee = random.nextInt(20) / 100d;
            double pBefore = economy.durable(owner), bBefore = balance();
            BankService.Result result = bank.transfer("sequence_" + i, owner, deposit, amount, fee, false);
            boolean allowed = deposit ? expectedPlayer >= amount + fee && expectedBank + amount <= 100 : expectedBank >= amount && amount > fee;
            assertEquals(allowed, result.success(), "serial step " + i); assertFalse(result.pending());
            if (allowed) {
                expectedPlayer += deposit ? -amount - fee : amount - fee;
                expectedBank += deposit ? amount : -amount;
                fees += fee; accepted++;
            } else { assertEquals(pBefore, economy.durable(owner)); assertEquals(bBefore, balance()); rejected++; }
            assertState(owner, expectedPlayer, expectedBank);
            assertEquals(150 - fees, economy.durable(owner) + balance(), 1e-8);
            assertTrue(store.pendingOperations().isEmpty());
        }
        assertTrue(accepted > 10); assertTrue(rejected > 5);
        int writes = economy.changes;
        assertRefusedUnchanged("sequence_flag_control", member, false, 0.01, 0, "DENIED");
        assertRefusedUnchanged("sequence_cap_control", owner, true, 100.01, 0, "BANK_CAP");
        assertEquals(writes, economy.changes);
    }

    private UUID addMember() throws Exception {
        UUID member = UUID.randomUUID();
        assertTrue(store.invite(team.id(), owner, member, System.currentTimeMillis() + 100000));
        assertTrue(store.accept(member, team.id(), 10));
        economy.account(member, 100);
        return member;
    }
    private void openingBank(double value) throws Exception { sql("UPDATE donut_teams SET balance=" + value + " WHERE id=" + team.id()); }
    private double balance() throws Exception {
        try (Connection connection = factory.open(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT balance FROM donut_teams WHERE id=" + team.id())) {
            assertTrue(result.next()); return result.getDouble(1);
        }
    }
    private void sql(String fixtureSql) throws Exception {
        try (Connection connection = factory.open(); Statement statement = connection.createStatement()) { statement.execute(fixtureSql); }
    }
    private String phase(String opid) throws Exception { return store.operation(opid).orElseThrow().phase(); }
    private void assertState(UUID player, double expectedPlayer, double expectedBank) throws Exception {
        assertEquals(expectedPlayer, economy.memory(player), 1e-8, "provider memory");
        assertEquals(expectedPlayer, economy.durable(player), 1e-8, "provider durable receipt");
        assertEquals(expectedBank, balance(), 1e-8, "SQL bank readback");
    }
    private void assertRefusedUnchanged(String opid, UUID player, boolean deposit, double amount, double fee, String code) throws Exception {
        double pBefore = economy.durable(player), bBefore = balance(); int writes = economy.changes;
        BankService.Result result = bank.transfer(opid, player, deposit, amount, fee, false);
        assertFalse(result.success()); assertFalse(result.pending()); assertEquals(code, result.code());
        assertState(player, pBefore, bBefore); assertEquals(writes, economy.changes);
        assertTrue(store.operation(opid).isEmpty());
    }
    private void assertPendingFrozen(BankService.Result result, String opid) throws Exception {
        assertFalse(result.success()); assertTrue(result.pending()); assertEquals("FROZEN", phase(opid));
        assertTrue(store.isFrozen(team.id(), owner));
        assertTrue(store.pendingOperations().stream().anyMatch(op -> op.opid().equals(opid)));
    }
    private void assertNoReplay(String opid, UUID player, boolean deposit, double amount, double fee) throws Exception {
        List<String> calls = List.copyOf(economy.calls); double pBefore = economy.durable(player), bBefore = balance();
        bank.transfer(opid, player, deposit, amount, fee, false);
        assertEquals(calls, economy.calls, "Retry must not call the provider again");
        assertEquals(pBefore, economy.durable(player)); assertEquals(bBefore, balance());
    }

    private enum Outcome { SUCCESS, FALSE_AFTER, THROW_AFTER }
    @FunctionalInterface private interface FixtureAction { void run() throws Exception; }
    private static final class FakeEconomy implements DurableEconomy {
        private final Map<UUID, Double> memory = new HashMap<>(), saved = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final Deque<Outcome> outcomes = new ArrayDeque<>();
        private FixtureAction afterChange, afterFlush;
        private int changes, flushes, rejectFlushAt = -1;
        void account(UUID player, double value) { memory.put(player, value); saved.put(player, value); }
        double memory(UUID player) { return memory.get(player); }
        double durable(UUID player) { return saved.get(player); }
        FakeEconomy restart() { FakeEconomy fresh = new FakeEconomy(); saved.forEach(fresh::account); return fresh; }
        @Override public boolean available() { calls.add("available"); return true; }
        @Override public boolean hasAccount(UUID player) { calls.add("has:" + player); return memory.containsKey(player); }
        @Override public double balance(UUID player) { calls.add("balance:" + player); return memory(player); }
        @Override public boolean change(UUID player, double delta, String reason) throws Exception {
            calls.add("change:" + delta + ":" + reason); changes++;
            if (!memory.containsKey(player)) throw new IllegalStateException("Fixture cannot create accounts");
            memory.put(player, memory(player) + delta);
            Outcome outcome = outcomes.isEmpty() ? Outcome.SUCCESS : outcomes.removeFirst();
            if (outcome != Outcome.SUCCESS) saved.put(player, memory(player));
            if (afterChange != null) { FixtureAction action = afterChange; afterChange = null; action.run(); }
            if (outcome == Outcome.THROW_AFTER) throw new IllegalStateException("fixture exception after applied durable effect");
            return outcome != Outcome.FALSE_AFTER;
        }
        @Override public boolean flushConfirmed(UUID player, double expected) throws Exception {
            calls.add("flush:" + expected); flushes++;
            if (flushes == rejectFlushAt) return false;
            saved.put(player, memory(player));
            boolean confirmed = Math.abs(saved.get(player) - expected) < 1e-8;
            if (afterFlush != null) { FixtureAction action = afterFlush; afterFlush = null; action.run(); }
            return confirmed;
        }
    }
}
