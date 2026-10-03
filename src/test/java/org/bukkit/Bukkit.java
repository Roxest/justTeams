package org.bukkit;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
public final class Bukkit {
    public static Object server;
    public static PluginManager manager;
    public static Player player;
    public static Object getServer(){return server;}
    public static PluginManager getPluginManager(){return manager;}
    public static Player getPlayer(UUID uuid){return player;}
}
