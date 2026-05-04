package eu.kotori.justTeams.util;

import eu.kotori.justTeams.JustTeams;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TaskRunner {
    private final JustTeams plugin;
    private final boolean isFolia;
    private final boolean isPaper;
    private final Map<UUID, CancellableTask> activeTasks = new ConcurrentHashMap<>();

    public TaskRunner(JustTeams plugin) {
        this.plugin = plugin;
        String serverName = plugin.getServer().getName();
        String serverNameLower = serverName.toLowerCase();

        this.isFolia = serverName.equals("Folia")
                || serverNameLower.contains("folia")
                || serverNameLower.equals("canvas")
                || serverNameLower.equals("petal")
                || serverNameLower.equals("leaf")
                || serverNameLower.contains("luminol");

        this.isPaper = serverName.equals("Paper")
                || serverNameLower.contains("paper")
                || serverName.equals("Purpur")
                || serverName.equals("Airplane")
                || serverName.equals("Pufferfish")
                || serverNameLower.contains("universespigot")
                || serverNameLower.equals("plazma")
                || serverNameLower.equals("mirai")
                || serverNameLower.contains("luminol")
                || serverNameLower.contains("arclight");
    }

    public void run(Runnable task) {
        if (isFolia) {
            Object scheduler = invokeMethod(plugin.getServer(), "getGlobalRegionScheduler");
            invokeMethod(scheduler, "run", plugin, task);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    public void runAsync(Runnable task) {
        if (task == null) {
            return;
        }

        if (!plugin.isEnabled()) {
            runTaskLater(() -> runAsyncInternal(task), 1);
            return;
        }

        runAsyncInternal(task);
    }

    private void runAsyncInternal(Runnable task) {
        if (isFolia) {
            Object asyncScheduler = invokeMethod(plugin.getServer(), "getAsyncScheduler");
            invokeMethod(asyncScheduler, "runNow", plugin, task);
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    task.run();
                } catch (Exception e) {
                    plugin.getLogger().severe("Error in async task: " + e.getMessage());
                    if (plugin.getConfigManager().isDebugEnabled()) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public void runAtLocation(Location location, Runnable task) {
        if (isFolia) {
            Object regionScheduler = invokeMethod(plugin.getServer(), "getRegionScheduler");
            invokeMethod(regionScheduler, "run", plugin, location, task);
        } else {
            run(task);
        }
    }

    public void runOnEntity(Entity entity, Runnable task) {
        if (isFolia) {
            Object scheduler = invokeMethod(entity, "getScheduler");
            invokeMethod(scheduler, "run", plugin, task, null);
        } else {
            run(task);
        }
    }

    public CancellableTask runEntityTaskLater(Entity entity, Runnable task, long delay) {
        if (isFolia) {
            long foliaDelay = Math.max(1L, delay);
            Object scheduler = invokeMethod(entity, "getScheduler");
            Object scheduledTask = invokeMethod(scheduler, "runDelayed", plugin, task, null, foliaDelay);
            return () -> invokeMethod(scheduledTask, "cancel");
        } else {
            BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
            return bukkitTask::cancel;
        }
    }

    public CancellableTask runEntityTaskTimer(Entity entity, Runnable task, long delay, long period) {
        if (isFolia) {
            long foliaDelay = Math.max(1L, delay);
            long foliaPeriod = Math.max(1L, period);
            Object scheduler = invokeMethod(entity, "getScheduler");
            Object scheduledTask = invokeMethod(scheduler, "runAtFixedRate", plugin, task, null, foliaDelay, foliaPeriod);
            return () -> invokeMethod(scheduledTask, "cancel");
        } else {
            BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delay, period);
            return bukkitTask::cancel;
        }
    }

    public CancellableTask runTimer(Runnable task, long delay, long period) {
        return runTaskTimer(task, delay, period);
    }

    public CancellableTask runLater(Runnable task, long delay) {
        return runTaskLater(task, delay);
    }

    public CancellableTask runTaskLater(Runnable task, long delay) {
        if (isFolia) {
            long foliaDelay = Math.max(1L, delay);
            Object scheduler = invokeMethod(plugin.getServer(), "getGlobalRegionScheduler");
            Object scheduledTask = invokeMethod(scheduler, "runDelayed", plugin, task, foliaDelay);
            return () -> invokeMethod(scheduledTask, "cancel");
        } else {
            BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
            return bukkitTask::cancel;
        }
    }

    public CancellableTask runTaskTimer(Runnable task, long delay, long period) {
        if (isFolia) {
            long foliaDelay = Math.max(1L, delay);
            long foliaPeriod = Math.max(1L, period);
            Object scheduler = invokeMethod(plugin.getServer(), "getGlobalRegionScheduler");
            Object scheduledTask = invokeMethod(scheduler, "runAtFixedRate", plugin, task, foliaDelay, foliaPeriod);
            return () -> invokeMethod(scheduledTask, "cancel");
        } else {
            BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delay, period);
            return bukkitTask::cancel;
        }
    }

    public CancellableTask runAsyncTaskLater(Runnable task, long delay) {
        if (isFolia) {
            long delayMs = Math.max(50L, delay * 50);
            Object asyncScheduler = invokeMethod(plugin.getServer(), "getAsyncScheduler");
            Object scheduledTask = invokeMethod(asyncScheduler, "runDelayed", plugin, task, delayMs, TimeUnit.MILLISECONDS);
            return () -> invokeMethod(scheduledTask, "cancel");
        } else {
            BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
            return bukkitTask::cancel;
        }
    }

    public CancellableTask runAsyncTaskTimer(Runnable task, long delay, long period) {
        if (isFolia) {
            long delayMs = Math.max(50L, delay * 50);
            long periodMs = Math.max(50L, period * 50);
            Object asyncScheduler = invokeMethod(plugin.getServer(), "getAsyncScheduler");
            Object scheduledTask = invokeMethod(asyncScheduler, "runAtFixedRate", plugin, task, delayMs, periodMs, TimeUnit.MILLISECONDS);
            return () -> invokeMethod(scheduledTask, "cancel");
        } else {
            BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, task, delay,
                    period);
            return bukkitTask::cancel;
        }
    }

    public void addActiveTask(UUID taskId, CancellableTask task) {
        activeTasks.put(taskId, task);
    }

    public void removeActiveTask(UUID taskId) {
        CancellableTask task = activeTasks.remove(taskId);
        if (task != null) {
            task.cancel();
        }
    }

    public void cancelAllTasks() {
        activeTasks.values().forEach(CancellableTask::cancel);
        activeTasks.clear();
    }

    public boolean hasActiveTask(UUID taskId) {
        return activeTasks.containsKey(taskId);
    }

    public boolean isFolia() {
        return isFolia;
    }

    public boolean isPaper() {
        return isPaper;
    }

    public int getActiveTaskCount() {
        return activeTasks.size();
    }

    public void runAsyncWithCatch(Runnable task, String taskName) {
        if (task == null) {
            plugin.getLogger().warning("Attempted to run null task: " + taskName);
            return;
        }
        runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                task.run();
                long duration = System.currentTimeMillis() - startTime;
                if (duration > 100 && plugin.getConfigManager().isSlowQueryLoggingEnabled()) {
                    plugin.getLogger().warning("Slow async task '" + taskName + "' took " + duration + "ms");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error in async task '" + taskName + "': " + e.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
        });
    }

    private Object invokeMethod(Object target, String methodName, Object... args) {
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
            }
            Method method = target.getClass().getMethod(methodName, paramTypes);
            return method.invoke(target, args);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to invoke method " + methodName + " on " + target.getClass().getName() + ": " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                e.printStackTrace();
            }
            return null;
        }
    }
}