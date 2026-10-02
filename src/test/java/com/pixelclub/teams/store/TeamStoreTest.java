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
public class TeamStoreTest {
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

    @Test void memberRankingCountsCurrentRowsAndPreservesDonorFields() throws Exception {
        addMember(3); addMember(3);
        Team other = store.create("Other", "OT", UUID.randomUUID(), "OtherOwner", "other-server");
        var ranked = store.top("members", 2);
        assertEquals(java.util.List.of(team.id(), other.id()), ranked.stream().map(Team::id).toList());
        assertEquals(3, ((Number) ranked.get(0).field("member_count")).intValue());
        assertEquals(1, ((Number) ranked.get(1).field("member_count")).intValue());
        assertEquals("original-server", ranked.getFirst().field("home_server"));
        assertEquals(1, store.top("members", 1).size());
        assertTrue(store.leave(store.members(team.id()).stream().filter(member -> !member.owner()).findFirst().orElseThrow().uuid()));
        assertEquals(2, ((Number) store.top("members", 1).getFirst().field("member_count")).intValue());
    }

    @Test void teamZeroFreezeOnlyAppliesToItsActor() throws Exception {
        UUID frozenActor = UUID.randomUUID();
        UUID unrelatedCreator = UUID.randomUUID();
        store.beginOperation("actor-only", 0, frozenActor, "FEATURE_COST", 1, "PREPARED", "{}");
        store.freezeOperation("actor-only", "fixture uncertainty");
        assertTrue(store.isFrozen(0, frozenActor));
        assertFalse(store.isFrozen(0, unrelatedCreator));
        assertFalse(store.isFrozen(0, null));
        assertFalse(store.isFrozen(team.id(), owner));
        Team created = store.create("Unrelated", "UR", unrelatedCreator, "NewCreator", "fixture-server");
        assertEquals(unrelatedCreator, created.owner());
        assertTrue(store.deleteTeam(created.id(), unrelatedCreator));
        assertTrue(store.deleteTeam(team.id(), owner));
    }

    @Test void createSetsColorInItsInsertAndRollsBackAColorFailure() throws Exception {
        assertNull(team.field("color"));
        Team colored = store.create("Colored", "CL", UUID.randomUUID(), "ColorOwner", "fixture-server", "blue");
        assertEquals("blue", colored.field("color"));
        sql("ALTER TABLE donut_teams ADD CONSTRAINT fixture_color CHECK(color <> 'forbidden')");
        UUID rejectedOwner = UUID.randomUUID();
        assertThrows(SQLException.class, () -> store.create("RejectedColor", "RC", rejectedOwner, "BadColorOwner", "fixture-server", "forbidden"));
        assertTrue(store.teamNamed("RejectedColor").isEmpty());
        assertTrue(store.member(rejectedOwner).isEmpty());
        assertEquals("red", store.create("RejectedColor", "RC", rejectedOwner, "GoodColorOwner", "fixture-server", "red").field("color"));
    }

    @Test void renameEnforcesOwnerUniquenessCooldownAndFrozenStateButAllowsOwnedChargeIntent() throws Exception {
        UUID member = addMember(2);
        store.create("Taken", "TK", UUID.randomUUID(), "OtherOwner", "fixture-server");
        sql("UPDATE donut_teams SET balance=123.005 WHERE id=" + team.id());
        long now = 1_000_000;
        assertFalse(store.rename(team.id(), member, "Unauthorized", 60, now));
        assertFalse(store.rename(team.id(), owner, "taken", 60, now));
        assertTrue(store.rows("team_rename_cooldowns", "team_id", team.id()).isEmpty());
        store.beginOperation("rename-charge", team.id(), owner, "FEATURE_COST", 10, "CHARGED", "{}");
        assertTrue(store.rename(team.id(), owner, "Renamed", 60, now));
        assertEquals(now, ((Timestamp) store.rows("team_rename_cooldowns", "team_id", team.id()).getFirst().get("last_rename")).getTime());
        assertFalse(store.rename(team.id(), owner, "TooSoon", 60, now + 59_999));
        assertTrue(store.rename(team.id(), owner, "Boundary", 60, now + 60_000));
        assertEquals("Boundary", store.team(team.id()).orElseThrow().name());
        assertEquals(Double.doubleToLongBits(123.005), Double.doubleToLongBits(store.team(team.id()).orElseThrow().balance()));
        store.freezeOperation("rename-charge", "fixture unknown outcome");
        assertFalse(store.rename(team.id(), owner, "FrozenDenied", 0, now + 120_000));
        assertEquals("Boundary", store.team(team.id()).orElseThrow().name());
    }

    @Test void renameRollsBackNameWhenCooldownInsertOrUpdateFails() throws Exception {
        Map<String, Object> before = store.team(team.id()).orElseThrow().fields();
        sql("ALTER TABLE donut_team_rename_cooldowns ADD CONSTRAINT fixture_cooldown CHECK(team_id <> " + team.id() + ")");
        assertThrows(SQLException.class, () -> store.rename(team.id(), owner, "InsertFails", 0, 100_000));
        assertEquals(before, store.team(team.id()).orElseThrow().fields());
        assertTrue(store.rows("team_rename_cooldowns", "team_id", team.id()).isEmpty());
        sql("ALTER TABLE donut_team_rename_cooldowns DROP CONSTRAINT fixture_cooldown");
        assertTrue(store.rename(team.id(), owner, "Inserted", 0, 100_000));
        Map<String, Object> cooldown = store.rows("team_rename_cooldowns", "team_id", team.id()).getFirst();
        sql("ALTER TABLE donut_team_rename_cooldowns ADD CONSTRAINT fixture_time CHECK(last_rename = TIMESTAMP '" + cooldown.get("last_rename") + "')");
        Map<String, Object> inserted = store.team(team.id()).orElseThrow().fields();
        assertThrows(SQLException.class, () -> store.rename(team.id(), owner, "UpdateFails", 0, 100_001));
        assertEquals(inserted, store.team(team.id()).orElseThrow().fields());
        assertEquals(cooldown, store.rows("team_rename_cooldowns", "team_id", team.id()).getFirst());
    }

    @Test void allyRequestsAcceptanceDenialAndRemovalEnforceRolesAndCanonicalPairs() throws Exception {
        UUID otherOwner = UUID.randomUUID();
        Team other = store.create("Other", "OT", otherOwner, "OtherOwner", "fixture-server");
        UUID thirdOwner = UUID.randomUUID();
        Team third = store.create("Third", "TH", thirdOwner, "ThirdOwner", "fixture-server");
        UUID co = addMember(2);
        assertFalse(store.allyRequest(team.id(), team.id(), owner, 1));
        assertFalse(store.allyRequest(team.id(), 999, owner, 1));
        assertFalse(store.allyRequest(team.id(), other.id(), co, 1));
        assertFalse(store.allyRequest(team.id(), other.id(), otherOwner, 1));
        assertTrue(store.allyRequest(other.id(), team.id(), otherOwner, 1));
        assertFalse(store.allyRequest(other.id(), team.id(), otherOwner, 1));
        var request = store.rows("team_ally_requests", "sender_team_id", other.id()).getFirst();
        assertEquals(otherOwner.toString(), request.get("requester_uuid"));
        assertFalse(store.allyAccept(other.id(), team.id(), otherOwner, 1));
        assertFalse(store.allyAccept(other.id(), team.id(), co, 1));
        assertTrue(store.setMemberRoleAndFlags(team.id(), co, "CO_OWNER", Map.of("can_withdraw", false)));
        assertTrue(store.allyAccept(other.id(), team.id(), co, 1));
        assertFalse(store.allyAccept(other.id(), team.id(), owner, 1));
        var ally = store.rows("team_allies", null, null).getFirst();
        assertEquals(team.id(), ((Number) ally.get("team_id_1")).intValue());
        assertEquals(other.id(), ((Number) ally.get("team_id_2")).intValue());
        assertEquals(1, store.rows("team_allies", null, null).size());
        assertTrue(store.rows("team_ally_requests", "sender_team_id", other.id()).isEmpty());
        assertFalse(store.allyRequest(other.id(), third.id(), otherOwner, 1));
        assertTrue(store.allyRequest(third.id(), team.id(), thirdOwner, 1));
        assertFalse(store.allyAccept(third.id(), team.id(), owner, 1));
        assertFalse(store.allyDeny(third.id(), team.id(), thirdOwner));
        assertTrue(store.allyDeny(third.id(), team.id(), co));
        assertFalse(store.allyDeny(third.id(), team.id(), owner));
        assertFalse(store.allyRemove(team.id(), other.id(), otherOwner));
        assertTrue(store.allyRemove(team.id(), other.id(), co));
        assertFalse(store.allyRemove(team.id(), other.id(), owner));
        assertTrue(store.rows("team_allies", null, null).isEmpty());
    }

    @Test void allyAcceptanceChecksBothCapacitiesAndRollsBackItsInsertOnRequestDeleteFailure() throws Exception {
        UUID otherOwner = UUID.randomUUID();
        Team other = store.create("Other", "OT", otherOwner, "OtherOwner", "fixture-server");
        UUID thirdOwner = UUID.randomUUID();
        Team third = store.create("Third", "TH", thirdOwner, "ThirdOwner", "fixture-server");
        store.insertRow("team_allies", Map.of("team_id_1", other.id(), "team_id_2", third.id()));
        assertTrue(store.allyRequest(team.id(), other.id(), owner, 2));
        assertFalse(store.allyAccept(team.id(), other.id(), otherOwner, 1));
        assertEquals(1, store.rows("team_ally_requests", "sender_team_id", team.id()).size());
        assertTrue(store.allyAccept(team.id(), other.id(), otherOwner, 2));
        assertEquals(2, store.rows("team_allies", null, null).size());
        UUID fourthOwner = UUID.randomUUID();
        Team fourth = store.create("Fourth", "FO", fourthOwner, "FourthOwner", "fixture-server");
        assertTrue(store.allyRequest(team.id(), fourth.id(), owner, 2));
        assertFalse(store.allyAccept(team.id(), fourth.id(), fourthOwner, 1));
        assertEquals(1, store.rows("team_ally_requests", "sender_team_id", team.id()).size());
        assertTrue(store.allyDeny(team.id(), fourth.id(), fourthOwner));
        assertTrue(store.allyRequest(fourth.id(), team.id(), fourthOwner, 2));
        sql("CREATE TRIGGER fixture_ally_delete BEFORE DELETE ON donut_team_ally_requests FOR EACH ROW CALL 'com.pixelclub.teams.store.TeamStoreTest$RejectAllyRequestDelete'");
        assertThrows(SQLException.class, () -> store.allyAccept(fourth.id(), team.id(), owner, 2));
        assertEquals(2, store.rows("team_allies", null, null).size());
        assertEquals(1, store.rows("team_ally_requests", "sender_team_id", fourth.id()).size());
    }

    @Test void blacklistUsesElevatedTeamScopeAndRetainsExactDonorIdentityColumns() throws Exception {
        UUID member = addMember(2);
        UUID target = UUID.randomUUID();
        assertFalse(store.blacklist(team.id(), member, "Member", target, "Target", "fixture"));
        assertFalse(store.blacklist(team.id(), UUID.randomUUID(), "Outsider", target, "Target", "fixture"));
        assertFalse(store.blacklist(team.id(), owner, "TestOwner", owner, "TestOwner", "fixture"));
        assertFalse(store.blacklist(team.id(), owner, "TestOwner", member, "Member", "fixture"));
        assertTrue(store.setMemberRoleAndFlags(team.id(), member, "CO_OWNER", Map.of("can_withdraw", false)));
        UUID otherOwner = UUID.randomUUID();
        store.create("Other", "OT", otherOwner, "OtherOwner", "fixture-server");
        assertTrue(store.blacklist(team.id(), member, "Member", otherOwner, "OtherOwner", "reason ' retained"));
        assertFalse(store.blacklist(team.id(), owner, "TestOwner", otherOwner, "OtherOwner", "duplicate"));
        var row = store.rows("team_blacklist", "team_id", team.id()).getFirst();
        assertEquals(otherOwner.toString(), row.get("player_uuid")); assertEquals("OtherOwner", row.get("player_name"));
        assertEquals(member.toString(), row.get("blacklisted_by_uuid")); assertEquals("Member", row.get("blacklisted_by_name"));
        assertEquals("reason ' retained", row.get("reason")); assertNotNull(row.get("blacklisted_at"));
        assertEquals(1, store.deleteRows("team_blacklist", Map.of("team_id", team.id(), "player_uuid", otherOwner)));
    }

    @Test void pointAdjustmentsUseOwnerCasRejectNegativeAndOverflowAndPreserveMoney() throws Exception {
        Map<String, Object> opening = store.team(team.id()).orElseThrow().fields();
        assertFalse(store.adjustPoints(team.id(), UUID.randomUUID(), 10));
        assertFalse(store.adjustPoints(team.id(), owner, -1));
        assertTrue(store.adjustPoints(team.id(), owner, 10));
        assertTrue(store.adjustPoints(team.id(), owner, -4));
        assertEquals(6L, store.team(team.id()).orElseThrow().field("points"));
        Map<String, Object> expected = new LinkedHashMap<>(opening); expected.put("points", 6L);
        assertEquals(expected, store.team(team.id()).orElseThrow().fields());
        sql("UPDATE donut_teams SET points=" + Long.MAX_VALUE + " WHERE id=" + team.id());
        assertFalse(store.adjustPoints(team.id(), owner, 1));
        assertEquals(Long.MAX_VALUE, store.team(team.id()).orElseThrow().field("points"));
        assertFalse(store.adjustPoints(team.id(), owner, Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, store.team(team.id()).orElseThrow().field("points"));
        assertEquals(opening.get("balance"), store.team(team.id()).orElseThrow().field("balance"));
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

    @Test void legacySqlInviteIsUnboundedByDefaultPinsOriginalTimestampAndConsumesOnce() throws Exception {
        UUID target = UUID.randomUUID();
        Timestamp original = new Timestamp(0);
        legacyInvite(target, original);
        assertTrue(store.accept(target, team.id(), 2));
        TeamStore.Operation receipt = store.operation("invite:" + team.id() + ":" + target).orElseThrow();
        assertEquals("CONSUMED", receipt.phase()); assertFalse(receipt.effectApplied());
        assertTrue(Json.booleanValue(receipt.payload(), "legacy"));
        assertEquals(Json.string(original.toInstant().toString()), Json.rawValue(receipt.payload(), "created_at"));
        assertEquals(Long.MAX_VALUE, Json.longValue(receipt.payload(), "expiresEpochMillis", 0));
        assertMemberDefaults(target);
        assertTrue(store.rows("team_invites", "player_uuid", target).isEmpty());
        assertFalse(store.accept(target, team.id(), 3));
        assertTrue(store.leave(target));
        assertFalse(store.accept(target, team.id(), 3));
        UUID noTimestamp = UUID.randomUUID();
        legacyInvite(noTimestamp, null);
        assertTrue(store.accept(noTimestamp, team.id(), 2));
        assertEquals("null", Json.rawValue(store.operation("invite:" + team.id() + ":" + noTimestamp).orElseThrow().payload(), "created_at"));
    }

    @Test void optionalLegacyLifetimeUsesOriginalDateAndNeverRenewsExpiredOrNativeInvites() throws Exception {
        UUID expired = UUID.randomUUID();
        Timestamp old = new Timestamp(System.currentTimeMillis() - 10000);
        legacyInvite(expired, old);
        Map<String, Object> before = store.rows("team_invites", "player_uuid", expired).getFirst();
        assertFalse(store.accept(expired, team.id(), 2, 1000));
        assertFalse(store.accept(expired, team.id(), 2, 1000));
        assertTrue(store.operation("invite:" + team.id() + ":" + expired).isEmpty());
        assertEquals(before, store.rows("team_invites", "player_uuid", expired).getFirst());
        UUID valid = UUID.randomUUID();
        Timestamp created = new Timestamp(System.currentTimeMillis() - 50);
        legacyInvite(valid, created);
        assertTrue(store.accept(valid, team.id(), 2, 120000));
        assertEquals(created.getTime() + 120000, Json.longValue(store.operation("invite:" + team.id() + ":" + valid).orElseThrow().payload(), "expiresEpochMillis", 0));
        assertTrue(store.leave(valid));
        UUID nativeTarget = UUID.randomUUID();
        long explicitExpiry = System.currentTimeMillis() + 120000;
        assertTrue(store.invite(team.id(), owner, nativeTarget, explicitExpiry));
        assertTrue(store.accept(nativeTarget, team.id(), 2, 0));
        assertEquals(explicitExpiry, Json.longValue(store.operation("invite:" + team.id() + ":" + nativeTarget).orElseThrow().payload(), "expiresEpochMillis", 0));
    }

    @Test void legacyInviteAdmissionRollsBackJournalAndMemberWhileKeepingTheDonorRow() throws Exception {
        UUID target = UUID.randomUUID();
        legacyInvite(target, new Timestamp(123456));
        Map<String, Object> before = store.rows("team_invites", "player_uuid", target).getFirst();
        assertFalse(store.accept(target, team.id(), 1));
        assertTrue(store.operation("invite:" + team.id() + ":" + target).isEmpty());
        sql("ALTER TABLE donut_team_members ADD CONSTRAINT fixture_legacy_join CHECK(player_uuid <> '" + target + "')");
        assertThrows(SQLException.class, () -> store.accept(target, team.id(), 2));
        assertTrue(store.member(target).isEmpty());
        assertTrue(store.operation("invite:" + team.id() + ":" + target).isEmpty());
        assertEquals(before, store.rows("team_invites", "player_uuid", target).getFirst());
        sql("ALTER TABLE donut_team_members DROP CONSTRAINT fixture_legacy_join");
        assertTrue(store.accept(target, team.id(), 2));
    }

    @Test void legacyAdmissionRejectsMismatchedAddonIdentity() throws Exception {
        UUID target = UUID.randomUUID();
        legacyInvite(target, new Timestamp(0));
        store.beginOperation("invite:" + team.id() + ":" + target, 0, target, "INVITE", 0, "INVITED", "{\"expiresEpochMillis\":9223372036854775807}");
        assertThrows(SQLException.class, () -> store.accept(target, team.id(), 2));
        assertTrue(store.member(target).isEmpty());
        assertEquals(1, store.rows("team_invites", "player_uuid", target).size());
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

    @Test void chestImportPreservesUtf8OriginalsAndReplaysOnlyUnchangedGenerationOneRows() throws Exception {
        Team other = store.create("Other", "OT", UUID.randomUUID(), "OtherOwner", "fixture-server");
        donorChest(team.id(), "old-\u03c0\r\n"); donorChest(other.id(), "abc");
        var originals = store.rows("team_enderchest", null, null);
        TeamStore.ChestSeed first = new TeamStore.ChestSeed(team.id(), 1, "[]", "0accf1235b9e5d527399d98d929fb4fede5249a324ef3adf85cc8d1527665ee7");
        TeamStore.ChestSeed second = new TeamStore.ChestSeed(other.id(), 1, "[null]", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertTrue(store.importChests(java.util.List.of(second, first)));
        TeamStore.Chest imported = store.chest(team.id()).orElseThrow();
        assertEquals(1, imported.generation()); assertEquals(first.originalSha256(), imported.originalSha256());
        assertTrue(store.importChests(java.util.List.of(first, second)));
        assertEquals(imported, store.chest(team.id()).orElseThrow());
        assertFalse(store.importChests(java.util.List.of(new TeamStore.ChestSeed(team.id(), 2, "[]", first.originalSha256()))));
        assertFalse(store.importChests(java.util.List.of(new TeamStore.ChestSeed(team.id(), 1, "[1]", first.originalSha256()))));
        assertTrue(store.saveChest(team.id(), 1, "[]", first.originalSha256(), 1));
        assertFalse(store.importChests(java.util.List.of(first, second)));
        assertEquals(2, store.chest(team.id()).orElseThrow().generation());
        assertEquals(originals, store.rows("team_enderchest", null, null));
    }

    @Test void chestImportRejectsHashDriftMissingEmptyDuplicateAndReservedSourcesBeforeAnyInsert() throws Exception {
        Team other = store.create("Other", "OT", UUID.randomUUID(), "OtherOwner", "fixture-server");
        donorChest(team.id(), "abc");
        String abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        TeamStore.ChestSeed first = new TeamStore.ChestSeed(team.id(), 1, "[]", abc);
        TeamStore.ChestSeed second = new TeamStore.ChestSeed(other.id(), 1, "[]", abc);
        assertFalse(store.importChests(java.util.List.of(first, first)));
        assertFalse(store.importChests(java.util.List.of(first, second)));
        donorChest(other.id(), "changed");
        assertFalse(store.importChests(java.util.List.of(first, second)));
        sql("UPDATE donut_team_enderchest SET inventory_data='' WHERE team_id=" + other.id());
        String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertFalse(store.importChests(java.util.List.of(first, new TeamStore.ChestSeed(other.id(), 1, "[]", emptyHash))));
        UUID reservedOwner = UUID.randomUUID();
        sql("INSERT INTO donut_teams(id,name,tag,owner_uuid) VALUES(2147483647,'ReservedFixture','RS','" + reservedOwner + "')");
        donorChest(Integer.MAX_VALUE, "abc");
        assertFalse(store.importChests(java.util.List.of(first, new TeamStore.ChestSeed(Integer.MAX_VALUE, 1, "[]", abc))));
        assertTrue(store.chest(team.id()).isEmpty()); assertTrue(store.chest(other.id()).isEmpty());
        assertTrue(store.chest(Integer.MAX_VALUE).isEmpty());
    }

    @Test void chestImportRollsBackTheWholeBatchWhenALaterInsertFails() throws Exception {
        Team other = store.create("Other", "OT", UUID.randomUUID(), "OtherOwner", "fixture-server");
        donorChest(team.id(), "abc"); donorChest(other.id(), "abc");
        var originals = store.rows("team_enderchest", null, null);
        String abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        sql("ALTER TABLE pxc_teams_chests ADD CONSTRAINT fixture_batch CHECK(team_id <> " + other.id() + ")");
        assertThrows(SQLException.class, () -> store.importChests(java.util.List.of(new TeamStore.ChestSeed(team.id(), 1, "[]", abc), new TeamStore.ChestSeed(other.id(), 1, "[]", abc))));
        assertTrue(store.chest(team.id()).isEmpty()); assertTrue(store.chest(other.id()).isEmpty());
        assertEquals(originals, store.rows("team_enderchest", null, null));
    }

    @Test void adminBalanceResetIsJournaledWithoutAPlayerLegAndReplaysWithoutReapplying() throws Exception {
        sql("UPDATE donut_teams SET balance=123.005 WHERE id=" + team.id());
        Map<String, Object> before = store.team(team.id()).orElseThrow().fields();
        UUID admin = UUID.randomUUID();
        double reset = 1234567890.125;
        assertTrue(store.setBalanceAdmin(team.id(), owner, admin, 123.005, reset, "admin-reset"));
        Map<String, Object> expected = new LinkedHashMap<>(before); expected.put("balance", reset);
        assertEquals(expected, store.team(team.id()).orElseThrow().fields());
        assertTrue(store.member(admin).isEmpty());
        TeamStore.Operation receipt = store.operation("admin-reset").orElseThrow();
        assertEquals("BANK", receipt.kind()); assertEquals("DONE", receipt.phase()); assertTrue(receipt.effectApplied());
        assertEquals(admin, receipt.player()); assertTrue(Json.booleanValue(receipt.payload(), "admin"));
        assertEquals(Json.string(admin.toString()), Json.rawValue(receipt.payload(), "actor"));
        assertEquals(123.005, Json.doubleValue(receipt.payload(), "bankBalanceBefore", 0));
        assertEquals(reset, Json.doubleValue(receipt.payload(), "bankBalanceAfter", 0));
        assertTrue(store.pendingOperations().isEmpty());
        assertTrue(store.setBalanceAdmin(team.id(), owner, admin, reset, 5, "later-reset"));
        assertTrue(store.setBalanceAdmin(team.id(), owner, admin, 123.005, reset, "admin-reset"));
        assertEquals(5, store.team(team.id()).orElseThrow().balance());
        assertThrows(SQLException.class, () -> store.setBalanceAdmin(team.id(), owner, admin, 123.005, reset + 1, "admin-reset"));
        assertThrows(SQLException.class, () -> store.setBalanceAdmin(team.id(), owner, UUID.randomUUID(), 123.005, reset, "admin-reset"));
        assertThrows(SQLException.class, () -> store.setBalanceAdmin(team.id(), UUID.randomUUID(), admin, 123.005, reset, "admin-reset"));
    }

    @Test void adminResetRefusesStaleUnauthorizedInvalidAndUnsettledOrFrozenInputsWithoutAReceipt() throws Exception {
        UUID admin = UUID.randomUUID();
        assertFalse(store.setBalanceAdmin(team.id(), UUID.randomUUID(), admin, 0, 10, "wrong-owner"));
        assertFalse(store.setBalanceAdmin(team.id(), owner, admin, 1, 10, "stale"));
        assertFalse(store.setBalanceAdmin(team.id(), owner, admin, Double.NaN, 10, "nan-before"));
        assertFalse(store.setBalanceAdmin(team.id(), owner, admin, 0, Double.POSITIVE_INFINITY, "infinite"));
        assertFalse(store.setBalanceAdmin(team.id(), owner, admin, 0, -1, "negative"));
        store.beginOperation("pending", team.id(), owner, "DEPOSIT", 1, "PREPARED", "{}");
        assertFalse(store.setBalanceAdmin(team.id(), owner, admin, 0, 10, "unsettled"));
        store.freezeOperation("pending", "fixture unknown");
        assertFalse(store.setBalanceAdmin(team.id(), owner, admin, 0, 10, "frozen"));
        for (String opid : java.util.List.of("wrong-owner", "stale", "nan-before", "infinite", "negative", "unsettled", "frozen")) assertTrue(store.operation(opid).isEmpty());
        assertEquals(0, store.team(team.id()).orElseThrow().balance());
    }

    @Test void adminResetRollsBackTheBalanceAndAuditTogetherOnJournalFailure() throws Exception {
        UUID admin = UUID.randomUUID();
        Map<String, Object> before = store.team(team.id()).orElseThrow().fields();
        sql("ALTER TABLE pxc_teams_operations ADD CONSTRAINT fixture_admin_done CHECK(phase <> 'DONE')");
        assertThrows(SQLException.class, () -> store.setBalanceAdmin(team.id(), owner, admin, 0, 10, "admin-rollback"));
        assertEquals(before, store.team(team.id()).orElseThrow().fields());
        assertTrue(store.operation("admin-rollback").isEmpty());
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
    private void legacyInvite(UUID target, Timestamp created) throws SQLException {
        try (Connection connection = factory.open(); var statement = connection.prepareStatement("INSERT INTO donut_team_invites(team_id,player_uuid,inviter_uuid,created_at) VALUES(?,?,?,?)")) {
            statement.setInt(1, team.id()); statement.setString(2, target.toString()); statement.setString(3, owner.toString()); statement.setTimestamp(4, created); statement.executeUpdate();
        }
    }
    private void donorChest(int teamId, String original) throws SQLException {
        try (Connection connection = factory.open(); var statement = connection.prepareStatement("INSERT INTO donut_team_enderchest(team_id,inventory_data) VALUES(?,?)")) {
            statement.setInt(1, teamId); statement.setString(2, original); statement.executeUpdate();
        }
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
    public static final class RejectAllyRequestDelete implements org.h2.api.Trigger {
        @Override public void init(Connection connection, String schema, String trigger, String table, boolean before, int type) {}
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException { throw new SQLException("Fixture request delete failure"); }
        @Override public void close() {}
        @Override public void remove() {}
    }
}
