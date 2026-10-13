package com.pixelclub.teams;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class X4TeamChatTest {
    private final String url = "jdbc:h2:mem:x4chat_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

    private Connection open() throws Exception {
        return DriverManager.getConnection(url);
    }

    private void insert(String origin, int team, String json) throws Exception {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO t_x4_team_chat (team_id, origin, member_json, spy_json, created) VALUES (?,?,?,NULL,?)")) {
            ps.setInt(1, team);
            ps.setString(2, origin);
            ps.setString(3, json);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static void started(X4TeamChat chat) throws Exception {
        chat.start();
        long until = System.currentTimeMillis() + 5000;
        while (chat.last < 0 && System.currentTimeMillis() < until) Thread.sleep(10);
        assertTrue(chat.last >= 0, "start() read the end of the table");
    }

    @Test
    void oldLinesAreNotReplayedAndOwnLinesCarryNothing() throws Exception {
        X4TeamChat a = new X4TeamChat(null, () -> DriverManager.getConnection(url), "t_", "hub");
        started(a);
        insert("shw2", 7, "\"before b started\"");
        X4TeamChat b = new X4TeamChat(null, () -> DriverManager.getConnection(url), "t_", "shw2");
        started(b);
        assertEquals(1, b.last, "b starts after the line already there");

        insert("hub", 7, "\"from hub\"");
        insert("shw2", 7, "\"from shw2\"");
        List<X4TeamChat.Line> seenByB = b.read(b.last);
        assertEquals(2, seenByB.size());
        assertEquals("\"from hub\"", seenByB.get(0).memberJson());
        assertEquals(7, seenByB.get(0).teamId());
        assertNull(seenByB.get(1).memberJson(), "b's own line advances the cursor but is not shown again");

        List<X4TeamChat.Line> seenByA = a.read(a.last);
        assertEquals(3, seenByA.size());
        assertEquals("\"before b started\"", seenByA.get(0).memberJson());
        assertNull(seenByA.get(1).memberJson());
        assertEquals("\"from shw2\"", seenByA.get(2).memberJson());
        a.stop();
        b.stop();
    }

    @Test
    void anUnsafePrefixIsRefused() {
        boolean threw = false;
        try {
            new X4TeamChat(null, () -> DriverManager.getConnection(url), "t; DROP", "hub");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
    }
}
