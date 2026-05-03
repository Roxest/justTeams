package eu.kotori.justTeams.util;

import eu.kotori.justTeams.JustTeams;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
                || serverNameLower.contains("luminol");
    }

    public void run(Runnable task) {
        if (isFolia) {
            Object scheduler = invokeMethod(plugin.getServer(), "getGlobalRegionScheduler");
            if (scheduler != null) {
                invokeMethod(scheduler, "run", new Class[]{Plugin.class, Consumer.class}, plugin,
                        (Consumer<Object>) scheduledTask -> task.run());
                return;
            }
        }

        plugin.getServer().getScheduler().runTask(plugin, task);
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
            if (asyncScheduler != null) {
                invokeMethod(asyncScheduler, "runNow", new Class[]{Plugin.class, Consumer.class}, plugin,
                        (Consumer<Object>) scheduledTask -> {
                            try {
                                task.run();
                            } catch (Exception e) {
                                plugin.getLogger().severe("Error in async task: " + e.getMessage());
                                if (plugin.getConfigManager().isDebugEnabled()) {
                                    e.printStackTrace();
                                }
                            }
                        });
                return;
            }
        }

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

    public void runAtLocation(Location location, Runnable task) {
        if (isFolia) {
            Object regionScheduler = invokeMethod(plugin.getServer(), "getRegionScheduler");
            if (regionScheduler != null) {
                invokeMethod(regionScheduler, "run", new Class[]{Plugin.class, Location.class, Consumer.class}, plugin,
                        location, (Consumer<Object>) scheduledTask -> task.run());
                return;
            }
        }

        run(task);
    }

    public void runOnEntity(Entity entity, Runnable task) {
        if (isFolia) {
            Object entityScheduler = invokeMethod(entity, "getScheduler");
            if (entityScheduler != null) {
                invokeMethod(entityScheduler, "run", new Class[]{Plugin.class, Consumer.class, Object.class}, plugin,
                        (Consumer<Object>) scheduledTask -> task.run(), null);
                return;
            }
        }

        run(task);
    }

    public CancellableTask runEntityTaskLater(Entity entity, Runnable task, long delay) {
        if (isFolia) {
            Object entityScheduler = invokeMethod(entity, "getScheduler");
            if (entityScheduler != null) {
                long foliaDelay = Math.max(1L, delay);
                Object scheduledTask = invokeMethod(entityScheduler, "runDelayed",
                        new Class[]{Plugin.class, Consumer.class, Object.class, long.class}, plugin,
                        (Consumer<Object>) scheduledTask1 -> task.run(), null, foliaDelay);
                if (scheduledTask != null) {
                    return createCancellableTask(scheduledTask);
                }
            }
        }

        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
        return bukkitTask::cancel;
    }

    public CancellableTask runEntityTaskTimer(Entity entity, Runnable task, long delay, long period) {
        if (isFolia) {
            Object entityScheduler = invokeMethod(entity, "getScheduler");
            if (entityScheduler != null) {
                long foliaDelay = Math.max(1L, delay);
                long foliaPeriod = Math.max(1L, period);
                Object scheduledTask = invokeMethod(entityScheduler, "runAtFixedRate",
                        new Class[]{Plugin.class, Consumer.class, Object.class, long.class, long.class}, plugin,
                        (Consumer<Object>) scheduledTask1 -> task.run(), null, foliaDelay, foliaPeriod);
                if (scheduledTask != null) {
                    return createCancellableTask(scheduledTask);
                }
            }
        }

        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delay, period);
        return bukkitTask::cancel;
    }

    public CancellableTask runTimer(Runnable task, long delay, long period) {
        return runTaskTimer(task, delay, period);
    }

    public CancellableTask runLater(Runnable task, long delay) {
        return runTaskLater(task, delay);
    }

    public CancellableTask runTaskLater(Runnable task, long delay) {
        if (isFolia) {
            Object scheduler = invokeMethod(plugin.getServer(), "getGlobalRegionScheduler");
            if (scheduler != null) {
                long foliaDelay = Math.max(1L, delay);
                Object scheduledTask = invokeMethod(scheduler, "runDelayed",
                        new Class[]{Plugin.class, Consumer.class, long.class}, plugin,
                        (Consumer<Object>) scheduledTask1 -> task.run(), foliaDelay);
                if (scheduledTask != null) {
                    return createCancellableTask(scheduledTask);
                }
            }
        }

        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
        return bukkitTask::cancel;
    }

    public CancellableTask runTaskTimer(Runnable task, long delay, long period) {
        if (isFolia) {
            Object scheduler = invokeMethod(plugin.getServer(), "getGlobalRegionScheduler");
            if (scheduler != null) {
                long foliaDelay = Math.max(1L, delay);
                long foliaPeriod = Math.max(1L, period);
                Object scheduledTask = invokeMethod(scheduler, "runAtFixedRate",
                        new Class[]{Plugin.class, Consumer.class, long.class, long.class}, plugin,
                        (Consumer<Object>) scheduledTask1 -> task.run(), foliaDelay, foliaPeriod);
                if (scheduledTask != null) {
                    return createCancellableTask(scheduledTask);
                }
            }
        }

        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delay, period);
        return bukkitTask::cancel;
    }

    public CancellableTask runAsyncTaskLater(Runnable task, long delay) {
        if (isFolia) {
            Object asyncScheduler = invokeMethod(plugin.getServer(), "getAsyncScheduler");
            if (asyncScheduler != null) {
                long delayMs = Math.max(50L, delay * 50);
                Object scheduledTask = invokeMethod(asyncScheduler, "runDelayed",
                        new Class[]{Plugin.class, Consumer.class, long.class, TimeUnit.class}, plugin,
                        (Consumer<Object>) scheduledTask1 -> task.run(), delayMs, TimeUnit.MILLISECONDS);
                if (scheduledTask != null) {
                    return createCancellableTask(scheduledTask);
                }
            }
        }

        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
        return bukkitTask::cancel;
    }

    public CancellableTask runAsyncTaskTimer(Runnable task, long delay, long period) {
        if (isFolia) {
            Object asyncScheduler = invokeMethod(plugin.getServer(), "getAsyncScheduler");
            if (asyncScheduler != null) {
                long delayMs = Math.max(50L, delay * 50);
                long periodMs = Math.max(50L, period * 50);
                Object scheduledTask = invokeMethod(asyncScheduler, "runAtFixedRate",
                        new Class[]{Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class}, plugin,
                        (Consumer<Object>) scheduledTask1 -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
                if (scheduledTask != null) {
                    return createCancellableTask(scheduledTask);
                }
            }
        }

        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, task, delay,
                period);
        return bukkitTask::cancel;
    }

    private Object invokeMethod(Object target, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception e) {
            plugin.getLogger().warning("Scheduler reflection failed for " + methodName + ": " + e.getMessage());
            return null;
        }
    }

    private Object invokeMethod(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Exception e) {
            plugin.getLogger().warning("Scheduler reflection failed for " + methodName + ": " + e.getMessage());
            return null;
        }
    }

    private CancellableTask createCancellableTask(Object scheduledTask) {
        return () -> {
            if (scheduledTask != null) {
                invokeMethod(scheduledTask, "cancel");
            }
        };
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
}