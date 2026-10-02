package eu.kotori.justTeams.listeners;

import eu.kotori.justTeams.JustTeamsFabric;
import java.util.logging.Logger;

public class FabricPlayerListener {

    private static final Logger LOGGER = Logger.getLogger(FabricPlayerListener.class.getName());

    public static void register(JustTeamsFabric plugin) {
        LOGGER.info("Registering Fabric player connection events (JOIN / DISCONNECT)...");
    }
}
