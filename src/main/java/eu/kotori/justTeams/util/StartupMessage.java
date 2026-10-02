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

        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize(lineSeparator)));
        console.sendMessage("");
        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <color:" + mainColor + ">█╗  ██╗   <white>JustTeams <gray>v<version>", placeholders)));
        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <color:" + mainColor + ">██║ ██╔╝   <gray>ʙʏ <white><author>", placeholders)));
        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <color:" + mainColor + ">█████╔╝    <white>sᴛᴀᴛᴜs: <color:#2ecc71>Active")));
        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <color:" + accentColor + ">█╔═██╗")));
        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <color:" + accentColor + ">█║  ██╗   <white>ʀᴇᴅɪs ᴄᴀᴄʜᴇ: " + redisStatus + " <gray>(optional)")));
        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <color:" + accentColor + ">█║  ╚═╝   <white>ᴠᴀᴜʟᴛ: " + vaultStatus + " <gray>(economy)")));
        console.sendMessage("");
        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <white>ᴘᴀᴘɪ: " + papiStatus + " <gray>| <white>ᴘᴠᴘᴍᴀɴᴀɢᴇʀ: " + pvpManagerStatus + " <gray>| <white>ᴇɴɢɪɴᴇ: <gray>" + engine)));
        console.sendMessage("");
        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize(lineSeparator)));
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

        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize(lineSeparator)));
        console.sendMessage("");
        for (String line : updateBlock) {
            console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize(line, placeholders)));
        }
        console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize(lineSeparator)));
    }

    public static void sendUpdateNotification(Player player, JustTeams plugin) {
        MiniMessage mm = MiniMessage.miniMessage();
        String link = "https://builtbybit.com/resources/justteams.71401/";
        
        player.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<gradient:#4C9DDE:#7FCAE3>--------------------------------------------------</gradient>")));
        player.sendMessage("");
        player.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <gradient:#4C9DDE:#7FCAE3>JustTeams</gradient> <gray>Update Available!</gray>")));
        player.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <gray>A new version is available: <green>" + plugin.latestVersion + "</green>")));
        player.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("  <click:open_url:'" + link + "'><hover:show_text:'<green>Click to visit download page!'><#7FCAE3><u>Click here to download the update.</u></hover></click>")));
        player.sendMessage("");
        player.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize("<gradient:#7FCAE3:#4C9DDE>--------------------------------------------------</gradient>")));
    }

    public static void sendMissingPacketEventsWarning() {
        CommandSender console = Bukkit.getConsoleSender();
        MiniMessage mm = MiniMessage.miniMessage();
        String mainColor = "#e74c3c";
        String accentColor = "#c0392b";
        String lineSeparator = "<dark_gray><strikethrough>                                                                                ";
        String downloadUrl = "https://modrinth.com/plugin/packetevents";
        java.util.function.Consumer<String> send = s -> console.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize(s)));
        send.accept(lineSeparator);
        console.sendMessage("");
        send.accept("  <color:" + mainColor + ">\u2588\u2557  \u2588\u2588\u2557   <white>JustTeams <red>\u26a0 Missing Dependency");
        send.accept("  <color:" + mainColor + ">\u2588\u2588\u2551 \u2588\u2588\u2554\u255d");
        send.accept("  <color:" + mainColor + ">\u2588\u2588\u2588\u2588\u2588\u2554\u255d   <white>\u1d18\u1d00\u1d04\u1d0b\u1d07\u1d1b\u1d07\u1d20\u1d07\u0274\u1d1bs <red>is not installed!");
        send.accept("  <color:" + accentColor + ">\u2588\u2554\u2550\u2588\u2588\u2557    <gray>The <white>Team Glow <gray>feature requires it.");
        send.accept("  <color:" + accentColor + ">\u2588\u2551  \u2588\u2588\u2557   <gray>Team Glow has been <red>disabled</red>.");
        send.accept("  <color:" + accentColor + ">\u2588\u2551  \u255a\u2550\u255d   <gray>Download: <aqua><click:open_url:'" + downloadUrl + "'>" + downloadUrl + "</click>");
        console.sendMessage("");
        send.accept(lineSeparator);
    }

    public static void sendMissingPacketEventsNotification(Player player) {
        MiniMessage mm = MiniMessage.miniMessage();
        String link = "https://modrinth.com/plugin/packetevents";
        java.util.function.Consumer<String> send = s -> player.sendMessage(LegacyComponentSerializer.legacySection().serialize(mm.deserialize(s)));
        send.accept("<gradient:#e74c3c:#c0392b>--------------------------------------------------</gradient>");
        player.sendMessage("");
        send.accept("  <gradient:#e74c3c:#c0392b>JustTeams</gradient> <gray>Missing Dependency</gray>");
        send.accept("  <gray><white>PacketEvents</white> is not installed! <white>Team Glow</white> is disabled.</gray>");
        send.accept("  <click:open_url:'" + link + "'><hover:show_text:'<green>Click to visit download page!'><aqua><u>Click here to download PacketEvents</u></hover></click>");
        player.sendMessage("");
        send.accept("<gradient:#c0392b:#e74c3c>--------------------------------------------------</gradient>");
    }
}
