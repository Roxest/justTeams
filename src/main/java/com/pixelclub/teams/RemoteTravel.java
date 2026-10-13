package com.pixelclub.teams;

/**
 * X4: what to do with a team home/warp saved on another backend. Pure, so the refusals are unit-tested; TeamsCore does
 * the hand-off. Either {@code refusal} (a TeamsCore.Refusal code) or {@code dest} + {@code after} is set.
 */
record RemoteTravel(String refusal, String dest, String after) {
    /**
     * Which backend a saved team home/warp is on, or null = here. A row saved under an X4 backend name (x4.servers)
     * goes there. Rows saved before X4 all say the V1 id ("survival"): those go by world - here when the world is loaded
     * here, else the backend x4.world_servers names for it; unknown world -> null, and the local path then refuses
     * WORLD_UNAVAILABLE as before.
     */
    static String route(String me, String saved, java.util.Set<String> x4Servers, boolean worldHere, String world,
                        java.util.Map<String, String> worldServers) {
        if (saved != null && x4Servers.contains(saved)) return saved.equals(me) ? null : saved;
        if (worldHere) return null;
        String dest = worldServers.get(world);
        return dest == null || dest.equals(me) ? null : dest;
    }

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
