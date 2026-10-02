package eu.kotori.justTeams.config;

import eu.kotori.justTeams.JustTeamsFabric;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.yaml.snakeyaml.Yaml;

public class FabricMessageManager {

    private static final Logger LOGGER = Logger.getLogger(FabricMessageManager.class.getName());
    private final JustTeamsFabric plugin;
    private final MiniMessage miniMessage;
    private Map<String, Object> messageMap;
    private String prefix = "<#4C9DDE>[ᴛᴇᴀᴍꜱ]</#4C9DDE> ";

    public FabricMessageManager(JustTeamsFabric plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.load();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        File msgFile = plugin.getDataFolder().resolve("messages.yml").toFile();
        if (!msgFile.exists()) {
            LOGGER.warning("messages.yml not found, using default internal messages.");
            this.messageMap = Collections.emptyMap();
            return;
        }

        try (InputStream in = new FileInputStream(msgFile)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                this.messageMap = (Map<String, Object>) loaded;
                Object p = this.messageMap.get("prefix");
                if (p != null) {
                    this.prefix = String.valueOf(p);
                }
                LOGGER.info("✓ Successfully parsed messages.yml (" + messageMap.size() + " message keys)!");
            } else {
                this.messageMap = Collections.emptyMap();
            }
        } catch (Exception e) {
            LOGGER.severe("Error parsing messages.yml: " + e.getMessage());
            this.messageMap = Collections.emptyMap();
        }
    }

    public String getRaw(String key, String def) {
        if (messageMap != null && messageMap.containsKey(key)) {
            return String.valueOf(messageMap.get(key));
        }
        return def;
    }

    public String format(String key, String defaultMsg, Map<String, String> placeholders) {
        String msg = getRaw(key, defaultMsg);
        if (msg == null || msg.isEmpty()) return "";

        boolean hasPrefix = !msg.startsWith(prefix) && !key.equals("prefix") && !key.startsWith("help_") && !key.startsWith("leaderboard_");
        if (hasPrefix && !msg.startsWith("<")) {
            msg = prefix + msg;
        }

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue() != null ? entry.getValue() : "";
                msg = msg.replace("<" + k + ">", v);
                msg = msg.replace("%" + k + "%", v);
            }
        }

        try {
            Component comp = miniMessage.deserialize(msg);
            return LegacyComponentSerializer.legacySection().serialize(comp);
        } catch (Exception e) {
            return msg.replace("&", "§");
        }
    }

    public String get(String key, String defaultMsg) {
        return format(key, defaultMsg, Collections.emptyMap());
    }

    public String get(String key, String defaultMsg, String k1, String v1) {
        Map<String, String> p = new HashMap<>();
        p.put(k1, v1);
        return format(key, defaultMsg, p);
    }

    public String get(String key, String defaultMsg, String k1, String v1, String k2, String v2) {
        Map<String, String> p = new HashMap<>();
        p.put(k1, v1);
        p.put(k2, v2);
        return format(key, defaultMsg, p);
    }
}
