package eu.kotori.justTeams.config;

import eu.kotori.justTeams.JustTeamsFabric;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.yaml.snakeyaml.Yaml;

public class FabricConfigManager {

    private static final Logger LOGGER = Logger.getLogger(FabricConfigManager.class.getName());
    private final JustTeamsFabric plugin;
    private Map<String, Object> configMap;

    public FabricConfigManager(JustTeamsFabric plugin) {
        this.plugin = plugin;
        this.load();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        File configFile = plugin.getDataFolder().resolve("config.yml").toFile();
        if (!configFile.exists()) {
            LOGGER.warning("config.yml not found, using empty config.");
            this.configMap = Collections.emptyMap();
            return;
        }

        try (InputStream in = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                this.configMap = (Map<String, Object>) loaded;
                LOGGER.info("✓ Successfully parsed config.yml with SnakeYAML!");
            } else {
                this.configMap = Collections.emptyMap();
            }
        } catch (Exception e) {
            LOGGER.severe("Error parsing config.yml: " + e.getMessage());
            this.configMap = Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    public Object get(String path) {
        if (configMap == null || path == null || path.isEmpty()) return null;
        String[] parts = path.split("\\.");
        Map<String, Object> current = configMap;
        for (int i = 0; i < parts.length - 1; i++) {
            Object obj = current.get(parts[i]);
            if (obj instanceof Map) {
                current = (Map<String, Object>) obj;
            } else {
                return null;
            }
        }
        return current.get(parts[parts.length - 1]);
    }

    public String getString(String path, String defaultValue) {
        Object val = get(path);
        return val != null ? String.valueOf(val) : defaultValue;
    }

    public int getInt(String path, int defaultValue) {
        Object val = get(path);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    public double getDouble(String path, double defaultValue) {
        Object val = get(path);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    public boolean getBoolean(String path, boolean defaultValue) {
        Object val = get(path);
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        if (val instanceof String) {
            return Boolean.parseBoolean((String) val);
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path) {
        Object val = get(path);
        if (val instanceof List) {
            return (List<String>) val;
        }
        return Collections.emptyList();
    }

    // Typed configuration getters
    public double getTeamCreationCost() {
        double cost = getDouble("economy.costs.team_creation", -1.0);
        if (cost < 0) {
            cost = getDouble("team_creation.cost", 0.0);
        }
        return cost;
    }

    public double getTeamJoinCost() {
        return getDouble("economy.costs.team_join", 0.0);
    }

    public int getMaxMembers() {
        return getInt("team.max_members", 10);
    }

    public int getMaxWarps() {
        return getInt("team.max_warps", 5);
    }

    public int getHomeCooldown() {
        return getInt("teleport.home.cooldown", 0);
    }

    public int getWarpCooldown() {
        return getInt("teleport.warp.cooldown", 0);
    }

    public int getPvpToggleCooldown() {
        return getInt("team_pvp.toggle_cooldown", 300);
    }

    public boolean isFriendlyFireDefault() {
        return getBoolean("team_pvp.friendly_fire_default", false);
    }
}
