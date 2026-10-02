package eu.kotori.justTeams.listeners;

import eu.kotori.justTeams.JustTeamsFabric;
import java.util.logging.Logger;

public class FabricStatsListener {

    private static final Logger LOGGER = Logger.getLogger(FabricStatsListener.class.getName());

    public static void register(JustTeamsFabric plugin) {
        LOGGER.info("Registering Fabric player death & kill tracking events...");
    }
}
