package eu.kotori.justTeams.util;

import eu.kotori.justTeams.JustTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class StartupMessage {

    // --- ADDED HELPER METHOD FOR ARCLIGHT COMPATIBILITY ---
    private static void sendSafe(CommandSender sender, Component component) {
        if (component.equals(Component.empty())) {
            sender.sendMessage("");
            return;
        }
        String legacyString = LegacyComponentSerializer.legacySection().serialize(component);
        sender.sendMessage(legacyString);
    }
    // ------------------------------------------------------

    public static void send() {
        JustTeams plugin = JustTeams.getInstance();
        CommandSender console = Bukkit.getConsoleSender();
        MiniMessage mm = MiniMessage.miniMessage();

        String check = "<green>✔</green>";
        String cross = "<red>✖</red>";

        boolean redisEnabled = false;
        try {
            redisEnabled = plugin.getConfigManager() != null && plugin.getConfigManager().isRedisEnabled();
        } catch (Exception e) {
        }
        
        boolean vaultEnabled = Bukkit.getPluginManager().isPluginEnabled("Vault");
        boolean papiEnabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        boolean pvpManagerEnabled = Bukkit.getPluginManager().isPluginEnabled("PvPManager");
        
        String redisStatus = redisEnabled ? check : "<gray>-</gray>";
        String vaultStatus = vaultEnabled ? check : cross;
        String papiStatus = papiEnabled ? check : cross;
        String pvpManagerStatus = pvpManagerEnabled ? check : cross;

        String engine;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            engine = "Folia";
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("com.destroystokyo.paper.PaperConfig");
                engine = "Paper";
            } catch (ClassNotFoundException e2) {
                engine = "Spigot/Bukkit";
            }
        }

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("version", plugin.getDescription().getVersion()))
                .resolver(Placeholder.unparsed("author", String.join(", ", plugin.getDescription().getAuthors())))
                .build();

        String mainColor = "#4C9DDE";
        String accentColor = "#7FCAE3";
        String lineSeparator = "<dark_gray><strikethrough>                                                                                ";

        // Replaced console.sendMessage with sendSafe
        sendSafe(console, mm.deserialize(lineSeparator));
        sendSafe(console, Component.empty());
        sendSafe(console, mm.deserialize("  <color:" + mainColor + ">█╗  ██╗   <white>JustTeams <gray>v<version>", placeholders));
        sendSafe(console, mm.deserialize("  <color:" + mainColor + ">██║ ██╔╝   <gray>ʙʏ <white><author>", placeholders));
        sendSafe(console, mm.deserialize("  <color:" + mainColor + ">█████╔╝    <white>sᴛᴀᴛᴜs: <color:#2ecc71>Active"));
        sendSafe(console, mm.deserialize("  <color:" + accentColor + ">█╔═██╗"));
        sendSafe(console, mm.deserialize("  <color:" + accentColor + ">█║  ██╗   <white>ʀᴇᴅɪs ᴄᴀᴄʜᴇ: " + redisStatus + " <gray>(optional)"));
        sendSafe(console, mm.deserialize("  <color:" + accentColor + ">█║  ╚═╝   <white>ᴠᴀᴜʟᴛ: " + vaultStatus + " <gray>(economy)"));
        sendSafe(console, Component.empty());
        sendSafe(console, mm.deserialize("  <white>ᴘᴀᴘɪ: " + papiStatus + " <gray>| <white>ᴘᴠᴘᴍᴀɴᴀɢᴇʀ: " + pvpManagerStatus + " <gray>| <white>ᴇɴɢɪɴᴇ: <gray>" + engine));
        sendSafe(console, Component.empty());
        sendSafe(console, mm.deserialize(lineSeparator));
    }

    public static void sendUpdateNotification(JustTeams plugin) {
        CommandSender console = Bukkit.getConsoleSender();
        MiniMessage mm = MiniMessage.miniMessage();

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("current_version", plugin.getDescription().getVersion()))
                .resolver(Placeholder.unparsed("latest_version", plugin.latestVersion))
                .build();

        String mainColor = "#f39c12";
        String accentColor = "#e67e22";
        String lineSeparator = "<dark_gray><strikethrough>                                                                                ";

        List<String> updateBlock = List.of(
                "  <color:" + mainColor + ">█╗  ██╗   <white>JustTeams <gray>Update",
                "  <color:" + mainColor + ">██║ ██╔╝   <gray>A new version is available!",
                "  <color:" + mainColor + ">█████╔╝",
                "  <color:" + accentColor + ">█╔═██╗    <white>ᴄᴜʀʀᴇɴᴛ: <gray><current_version>",
                "  <color:" + accentColor + ">█║  ██╗   <white>ʟᴀᴛᴇsᴛ: <green><latest_version>",
                "  <color:" + accentColor + ">█║  ╚═╝   <aqua><click:open_url:'https://builtbybit.com/resources/justteams.71401/'>Click here to download</click>",
                ""
        );

        sendSafe(console, mm.deserialize(lineSeparator));
        sendSafe(console, Component.empty());
        for (String line : updateBlock) {
            sendSafe(console, mm.deserialize(line, placeholders));
        }
        sendSafe(console, mm.deserialize(lineSeparator));
    }

    public static void sendUpdateNotification(Player player, JustTeams plugin) {
        MiniMessage mm = MiniMessage.miniMessage();
        String link = "https://builtbybit.com/resources/justteams.71401/";
        
        sendSafe(player, mm.deserialize("<gradient:#4C9DDE:#7FCAE3>--------------------------------------------------</gradient>"));
        sendSafe(player, Component.empty());
        sendSafe(player, mm.deserialize("  <gradient:#4C9DDE:#7FCAE3>JustTeams</gradient> <gray>Update Available!</gray>"));
        sendSafe(player, mm.deserialize("  <gray>A new version is available: <green>" + plugin.latestVersion + "</green>"));
        sendSafe(player, mm.deserialize("  <click:open_url:'" + link + "'><hover:show_text:'<green>Click to visit download page!'><#7FCAE3><u>Click here to download the update.</u></hover></click>"));
        sendSafe(player, Component.empty());
        sendSafe(player, mm.deserialize("<gradient:#7FCAE3:#4C9DDE>--------------------------------------------------</gradient>"));
    }
}