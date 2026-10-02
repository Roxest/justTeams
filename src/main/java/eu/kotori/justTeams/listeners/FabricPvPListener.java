package eu.kotori.justTeams.listeners;

import eu.kotori.justTeams.JustTeamsFabric;
import java.util.logging.Logger;

public class FabricPvPListener {

    private static final Logger LOGGER = Logger.getLogger(FabricPvPListener.class.getName());

    public static void register(JustTeamsFabric plugin) {
        LOGGER.info("Registering Fabric PvP friendly-fire & team protection events...");
    }
}
