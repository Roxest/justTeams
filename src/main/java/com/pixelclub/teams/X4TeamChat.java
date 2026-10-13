package com.pixelclub.teams;

import com.pixelclub.teams.store.ConnectionFactory;
import com.pixelclub.teams.store.Member;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * X4 (x4.team_chat, off = nothing here runs): team chat reaches members on the other backends. The sender's backend
 * renders the line as today, delivers it locally, and appends it to {@code <prefix>x4_team_chat}; every backend polls
 * that table once a second for rows from other backends and shows them to its own online members (and spies). Lines
 * are display only - nothing is granted - so a lost or late line is the only failure. Rows older than 10 min go.
 * All JDBC on one daemon thread; delivery on the server thread.
 */
final class X4TeamChat {
    record Line(long id, int teamId, String memberJson, String spyJson) {}

    private final MinecraftServer server;
    private final ConnectionFactory db;
    private final String table;
    private final String me;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pxc-teams-x4chat");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean polling = new AtomicBoolean();
    /** Highest row id already seen; -1 until start() read the current end (old lines are never replayed). */
    volatile long last = -1;

    X4TeamChat(MinecraftServer server, ConnectionFactory db, String prefix, String me) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9_]*")) throw new IllegalArgumentException("Unsafe table prefix");
        this.server = server;
        this.db = db;
        this.table = prefix + "x4_team_chat";
        this.me = me;
    }

    void start() {
        io.execute(() -> {
            try (Connection c = db.open(); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS " + table + " (id BIGINT AUTO_INCREMENT PRIMARY KEY, team_id INT NOT NULL,"
                        + " origin VARCHAR(64) NOT NULL, member_json MEDIUMTEXT NOT NULL, spy_json MEDIUMTEXT NULL,"
                        + " created BIGINT NOT NULL)");
                try (ResultSet r = s.executeQuery("SELECT COALESCE(MAX(id),0) FROM " + table)) {
                    last = r.next() ? r.getLong(1) : 0;
                }
                TeamsMod.LOG.info("[pxc-teams] X4 team chat on: {} as {} from id {}", table, me, last);
            } catch (SQLException e) {
                TeamsMod.LOG.error("[pxc-teams] X4 team chat could not start (lines stay on this backend): {}", e.toString());
            }
        });
    }

    /** Server thread: queue one rendered line for the other backends. */
    void publish(int teamId, Component asMember, Component asSpy) {
        String member = Component.Serializer.toJson(asMember, server.registryAccess());
        String spy = asSpy == null ? null : Component.Serializer.toJson(asSpy, server.registryAccess());
        io.execute(() -> {
            try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + table + " (team_id, origin, member_json, spy_json, created) VALUES (?,?,?,?,?)")) {
                ps.setInt(1, teamId);
                ps.setString(2, me);
                ps.setString(3, member);
                ps.setString(4, spy);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                TeamsMod.LOG.warn("[pxc-teams] X4 team chat line for team {} not sent to other backends: {}", teamId, e.toString());
            }
        });
    }

    /**
     * Server thread, once a second. members: team id -> member UUIDs (read on the IO thread); spy: may this player
     * read every team's chat (checked on the server thread at delivery).
     */
    void poll(MemberLookup members, Predicate<ServerPlayer> canRead, Predicate<ServerPlayer> spy) {
        if (last < 0 || !polling.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                List<Line> lines = read(last);
                if (lines.isEmpty()) return;
                last = lines.get(lines.size() - 1).id();
                List<Set<UUID>> to = new ArrayList<>();
                for (Line l : lines) {
                    Set<UUID> ids = new HashSet<>();
                    if (l.memberJson() == null) { // own line: nothing to deliver here
                        to.add(ids);
                        continue;
                    }
                    for (Member m : members.members(l.teamId())) ids.add(m.uuid());
                    to.add(ids);
                }
                server.execute(() -> deliver(lines, to, canRead, spy));
            } catch (Exception e) {
                TeamsMod.LOG.warn("[pxc-teams] X4 team chat poll failed (retried next second): {}", e.toString());
            } finally {
                polling.set(false);
            }
        });
    }

    /** IO thread, every few minutes. */
    void prune() {
        io.execute(() -> {
            try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE created < ?")) {
                ps.setLong(1, System.currentTimeMillis() - 600_000L);
                ps.executeUpdate();
            } catch (SQLException e) {
                TeamsMod.LOG.warn("[pxc-teams] X4 team chat prune failed: {}", e.toString());
            }
        });
    }

    void stop() {
        io.shutdownNow();
    }

    List<Line> read(long after) throws SQLException {
        List<Line> out = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, team_id, origin, member_json, spy_json FROM " + table + " WHERE id > ? ORDER BY id LIMIT 200")) {
            ps.setLong(1, after);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    long id = r.getLong(1);
                    // own lines are skipped but still advance the cursor
                    if (me.equals(r.getString(3))) out.add(new Line(id, -1, null, null));
                    else out.add(new Line(id, r.getInt(2), r.getString(4), r.getString(5)));
                }
            }
            if (out.isEmpty()) {
                // review LOW: AUTO_INCREMENT restarted (TRUNCATE, MySQL < 8 after the table emptied) -> follow it down
                try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT COALESCE(MAX(id),0) FROM " + table)) {
                    if (r.next() && r.getLong(1) < after) last = r.getLong(1);
                }
            }
        }
        return out;
    }

    private void deliver(List<Line> lines, List<Set<UUID>> to, Predicate<ServerPlayer> canRead, Predicate<ServerPlayer> spy) {
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.memberJson() == null) continue;
            Component member, asSpy;
            try {
                member = Component.Serializer.fromJson(l.memberJson(), server.registryAccess());
                asSpy = l.spyJson() == null ? null : Component.Serializer.fromJson(l.spyJson(), server.registryAccess());
            } catch (RuntimeException e) {
                TeamsMod.LOG.warn("[pxc-teams] X4 team chat line {} unreadable: {}", l.id(), e.toString());
                continue;
            }
            if (member == null) continue;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!canRead.test(p)) continue;
                if (to.get(i).contains(p.getUUID())) p.sendSystemMessage(member);
                else if (asSpy != null && spy.test(p)) p.sendSystemMessage(asSpy);
            }
        }
    }

    @FunctionalInterface
    interface MemberLookup {
        List<Member> members(int teamId) throws Exception;
    }
}
