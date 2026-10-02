package eu.kotori.justTeams.util;

public final class DimensionMapper {

    private DimensionMapper() {}

    /**
     * Map Bukkit world name to Minecraft Fabric dimension identifier
     */
    public static String toFabricDimension(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) {
            return "minecraft:overworld";
        }
        String lower = worldName.toLowerCase().trim();
        switch (lower) {
            case "world":
            case "overworld":
            case "minecraft:overworld":
                return "minecraft:overworld";
            case "world_nether":
            case "nether":
            case "the_nether":
            case "minecraft:the_nether":
                return "minecraft:the_nether";
            case "world_the_end":
            case "end":
            case "the_end":
            case "minecraft:the_end":
                return "minecraft:the_end";
            default:
                if (worldName.contains(":")) {
                    return worldName;
                }
                return "minecraft:" + lower;
        }
    }

    /**
     * Map Minecraft Fabric dimension identifier to Bukkit world name
     */
    public static String toBukkitWorld(String dimensionId) {
        if (dimensionId == null || dimensionId.trim().isEmpty()) {
            return "world";
        }
        String lower = dimensionId.toLowerCase().trim();
        switch (lower) {
            case "minecraft:overworld":
            case "overworld":
                return "world";
            case "minecraft:the_nether":
            case "nether":
                return "world_nether";
            case "minecraft:the_end":
            case "the_end":
                return "world_the_end";
            default:
                if (lower.startsWith("minecraft:")) {
                    return lower.substring("minecraft:".length());
                }
                return lower;
        }
    }
}
