package com.pixelclub.teams;

/**
 * X4: what to do with a team home/warp saved on another backend. Pure, so the refusals are unit-tested; TeamsCore does
 * the hand-off. Either {@code refusal} (a TeamsCore.Refusal code) or {@code dest} + {@code after} is set.
 */
record RemoteTravel(String refusal, String dest, String after) {
    static RemoteTravel plan(boolean enabled, boolean xferLoaded, boolean locked, String kind, String name,
                             String dest, String command) {
        if (!enabled || !xferLoaded) return refuse("REMOTE_SERVER_UNAVAILABLE");
        if (locked) return refuse("REMOTE_WARP_LOCKED");
        if (dest == null || dest.isBlank()) return refuse("REMOTE_SERVER_UNAVAILABLE");
        String after = kind.equals("home") ? command + " home" : command + " warp " + name;
        if (after.length() > 256 || after.indexOf('\n') >= 0 || after.indexOf('\r') >= 0
                || !kind.equals("home") && (name == null || name.isBlank() || name.indexOf(' ') >= 0)) {
            return refuse("INVALID_WARP");
        }
        return new RemoteTravel(null, dest, after);
    }

    private static RemoteTravel refuse(String code) {
        return new RemoteTravel(code, null, null);
    }
}
