package com.pixelclub.teams.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reads of pxc_teams_operations must not grow with settled history. On PRD (2026-10-07) the table held 2,253 rows /
 * 111.7 MB, pendingOperations() fetched every payload (850 ms) and boot, /team claim and /team admin pending run it on
 * the Server thread; the member guards (kick, leave, transfer, disband, bank) read every row of the team and actor.
 */
class OperationReadPathTest {
    private static final List<String> PHASES = List.of("PREPARED", "CHEST_INTENT", "PLAYER_INTENT", "CURSOR_HELD", "CLOSE_INTENT", "ESCROW", "CLAIM_INTENT",
            "FROZEN", "UNCERTAIN", "RECOVERY_INTENT", "DONE", "COMPLETED", "COMPENSATED", "FAILED", "REJECTED", "CANCELLED", "CONSUMED", "INVITED", "IDENTITY");
    private final AtomicInteger fetched = new AtomicInteger();
    private String url;
    private TeamStore store;

    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        store = new TeamStore(() -> counting(DriverManager.getConnection(url)), "donut_");
        store.createFixtureSchema();
    }

    @Test void pendingOperationsFetchesOnlyUnsettledRows() throws Exception {
        UUID actor = UUID.randomUUID();
        for (int i = 0; i < 40; i++) store.beginOperation(UUID.randomUUID().toString(), 7, actor, "CHEST", 0, i % 5 == 0 ? "CANCELLED" : "DONE", "{\"chestBefore\":\"" + "x".repeat(2_000) + "\"}");
        store.beginOperation(UUID.randomUUID().toString(), 7, actor, "CHEST", 0, "ESCROW", "{}");
        store.beginOperation(UUID.randomUUID().toString(), 0, actor, "DEPOSIT", 5, "FROZEN", "{}");
        fetched.set(0);
        assertEquals(2, store.pendingOperations().size());
        assertEquals(2, fetched.get(), "operation rows read from the database");
    }

    @Test void pendingOperationsMatchesTheFullReadOnEveryPhase() throws Exception {
        UUID actor = UUID.randomUUID();
        for (String kind : List.of("CHEST", "DEPOSIT", "WITHDRAW", "FEATURE_COST", "INVITE", "IDENTITY"))
            for (String phase : PHASES) store.beginOperation(UUID.randomUUID().toString(), 0, actor, kind, 1, phase, "{}");
        assertEquals(pxc10PendingOperations(), store.pendingOperations().stream().map(TeamStore.Operation::opid).toList());
    }

    @Test void memberGuardsFetchNoSettledRows() throws Exception {
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        Team team = store.create("Fixture", "FX", owner, "Owner", "fixture-server");
        assertTrue(store.invite(team.id(), owner, member, System.currentTimeMillis() + 100_000));
        assertTrue(store.accept(member, team.id(), 10));
        for (int i = 0; i < 30; i++) store.beginOperation(UUID.randomUUID().toString(), team.id(), member, i % 2 == 0 ? "DEPOSIT" : "WITHDRAW", 10, "DONE", "{}");
        fetched.set(0);
        assertTrue(store.kick(team.id(), member));
        assertEquals(0, fetched.get(), "operation rows read by the frozen and unsettled-money guards");
    }

    @Test void anUnsettledPaymentStillBlocksTheKick() throws Exception {
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        Team team = store.create("Fixture", "FX", owner, "Owner", "fixture-server");
        assertTrue(store.invite(team.id(), owner, member, System.currentTimeMillis() + 100_000));
        assertTrue(store.accept(member, team.id(), 10));
        store.beginOperation(UUID.randomUUID().toString(), team.id(), member, "DEPOSIT", 10, "PREPARED", "{}");
        assertFalse(store.kick(team.id(), member));
    }

    @Test void phaseIndexIsProvisionedOnce() throws Exception {
        store.provisionAdditiveTables();
        store.provisionAdditiveTables();
        try (Connection connection = DriverManager.getConnection(url)) {
            Set<String> indexes = new HashSet<>();
            for (String table : List.of("pxc_teams_operations", "PXC_TEAMS_OPERATIONS"))
                try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
                    while (rows.next()) if ("phase".equalsIgnoreCase(rows.getString("COLUMN_NAME")) && rows.getShort("ORDINAL_POSITION") == 1) indexes.add(rows.getString("INDEX_NAME"));
                }
            assertEquals(1, indexes.size(), "indexes leading with phase: " + indexes);
        }
    }

    /** pxc.10's pendingOperations, as the reference: every non-invite, non-identity row in order, terminal phases dropped in Java. */
    private List<String> pxc10PendingOperations() throws SQLException {
        Set<String> terminal = Set.of("DONE", "COMPLETED", "COMPENSATED", "FAILED", "REJECTED", "CANCELLED", "CONSUMED", "INVITED", "IDENTITY");
        List<String> pending = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement("SELECT opid,phase FROM pxc_teams_operations WHERE kind<>'INVITE' AND kind<>'IDENTITY' ORDER BY created_at,opid");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) if (!terminal.contains(rows.getString("phase"))) pending.add(rows.getString("opid"));
        }
        return pending;
    }

    /** Counts the rows that SELECTs on pxc_teams_operations hand back to Java; each one carries its payload. */
    private Connection counting(Connection connection) {
        return proxy(Connection.class, connection, (method, args) -> {
            Object value = method.invoke(connection, args);
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.startsWith("SELECT") && sql.contains("pxc_teams_operations")) {
                PreparedStatement statement = (PreparedStatement) value;
                return proxy(PreparedStatement.class, statement, (call, callArgs) -> {
                    Object result = call.invoke(statement, callArgs);
                    if (!call.getName().equals("executeQuery")) return result;
                    ResultSet rows = (ResultSet) result;
                    return proxy(ResultSet.class, rows, (next, nextArgs) -> {
                        Object more = next.invoke(rows, nextArgs);
                        if (next.getName().equals("next") && Boolean.TRUE.equals(more)) fetched.incrementAndGet();
                        return more;
                    });
                });
            }
            return value;
        });
    }

    private interface Call {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, Call call) {
        return (T) Proxy.newProxyInstance(OperationReadPathTest.class.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            try {
                return call.invoke(method, args == null ? new Object[0] : args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        });
    }
}
