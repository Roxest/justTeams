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
