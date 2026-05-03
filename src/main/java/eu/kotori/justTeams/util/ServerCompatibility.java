package eu.kotori.justTeams.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class ServerCompatibility {
    
    private static final String SERVER_NAME = Bukkit.getServer().getName();
    private static final String SERVER_VERSION = Bukkit.getServer().getVersion();
    private static final boolean IS_FOLIA;
    private static final boolean IS_PAPER;
    private static final boolean IS_SPIGOT;
    private static final ServerType SERVER_TYPE;
    
    static {
        String name = SERVER_NAME.toLowerCase();
        IS_FOLIA = name.contains("folia");
        IS_PAPER = name.contains("paper") || name.contains("purpur") || name.contains("airplane") || name.contains("pufferfish") || name.contains("arclight") || name.contains("fabric");
        IS_SPIGOT = name.contains("spigot") || name.contains("craftbukkit");
        
        if (IS_FOLIA) {
            SERVER_TYPE = ServerType.FOLIA;
        } else if (IS_PAPER) {
            SERVER_TYPE = ServerType.PAPER;
        } else if (IS_SPIGOT) {
            SERVER_TYPE = ServerType.SPIGOT;
        } else {
            SERVER_TYPE = ServerType.BUKKIT;
        }
    }
    
    public static boolean isFolia() {
        return IS_FOLIA;
    }
    
    public static boolean isPaper() {
        return IS_PAPER;
    }
    

    public static boolean isSpigot() {
        return IS_SPIGOT;
    }
    

    public static ServerType getServerType() {
        return SERVER_TYPE;
    }
    

    public static String getServerName() {
        return SERVER_NAME;
    }
    

    public static String getServerVersion() {
        return SERVER_VERSION;
    }
    

    public static boolean supportsRegionThreading() {
        return IS_FOLIA;
    }
    

    public static boolean supportsPaperAPI() {
        return IS_PAPER || IS_FOLIA;
    }
    

    public static void logCompatibilityInfo(Plugin plugin) {
        plugin.getLogger().info("=".repeat(50));
        plugin.getLogger().info("Server Compatibility Information:");
        plugin.getLogger().info("  Server: " + SERVER_NAME);
        plugin.getLogger().info("  Version: " + SERVER_VERSION);
        plugin.getLogger().info("  Type: " + SERVER_TYPE);
        plugin.getLogger().info("  Folia Support: " + (IS_FOLIA ? "ENABLED" : "Not Running Folia"));
        plugin.getLogger().info("  Paper API: " + (supportsPaperAPI() ? "Available" : "Not Available"));
        plugin.getLogger().info("  Region Threading: " + (supportsRegionThreading() ? "ENABLED" : "Standard Threading"));
        plugin.getLogger().info("=".repeat(50));
    }
    
    public enum ServerType {
        FOLIA("Folia", "Region-threaded server"),
        PAPER("Paper", "High-performance fork"),
        SPIGOT("Spigot", "Standard performance"),
        BUKKIT("Bukkit", "Base implementation");
        
        private final String displayName;
        private final String description;
        
        ServerType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String getDescription() {
            return description;
        }
        
        @Override
        public String toString() {
            return displayName + " (" + description + ")";
        }
    }
}
