package com.pixelclub.teams.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Every database is a new H2 memory fixture; there is no runtime configuration lookup. */
class TeamStoreTest {
    private ConnectionFactory factory;
    private TeamStore store;
    private UUID owner;
    private Team team;

    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        factory = () -> DriverManager.getConnection(url);
        store = new TeamStore(factory, "donut_");
        store.createFixtureSchema();
        store.validateSchema();
        owner = UUID.randomUUID();
        team = store.create("Fixture", "FX", owner, "TestOwner", "original-server");
    }

    @Test void preservesDoubleNullRawColumnsAndImmutableSnapshots() throws Exception {
        double opening = 100.00000123456789;
        sql("UPDATE donut_teams SET balance=" + opening + ",alias='kept',gradient_start='#123456' WHERE id=" + team.id());
        Team read = store.team(team.id()).orElseThrow();
        assertEquals(Double.doubleToLongBits(opening), Double.doubleToLongBits(read.balance()));
        assertEquals("kept", read.field("alias"));
        assertNull(read.field("description"));
        assertEquals("original-server", read.field("home_server"));
        assertTrue(read.fields().containsKey("accept_requests"));
        assertThrows(UnsupportedOperationException.class, () -> read.fields().put("balance", 0));
        Timestamp date = (Timestamp) read.field("created_at");
        long original = date.getTime(); date.setTime(0);
        assertEquals(original, ((Timestamp) read.field("created_at")).getTime());
        assertEquals(10, store.member(owner).orElseThrow().fields().keySet().stream().filter(key -> key.startsWith("can_")).count());
    }

    @Test void missingAndWrongTypeSchemasFailClosed() throws Exception {
        assertEquals(22, store.requiredSchema().size());
        sql("ALTER TABLE donut_team_members DROP COLUMN can_demote_members");
        assertThrows(SQLException.class, store::validateSchema);
        sql("ALTER TABLE donut_team_members ADD can_demote_members BOOLEAN DEFAULT FALSE");
        sql("ALTER TABLE donut_team_warps ALTER COLUMN server_name INT");
        assertThrows(SQLException.class, store::validateSchema);
        TeamStore broken = new TeamStore(() -> { throw new SQLException("fixture unavailable"); }, "donut_");
        assertThrows(SQLException.class, () -> broken.team(1));
        assertThrows(IllegalArgumentException.class, () -> new TeamStore(factory, "x;DROP_"));
        assertThrows(IllegalArgumentException.class, () -> store.rows("teams;DROP", "id", 1));
    }

    @Test void bankChecksCurrentMembershipWithdrawalFlagBypassCapAndUnderflow() throws Exception {
        UUID member = addMember(2);
        assertTrue(store.mutateBank("fund", team.id(), owner, 100, 100, false, false).applied());
        TeamStore.BankResult denied = store.mutateBank("denied", team.id(), member, -1, 100, true, false);
        assertFalse(denied.applied()); assertEquals("WITHDRAW_DENIED", denied.reason());
        assertEquals(100, store.team(team.id()).orElseThrow().balance());
        assertTrue(store.mutateBank("bypass", team.id(), member, -1, 100, true, true).applied());
        assertFalse(store.mutateBank("cap", team.id(), member, 2, 100, false, false).applied());
        assertFalse(store.mutateBank("negative", team.id(), owner, -100, 100, true, false).applied());
        assertFalse(store.mutateBank("outsider", team.id(), UUID.randomUUID(), -1, 100, false, true).applied());
        store.updateOperation("fund", "DONE", "{}");
        store.updateOperation("bypass", "DONE", "{}");
        assertTrue(store.leave(member));
        assertFalse(store.mutateBank("left", team.id(), member, 1, 100, false, true).applied());
        assertEquals(99, store.team(team.id()).orElseThrow().balance());
    }

    @Test void opidReplayKeepsAppliedFlagAndBeforeAfterThroughFinalPhaseUpdates() throws Exception {
        store.beginOperation("intent", team.id(), owner, "DEPOSIT", 20, "PLAYER_APPLIED", "{\"playerBefore\":50,\"playerAfter\":30,\"nested\":{\"effectApplied\":false}}");
        TeamStore.BankResult first = store.mutateBank("intent", team.id(), owner, 20, 100, false, false);
        assertTrue(first.applied()); assertFalse(first.duplicate()); assertEquals("TEAM_APPLIED", first.operation().phase());
        TeamStore.Operation done = store.updateOperation("intent", "DONE", "{\"providerResult\":\"success\",\"effectApplied\":false,\"bankDelta\":-20,\"bankBalanceAfter\":0}");
        assertTrue(done.effectApplied());
        assertTrue(done.payload().contains("\"bankBalanceBefore\":0.0"));
        assertTrue(done.payload().contains("\"playerBefore\":50"));
        TeamStore.BankResult replay = store.mutateBank("intent", team.id(), owner, 20, 100, false, false);
        assertTrue(replay.applied()); assertTrue(replay.duplicate());
        assertEquals(20, replay.balance()); assertEquals(20, store.team(team.id()).orElseThrow().balance());
        assertThrows(SQLException.class, () -> store.mutateBank("intent", team.id(), owner, -20, 100, true, true));
        assertThrows(SQLException.class, () -> store.beginOperation("intent", team.id(), owner, "DEPOSIT", 21, "PREPARED", "{}"));
        assertTrue(store.pendingOperations().isEmpty());
    }

    @Test void rejectedOperationCannotApplyOnRetryAndMarkerCannotBeInvented() throws Exception {
        assertFalse(store.mutateBank("reject", team.id(), owner, 101, 100, false, false).applied());
        TeamStore.BankResult retry = store.mutateBank("reject", team.id(), owner, 101, 1000, false, false);
        assertFalse(retry.applied()); assertTrue(retry.duplicate());
        store.beginOperation("not-applied", team.id(), owner, "DEPOSIT", 1, "PREPARED", "{\"effectApplied\":true}");
        assertFalse(store.operation("not-applied").orElseThrow().effectApplied());
        assertThrows(SQLException.class, () -> store.beginOperation("forged-phase", team.id(), owner, "DEPOSIT", 1, "TEAM_APPLIED", "{}"));
        assertThrows(SQLException.class, () -> store.updateOperation("not-applied", "TEAM_APPLIED", "{}"));
        assertFalse(store.updateOperation("not-applied", "PLAYER_APPLIED", "{\"effectApplied\":true}").effectApplied());
    }

    @Test void concurrentStoresReturnOneCommittedEffectForDuplicateOpid() throws Exception {
        TeamStore another = new TeamStore(factory, "donut_");
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<TeamStore.BankResult> a = () -> store.mutateBank("concurrent", team.id(), owner, 10, 100, false, false);
            Callable<TeamStore.BankResult> b = () -> another.mutateBank("concurrent", team.id(), owner, 10, 100, false, false);
            var results = executor.invokeAll(java.util.List.of(a, b));
            TeamStore.BankResult first = results.get(0).get(10, TimeUnit.SECONDS);
            TeamStore.BankResult second = results.get(1).get(10, TimeUnit.SECONDS);
            assertTrue(first.applied()); assertTrue(second.applied());
            assertNotEquals(first.duplicate(), second.duplicate());
        }
        assertEquals(10, store.team(team.id()).orElseThrow().balance());
    }

    @Test void journalFailureRollsBackTheBankDeltaAndNewOperationTogether() throws Exception {
        sql("ALTER TABLE pxc_teams_operations ADD CONSTRAINT fixture_phase CHECK(phase <> 'TEAM_APPLIED')");
        assertThrows(SQLException.class, () -> store.mutateBank("rollback", team.id(), owner, 10, 100, false, false));
        assertEquals(0, store.team(team.id()).orElseThrow().balance());
        assertTrue(store.operation("rollback").isEmpty());
    }

    @Test void ownerRemovalAndRoleChangesCannotEvadeOwnerProtection() throws Exception {
        assertFalse(store.leave(owner)); assertFalse(store.removeMember(team.id(), owner));
        assertFalse(store.setMemberRoleAndFlags(team.id(), owner, "MEMBER", Map.of("can_withdraw", false)));
        UUID member = addMember(2);
        assertFalse(store.setMemberRoleAndFlags(team.id(), member, "OWNER", Map.of("can_withdraw", true)));
        assertTrue(store.setMemberRoleAndFlags(team.id(), member, "CO_OWNER", Map.of("can_withdraw", false, "can_edit_members", true)));
        assertFalse(store.member(member).orElseThrow().flag("can_withdraw"));
        assertTrue(store.member(member).orElseThrow().flag("can_edit_members"));
        assertTrue(store.kick(team.id(), member));
    }

    @Test void inviteAcceptChecksInviteExpiryCapacityBlacklistAndCurrentTeam() throws Exception {
        UUID member = addMember(2);
        UUID second = UUID.randomUUID();
        assertTrue(store.invite(team.id(), owner, second, System.currentTimeMillis() + 100000));
        assertFalse(store.accept(second, team.id(), 2));
        assertTrue(store.leave(member));
        assertTrue(store.accept(second, team.id(), 2));
        assertFalse(store.accept(second, team.id(), 10));
        UUID blacklisted = UUID.randomUUID();
        assertTrue(store.insertRow("team_blacklist", Map.of("team_id", team.id(), "player_uuid", blacklisted, "player_name", "Blocked", "blacklisted_by_uuid", owner, "blacklisted_by_name", "TestOwner")));
        assertFalse(store.invite(team.id(), owner, blacklisted, System.currentTimeMillis() + 100000));
        assertFalse(store.accept(UUID.randomUUID(), team.id(), 10));
        assertFalse(store.invite(team.id(), owner, UUID.randomUUID(), System.currentTimeMillis() - 1));
        UUID expired = UUID.randomUUID();
        assertTrue(store.invite(team.id(), owner, expired, System.currentTimeMillis() + 100000));
        store.updateOperation("invite:" + team.id() + ":" + expired, "INVITED", "{\"expiresEpochMillis\":1}");
        assertFalse(store.accept(expired, team.id(), 10));
        assertEquals(2, store.members(team.id()).size());
    }

    @Test void upgradeBalanceAndTierCommitTogetherOrRollBackTogether() throws Exception {
        assertTrue(store.mutateBank("fund", team.id(), owner, 100, 1000, false, false).applied());
        assertFalse(store.upgrade(team.id(), 2, 3, 10));
        assertEquals(100, store.team(team.id()).orElseThrow().balance());
        assertTrue(store.upgrade(team.id(), 1, 2, 20));
        assertEquals(80, store.team(team.id()).orElseThrow().balance());
        assertFalse(store.upgrade(team.id(), 2, 3, 81));
        sql("ALTER TABLE donut_teams ADD CONSTRAINT fixture_tier CHECK(tier < 3)");
        assertThrows(SQLException.class, () -> store.upgrade(team.id(), 2, 3, 10));
        assertEquals(80, store.team(team.id()).orElseThrow().balance());
        assertEquals(2, store.team(team.id()).orElseThrow().tier());
    }

    @Test void frozenOperationsBlockNewMoneyAndDestructiveMembershipChanges() throws Exception {
        UUID member = addMember(2);
        store.beginOperation("uncertain", team.id(), member, "WITHDRAW", 1, "PLAYER_UNKNOWN", "{}");
        store.freezeOperation("uncertain", "fixture unknown provider outcome");
        assertTrue(store.isFrozen(team.id(), owner));
        assertTrue(store.isFrozen(999, member));
        assertFalse(store.mutateBank("blocked", team.id(), owner, 10, 100, false, false).applied());
        assertFalse(store.upgrade(team.id(), 1, 2, 0));
        assertFalse(store.leave(member));
        assertFalse(store.deleteTeam(team.id(), owner));
        assertEquals("FROZEN", store.pendingOperations().getFirst().phase());
    }

    @Test void preparedFinancialOperationsBlockDestructionUntilTerminalForTeamOrActor() throws Exception {
        UUID member = addMember(2);
        store.beginOperation("active", team.id(), member, "DEPOSIT", 10, "PREPARED", "{}");
        assertFalse(store.deleteTeam(team.id(), owner));
        assertFalse(store.removeMember(team.id(), member));
        assertFalse(store.leave(member));
        assertFalse(store.kick(team.id(), member));
        store.updateOperation("active", "CANCELLED", "{}");
        assertTrue(store.removeMember(team.id(), member));
        store.beginOperation("actor-cost", 0, owner, "FEATURE_COST", 1, "PREPARED", "{}");
        assertFalse(store.deleteTeam(team.id(), owner));
        store.updateOperation("actor-cost", "COMPENSATED", "{}");
        store.beginOperation("invite-metadata", team.id(), owner, "INVITE", 0, "PREPARED", "{}");
        assertTrue(store.deleteTeam(team.id(), owner));
    }

    @Test void ownershipTransferIsAtomicAndPreservesEveryUnchangedDonorField() throws Exception {
        UUID target = addMember(2);
        store.setMemberFlags(team.id(), owner, Map.of("can_edit_members", false, "can_promote_to_co_owner", true));
        store.setMemberFlags(team.id(), target, Map.of("can_use_enderchest", false, "can_edit_members", true, "can_demote_members", true));
        sql("UPDATE donut_teams SET balance=123.000001,description='preserved',home_location='fixture-location',alias='fixture-alias' WHERE id=" + team.id());
        Map<String, Object> beforeTeam = store.team(team.id()).orElseThrow().fields();
        Map<String, Object> beforeOwner = store.member(owner).orElseThrow().fields();
        Map<String, Object> beforeTarget = store.member(target).orElseThrow().fields();
        assertFalse(store.transferOwnership(team.id(), UUID.randomUUID(), target));
        assertFalse(store.transferOwnership(team.id(), owner, owner));
        assertFalse(store.transferOwnership(team.id(), owner, UUID.randomUUID()));
        store.beginOperation("target-cost", 0, target, "FEATURE_COST", 1, "PREPARED", "{}");
        assertFalse(store.transferOwnership(team.id(), owner, target));
        store.updateOperation("target-cost", "CANCELLED", "{}");
        sql("ALTER TABLE donut_teams ADD CONSTRAINT fixture_owner CHECK(owner_uuid <> '" + target + "')");
        assertThrows(SQLException.class, () -> store.transferOwnership(team.id(), owner, target));
        assertEquals(beforeTeam, store.team(team.id()).orElseThrow().fields());
        assertEquals(beforeOwner, store.member(owner).orElseThrow().fields());
        assertEquals(beforeTarget, store.member(target).orElseThrow().fields());
        sql("ALTER TABLE donut_teams DROP CONSTRAINT fixture_owner");
        assertTrue(store.transferOwnership(team.id(), owner, target));
        Map<String, Object> expectedTeam = new LinkedHashMap<>(beforeTeam);
        expectedTeam.put("owner_uuid", target.toString());
        Map<String, Object> expectedOwner = new LinkedHashMap<>(beforeOwner);
        expectedOwner.put("role", "MEMBER"); expectedOwner.put("can_withdraw", false); expectedOwner.put("can_use_enderchest", true); expectedOwner.put("can_set_home", false); expectedOwner.put("can_use_home", true);
        Map<String, Object> expectedTarget = new LinkedHashMap<>(beforeTarget);
        expectedTarget.put("role", "OWNER");
        for (String flag : java.util.List.of("can_withdraw", "can_use_enderchest", "can_set_home", "can_use_home")) expectedTarget.put(flag, true);
        assertEquals(expectedTeam, store.team(team.id()).orElseThrow().fields());
        assertEquals(expectedOwner, store.member(owner).orElseThrow().fields());
        assertEquals(expectedTarget, store.member(target).orElseThrow().fields());
        assertFalse(store.leave(target)); assertTrue(store.leave(owner));
    }

    @Test void publicJoinEnforcesVisibilityMembershipCapacityBlacklistAndCleansRequests() throws Exception {
        UUID target = UUID.randomUUID();
        assertFalse(store.joinPublic(target, team.id(), 2));
        assertTrue(store.updateTeam(team.id(), owner, Map.of("is_public", true)));
        assertFalse(store.joinPublic(target, team.id(), 1));
        UUID otherOwner = UUID.randomUUID();
        Team other = store.create("Other", "OT", otherOwner, "OtherOwner", "fixture-server");
        assertFalse(store.joinPublic(otherOwner, team.id(), 2));
        UUID blocked = UUID.randomUUID();
        blacklist(blocked);
        assertFalse(store.joinPublic(blocked, team.id(), 2));
        store.insertRow("join_requests", Map.of("team_id", team.id(), "player_uuid", target));
        store.insertRow("join_requests", Map.of("team_id", other.id(), "player_uuid", target));
        assertTrue(store.joinPublic(target, team.id(), 2));
        assertMemberDefaults(target);
        assertTrue(store.rows("join_requests", "player_uuid", target).isEmpty());
        assertFalse(store.joinPublic(UUID.randomUUID(), team.id(), 2));
        assertFalse(store.joinPublic(target, team.id(), 3));
    }

    @Test void acceptingRequestsRequiresAuthorizedRoleAndKeepsRequestOnFailure() throws Exception {
        UUID approver = addMember(2);
        UUID target = UUID.randomUUID();
        assertFalse(store.acceptRequest(team.id(), owner, target, 3));
        store.insertRow("join_requests", Map.of("team_id", team.id(), "player_uuid", target));
        assertFalse(store.acceptRequest(team.id(), approver, target, 3));
        assertFalse(store.acceptRequest(team.id(), UUID.randomUUID(), target, 3));
        assertFalse(store.acceptRequest(team.id(), owner, target, 2));
        assertTrue(store.setMemberRoleAndFlags(team.id(), approver, "CO_OWNER", Map.of("can_withdraw", false)));
        sql("ALTER TABLE donut_team_members ADD CONSTRAINT fixture_join CHECK(player_uuid <> '" + target + "')");
        assertThrows(SQLException.class, () -> store.acceptRequest(team.id(), approver, target, 3));
        assertTrue(store.member(target).isEmpty());
        assertEquals(1, store.rows("join_requests", "player_uuid", target).size());
        sql("ALTER TABLE donut_team_members DROP CONSTRAINT fixture_join");
        assertTrue(store.acceptRequest(team.id(), approver, target, 3));
        assertMemberDefaults(target);
        assertTrue(store.rows("join_requests", "player_uuid", target).isEmpty());
        assertFalse(store.acceptRequest(team.id(), owner, target, 4));
        UUID blocked = UUID.randomUUID();
        blacklist(blocked);
        store.insertRow("join_requests", Map.of("team_id", team.id(), "player_uuid", blocked));
        assertFalse(store.acceptRequest(team.id(), owner, blocked, 4));
    }

    @Test void denyingAnInviteOnlyRemovesTargetsInviteAndCancelsItsSidecar() throws Exception {
        UUID target = UUID.randomUUID();
        assertTrue(store.invite(team.id(), owner, target, System.currentTimeMillis() + 100000));
        assertFalse(store.denyInvite(UUID.randomUUID(), team.id()));
        assertEquals(1, store.rows("team_invites", "team_id", team.id()).size());
        assertTrue(store.denyInvite(target, team.id()));
        assertTrue(store.rows("team_invites", "team_id", team.id()).isEmpty());
        assertEquals("CANCELLED", store.operation("invite:" + team.id() + ":" + target).orElseThrow().phase());
        assertFalse(store.accept(target, team.id(), 2));
        assertFalse(store.denyInvite(target, team.id()));
    }

    @Test void warpCapIdentityUniquenessAndGenericWhitelistAreEnforced() throws Exception {
        assertTrue(store.setWarp(team.id(), "base", "world:1,2,3", "original-server", null, 1));
        assertFalse(store.setWarp(team.id(), "other", "world:4,5,6", "original-server", null, 1));
        int id = ((Number) store.rows("team_warps", "team_id", team.id()).getFirst().get("id")).intValue();
        assertTrue(store.setWarp(team.id(), "BASE", "world:9,9,9", "second-server", "pw", 1));
        var warp = store.rows("team_warps", "team_id", team.id()).getFirst();
        assertEquals(id, ((Number) warp.get("id")).intValue()); assertEquals("second-server", warp.get("server_name"));
        assertThrows(SQLException.class, () -> store.create("Other", "fx", UUID.randomUUID(), "OtherOwner", "fixture"));
        assertThrows(SQLException.class, () -> store.create("fixture", "OTHER", UUID.randomUUID(), "OtherOwner", "fixture"));
        assertFalse(store.updateTeam(team.id(), UUID.randomUUID(), Map.of("name", "Denied")));
        assertThrows(IllegalArgumentException.class, () -> store.updateTeam(team.id(), owner, Map.of("balance", 999)));
        assertThrows(IllegalArgumentException.class, () -> store.insertRow("teams", Map.of("balance", 999)));
        assertThrows(IllegalArgumentException.class, () -> store.insertRow("team_custom_data", Map.of("bad_column", "x")));
        assertEquals(1, store.rows("teams", null, null).size());
    }

    @Test void createFailureRollsBackTeamMemberAndIdentityClaim() throws Exception {
        sql("ALTER TABLE donut_player_cache ADD CONSTRAINT fixture_name CHECK(player_name <> 'FailOwner')");
        assertThrows(SQLException.class, () -> store.create("Rollback", "RB", UUID.randomUUID(), "FailOwner", "fixture"));
        assertTrue(store.teamNamed("Rollback").isEmpty());
        assertNotNull(store.create("Rollback", "RB", UUID.randomUUID(), "OkayOwner", "fixture"));
    }

    @Test void nativeChestCasRetainsOriginalAndRollsBackRejectedGeneration() throws Exception {
        sql("INSERT INTO donut_team_enderchest(team_id,inventory_data) VALUES(" + team.id() + ",'immutable-donor-blob')");
        String hash = "a".repeat(64);
        assertFalse(store.saveChest(team.id(), 1, "[]", hash, 1));
        assertTrue(store.saveChest(team.id(), 1, "[]", hash, 0));
        assertFalse(store.saveChest(team.id(), 1, "[1]", hash, 0));
        assertTrue(store.saveChest(team.id(), 1, "[1]", hash, 1));
        assertThrows(SQLException.class, () -> store.saveChest(team.id(), 1, "[2]", "b".repeat(64), 2));
        sql("ALTER TABLE pxc_teams_chests ADD CONSTRAINT fixture_generation CHECK(generation < 3)");
        assertThrows(SQLException.class, () -> store.saveChest(team.id(), 1, "[2]", hash, 2));
        TeamStore.Chest chest = store.chest(team.id()).orElseThrow();
        assertEquals(2, chest.generation()); assertEquals("[1]", chest.inventoryJson());
        assertEquals("immutable-donor-blob", store.rows("team_enderchest", "team_id", team.id()).getFirst().get("inventory_data"));
    }

    @Test void deletionCascadesAllDonorDependenciesButRetainsMoneyJournal() throws Exception {
        UUID member = addMember(2);
        store.insertRow("team_custom_data", Map.of("team_id", team.id(), "data_key", "fixture", "data_value", "kept until delete"));
        store.insertRow("team_quest_progress", Map.of("team_id", team.id(), "quest_id", "fixture", "progress", 7));
        store.setWarp(team.id(), "base", "fixture", "fixture", null, 1);
        store.saveChest(team.id(), 1, "[]", null, 0);
        store.mutateBank("donor-history", team.id(), owner, 1, 100, false, false);
        store.updateOperation("donor-history", "DONE", "{}");
        assertFalse(store.deleteTeam(team.id(), member));
        assertTrue(store.deleteTeam(team.id(), owner));
        assertTrue(store.team(team.id()).isEmpty()); assertTrue(store.member(owner).isEmpty());
        for (String suffix : java.util.List.of("team_custom_data", "team_quest_progress", "team_warps")) assertTrue(store.rows(suffix, "team_id", team.id()).isEmpty());
        assertTrue(store.chest(team.id()).isEmpty());
        assertTrue(store.operation("donor-history").orElseThrow().effectApplied());
    }

    private UUID addMember(int maxMembers) throws SQLException {
        UUID member = UUID.randomUUID();
        assertTrue(store.invite(team.id(), owner, member, System.currentTimeMillis() + 100000));
        assertTrue(store.accept(member, team.id(), maxMembers));
        return member;
    }
    private void blacklist(UUID target) throws SQLException {
        store.insertRow("team_blacklist", Map.of("team_id", team.id(), "player_uuid", target, "player_name", "Blocked", "blacklisted_by_uuid", owner, "blacklisted_by_name", "TestOwner"));
    }
    private void assertMemberDefaults(UUID player) throws SQLException {
        Member member = store.member(player).orElseThrow();
        assertEquals("MEMBER", member.role());
        assertEquals(team.id(), member.teamId());
        assertNotNull(member.field("join_date"));
        Map<String, Object> flags = new LinkedHashMap<>();
        member.fields().forEach((key, value) -> { if (key.startsWith("can_")) flags.put(key, value); });
        assertEquals(Map.of("can_withdraw", false, "can_use_enderchest", true, "can_set_home", false, "can_use_home", true, "can_promote_to_co_owner", false, "can_edit_members", false, "can_edit_co_owners", false, "can_kick_members", false, "can_promote_members", false, "can_demote_members", false), flags);
    }
    private void sql(String fixtureSql) throws SQLException {
        try (Connection connection = factory.open(); Statement statement = connection.createStatement()) { statement.execute(fixtureSql); }
    }
}
