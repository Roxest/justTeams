package com.pixelclub.teams;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TeamsMod implements ModInitializer {
    public static final Logger LOG=LoggerFactory.getLogger("justteams");
    private static volatile TeamsCore core;
    private static volatile String problem="starting";
    public static TeamsCore core(){return core;}
    public static String problem(){return problem;}
    /** The ready line names the jar that booted, so a postboot check cannot pass on a stale string. */
    static String version(){return FabricLoader.getInstance().getModContainer("justteams").map(c->c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");}
    public static boolean bukkitApiPresent(){try{Class.forName("org.bukkit.Bukkit",false,TeamsMod.class.getClassLoader());return true;}catch(ClassNotFoundException e){return false;}}
    public static boolean bukkitServer(){try{return Class.forName("org.bukkit.Bukkit").getMethod("getServer").invoke(null)!=null;}catch(ClassNotFoundException e){return false;}catch(ReflectiveOperationException e){return true;}}
    public static boolean legacyEnabled(){
        try{Class<?> bukkit=Class.forName("org.bukkit.Bukkit"),plugin=Class.forName("org.bukkit.plugin.Plugin");if(bukkit.getMethod("getServer").invoke(null)==null)return false;Object manager=bukkit.getMethod("getPluginManager").invoke(null);Object[] plugins=(Object[])Class.forName("org.bukkit.plugin.PluginManager").getMethod("getPlugins").invoke(manager);for(Object value:plugins)if("justTeams".equalsIgnoreCase(plugin.getMethod("getName").invoke(value).toString())&&Boolean.TRUE.equals(plugin.getMethod("isEnabled").invoke(value)))return true;return false;}catch(ClassNotFoundException e){return false;}catch(ReflectiveOperationException|RuntimeException e){return true;}
    }
    @Override public void onInitialize() {
        Settings settings=new Settings(FabricLoader.getInstance().getConfigDir().resolve("JustTeams"));
        try{settings.load();}catch(Exception e){problem=e.getClass().getSimpleName();LOG.error("[pxc-teams] NOT READY: configuration rejected ({})",problem);}
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{if(!bukkitApiPresent())TeamCommands.register(dispatcher,settings);});
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            if(legacyEnabled()){problem="BukkitJustTeamsEnabled";LOG.info("[pxc-teams] DORMANT: Bukkit JustTeams is enabled");return;}
            if(bukkitApiPresent()){TeamCommands.register(server.getCommands().getDispatcher(),settings);LOG.info("[pxc-teams] command labels: {} | {}",TeamCommands.releaseBukkitTeamLabel(server.getCommands(),server.getCommands().getDispatcher()),TeamCommands.bukkitOwners());}
            try{core=new TeamsCore(server,settings);problem="";LOG.info("[pxc-teams] native {} ready: {} chests-locked={}",version(),core.status(),core.chests.undecodable());}
            catch(Exception e){problem=e.getClass().getSimpleName();LOG.error("[pxc-teams] NOT READY: {}. No team mutations are exposed.",problem,e);}
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{TeamsCore current=core;core=null;if(current!=null)try{current.close();}catch(Exception e){LOG.error("[pxc-teams] stop reconciliation required: {}",e.getClass().getSimpleName());}});
        ServerTickEvents.END_SERVER_TICK.register(server->{TeamsCore current=core;if(current!=null)try{current.tick();}catch(Exception e){current.storageFailed(e);LOG.error("[pxc-teams] watchdog failed: {}",e.getClass().getSimpleName());}});
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{TeamsCore current=core;if(current!=null)current.safely(handler.player,()->current.joined(handler.player));});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{TeamsCore current=core;if(current!=null)try{current.departed(handler.player);}catch(Exception e){LOG.error("[pxc-teams] disconnect reconciliation required: {}",e.getClass().getSimpleName());}});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{TeamsCore current=core;if(current!=null)try{current.death(entity,source);}catch(Exception e){LOG.error("[pxc-teams] stats update refused: {}",e.getClass().getSimpleName());}});
    }
    public static boolean chat(ServerPlayer player,String message){TeamsCore current=core;if(current==null)return false;try{return current.chat(player,message);}catch(Exception e){current.refuse(player,e);return true;}}
}
