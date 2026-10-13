package com.pixelclub.teams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RemoteTravelTest {
    @Test
    void offOrWithoutXferIsTheV1Refusal() {
        assertEquals("REMOTE_SERVER_UNAVAILABLE", RemoteTravel.plan(false, true, false, "home", null, "shw2", "team").refusal());
        assertEquals("REMOTE_SERVER_UNAVAILABLE", RemoteTravel.plan(true, false, false, "home", null, "shw2", "team").refusal());
        assertEquals("REMOTE_SERVER_UNAVAILABLE", RemoteTravel.plan(true, true, false, "home", null, " ", "team").refusal());
    }

    private static final java.util.Set<String> SERVERS = java.util.Set.of("hub", "shw2", "shw");
    private static final java.util.Map<String, String> WORLDS = java.util.Map.of("home_world_2", "shw2", "spawn_world", "hub");

    @Test
    void anX4RowGoesToItsBackend() {
        assertEquals("shw2", RemoteTravel.route("hub", "shw2", SERVERS, false, "home_world_2", WORLDS));
        assertNull(RemoteTravel.route("shw2", "shw2", SERVERS, true, "home_world_2", WORLDS));
        // the saved backend wins over a same-named world loaded here (resource worlds exist on every backend)
        assertEquals("shw", RemoteTravel.route("shw2", "shw", SERVERS, true, "sw6", WORLDS));
    }

    @Test
    void aV1RowGoesByWorld() {
        assertNull(RemoteTravel.route("shw2", "survival", SERVERS, true, "home_world_2", WORLDS));
        assertEquals("shw2", RemoteTravel.route("hub", "survival", SERVERS, false, "home_world_2", WORLDS));
        assertNull(RemoteTravel.route("hub", "survival", SERVERS, false, "nowhere", WORLDS)); // local path refuses
        assertNull(RemoteTravel.route("hub", "survival", SERVERS, false, "spawn_world", WORLDS)); // map says here
        assertEquals("shw2", RemoteTravel.route("hub", null, SERVERS, false, "home_world_2", WORLDS));
    }

    @Test
    void homeRunsTeamHomeOnArrival() {
        RemoteTravel t = RemoteTravel.plan(true, true, false, "home", null, "shw2", "team");
        assertNull(t.refusal());
        assertEquals("shw2", t.dest());
        assertEquals("team home", t.after());
    }

    @Test
    void warpRunsTheSameWarpOnArrival() {
        RemoteTravel t = RemoteTravel.plan(true, true, false, "warp", "farm", "hub", "party");
        assertNull(t.refusal());
        assertEquals("party warp farm", t.after());
    }

    @Test
    void passwordWarpIsNeverSent() {
        assertEquals("REMOTE_WARP_LOCKED", RemoteTravel.plan(true, true, true, "warp", "vault", "hub", "team").refusal());
    }

    @Test
    void aWarpNameThatWouldBreakTheArrivalLineIsRefused() {
        assertEquals("INVALID_WARP", RemoteTravel.plan(true, true, false, "warp", "a b", "hub", "team").refusal());
        assertEquals("INVALID_WARP", RemoteTravel.plan(true, true, false, "warp", "a\nop me", "hub", "team").refusal());
        assertEquals("INVALID_WARP", RemoteTravel.plan(true, true, false, "warp", "x".repeat(260), "hub", "team").refusal());
        assertEquals("INVALID_WARP", RemoteTravel.plan(true, true, false, "warp", null, "hub", "team").refusal());
    }
}
