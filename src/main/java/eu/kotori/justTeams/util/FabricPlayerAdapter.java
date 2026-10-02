package eu.kotori.justTeams.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FabricPlayerAdapter {

    private static final ConcurrentHashMap<UUID, Object> ADAPTED_PLAYERS = new ConcurrentHashMap<>();

    private FabricPlayerAdapter() {}

    public static Object adapt(Object serverPlayerEntity) {
        if (serverPlayerEntity == null) {
            return null;
        }
        return serverPlayerEntity;
    }
}
