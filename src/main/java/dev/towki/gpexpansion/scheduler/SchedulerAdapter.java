package dev.towki.gpexpansion.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Folia-compatible scheduler adapter using reflection.
 * Falls back to Bukkit scheduler on non-Folia servers.
 */
public final class SchedulerAdapter {

    private static final boolean FOLIA_PRESENT;

    static {
        FOLIA_PRESENT = detectFolia();
    }

    private SchedulerAdapter() {}

    private static boolean detectFolia() {
        try {
            for (Method m : Bukkit.getServer().getClass().getMethods()) {
                if (m.getName().equals("getGlobalRegionScheduler")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isFolia() {
        return FOLIA_PRESENT;
    }

    /**
     * Run a task on the entity's region thread (Folia) or main thread (Bukkit).
     */
    public static void runOnEntity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(task);
        if (FOLIA_PRESENT) {
            try {
                Method getScheduler = entity.getClass().getMethod("getScheduler");
                Object scheduler = getScheduler.invoke(entity);
                Consumer<Object> consumer = (ignored) -> task.run();
                for (Method m : scheduler.getClass().getMethods()) {
                    if (!m.getName().equals("execute")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 4 && Plugin.class.isAssignableFrom(params[0]) 
                            && Runnable.class.isAssignableFrom(params[1])
                            && params[3] == long.class) {
                        m.invoke(scheduler, plugin, task, retired, 1L);
                        return;
                    }
                }
                // Try run method as fallback
                for (Method m : scheduler.getClass().getMethods()) {
                    if (!m.getName().equals("run")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 3 && Plugin.class.isAssignableFrom(params[0]) 
                            && Consumer.class.isAssignableFrom(params[1]) 
                            && Runnable.class.isAssignableFrom(params[2])) {
                        m.invoke(scheduler, plugin, consumer, retired);
                        return;
                    }
                }
                throw new UnsupportedOperationException("No compatible EntityScheduler method found");
            } catch (Throwable t) {
                if (FOLIA_PRESENT) {
                    throw new UnsupportedOperationException("Failed to schedule entity task on Folia", t);
                }
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run a task later on the entity's region thread (Folia) or main thread (Bukkit).
     */
    public static void runOnEntityLater(Plugin plugin, Entity entity, Runnable task, Runnable retired, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(task);
        long safeDelay = Math.max(1L, delayTicks);
        if (FOLIA_PRESENT) {
            try {
                Method getScheduler = entity.getClass().getMethod("getScheduler");
                Object scheduler = getScheduler.invoke(entity);
                // Try execute method (Plugin, Runnable, Runnable, long)
                for (Method m : scheduler.getClass().getMethods()) {
                    if (!m.getName().equals("execute")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 4 && Plugin.class.isAssignableFrom(params[0]) 
                            && Runnable.class.isAssignableFrom(params[1])
                            && params[3] == long.class) {
                        m.invoke(scheduler, plugin, task, retired, safeDelay);
                        return;
                    }
                }
                throw new UnsupportedOperationException("No compatible EntityScheduler#execute method found");
            } catch (Throwable t) {
                if (FOLIA_PRESENT) {
                    throw new UnsupportedOperationException("Failed to schedule delayed entity task on Folia", t);
                }
                Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
        }
    }

    /**
     * Run a task on the global region (Folia) or main thread (Bukkit).
     */
    public static void runGlobal(Plugin plugin, Runnable task) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(task);
        if (FOLIA_PRESENT) {
            try {
                Object global = getGlobalScheduler();
                Consumer<Object> consumer = (ignored) -> task.run();
                for (Method m : global.getClass().getMethods()) {
                    if (!m.getName().equals("run")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2 && Plugin.class.isAssignableFrom(params[0]) 
                            && Consumer.class.isAssignableFrom(params[1])) {
                        m.invoke(global, plugin, consumer);
                        return;
                    }
                }
                for (Method m : global.getClass().getMethods()) {
                    if (!m.getName().equals("execute")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2 && Plugin.class.isAssignableFrom(params[0]) 
                            && Consumer.class.isAssignableFrom(params[1])) {
                        m.invoke(global, plugin, consumer);
                        return;
                    }
                }
                throw new UnsupportedOperationException("No compatible GlobalRegionScheduler method found");
            } catch (Throwable t) {
                if (FOLIA_PRESENT) {
                    throw new UnsupportedOperationException("Failed to schedule global task on Folia", t);
                }
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run a task later on the global region (Folia) or main thread (Bukkit).
     */
    public static void runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(task);
        long safeDelay = Math.max(1L, delayTicks);
        if (FOLIA_PRESENT) {
            try {
                Object global = getGlobalScheduler();
                Consumer<Object> consumer = (ignored) -> task.run();
                Method runDelayed = global.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                runDelayed.invoke(global, plugin, consumer, safeDelay);
                return;
            } catch (Throwable t) {
                if (FOLIA_PRESENT) {
                    throw new UnsupportedOperationException("Failed to schedule delayed global task on Folia", t);
                }
                Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
        }
    }

    /**
     * Run a repeating task on the global region (Folia) or main thread (Bukkit).
     */
    public static BukkitTask runGlobalRepeating(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(task);
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (FOLIA_PRESENT) {
            try {
                Object global = getGlobalScheduler();
                Consumer<Object> consumer = (ignored) -> task.run();
                Method runAtFixedRate = global.getClass().getMethod("runAtFixedRate", 
                        Plugin.class, Consumer.class, long.class, long.class);
                runAtFixedRate.invoke(global, plugin, consumer, safeDelay, safePeriod);
                return null; // Folia doesn't return BukkitTask
            } catch (Throwable t) {
                if (FOLIA_PRESENT) {
                    throw new UnsupportedOperationException("Failed to schedule repeating global task on Folia", t);
                }
                return Bukkit.getScheduler().runTaskTimer(plugin, task, safeDelay, safePeriod);
            }
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, task, safeDelay, safePeriod);
    }

    private static Object getGlobalScheduler() throws Exception {
        try {
            Method staticGetter = Bukkit.class.getMethod("getGlobalRegionScheduler");
            return staticGetter.invoke(null);
        } catch (Throwable ignored) {
            Object server = Bukkit.getServer();
            Method instanceGetter = server.getClass().getMethod("getGlobalRegionScheduler");
            return instanceGetter.invoke(server);
        }
    }

    /**
     * Run a task asynchronously (off the main thread).
     * Works the same on both Folia and Bukkit - uses the async scheduler.
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(task);
        if (FOLIA_PRESENT) {
            try {
                Object asyncScheduler = getAsyncScheduler();
                Consumer<Object> consumer = (ignored) -> task.run();
                for (Method m : asyncScheduler.getClass().getMethods()) {
                    if (!m.getName().equals("runNow")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2 && Plugin.class.isAssignableFrom(params[0]) 
                            && Consumer.class.isAssignableFrom(params[1])) {
                        m.invoke(asyncScheduler, plugin, consumer);
                        return;
                    }
                }
                throw new UnsupportedOperationException("No compatible AsyncScheduler#runNow method found");
            } catch (Throwable t) {
                // Fallback to Bukkit async
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    private static Object getAsyncScheduler() throws Exception {
        try {
            Method staticGetter = Bukkit.class.getMethod("getAsyncScheduler");
            return staticGetter.invoke(null);
        } catch (Throwable ignored) {
            Object server = Bukkit.getServer();
            Method instanceGetter = server.getClass().getMethod("getAsyncScheduler");
            return instanceGetter.invoke(server);
        }
    }
}
