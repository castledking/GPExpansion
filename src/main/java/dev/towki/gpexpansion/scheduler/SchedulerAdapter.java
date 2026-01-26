package dev.towki.gpexpansion.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Comprehensive Folia-compatible scheduling adapter with Bukkit fallback.
 * Supports global, async, region, location, and entity scheduling.
 * Improved version combining the best of ExcellentCrates and SkipNight implementations.
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

    private static Object getGlobalScheduler() throws Exception {
        try {
            return Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
        } catch (Throwable ignored) {
            return Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
        }
    }

    private static Object getAsyncScheduler() throws Exception {
        try {
            return Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
        } catch (Throwable ignored) {
            return Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
        }
    }

    private static Object getRegionScheduler() throws Exception {
        try {
            return Bukkit.class.getMethod("getRegionScheduler").invoke(null);
        } catch (Throwable ignored) {
            return Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
        }
    }

    // ====================== GLOBAL (MAIN THREAD EQUIVALENT) ======================

    public static TaskHandle runGlobal(Plugin plugin, Runnable runnable) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                Object global = getGlobalScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method run = findMethod(global, "run", Plugin.class, Consumer.class);
                if (run != null) {
                    return new TaskHandle(run.invoke(global, plugin, consumer));
                }
                // Fallback execute (fire-and-forget)
                Method execute = findMethod(global, "execute", Plugin.class, Consumer.class);
                if (execute != null) {
                    execute.invoke(global, plugin, consumer);
                    return new TaskHandle(null);
                }
                throw new UnsupportedOperationException("No compatible run/execute method found on GlobalRegionScheduler");
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule global task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
    }

    public static TaskHandle runLaterGlobal(Plugin plugin, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        long safeDelay = Math.max(1L, delayTicks);
        if (FOLIA_PRESENT) {
            try {
                Object global = getGlobalScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method m = findMethod(global, "runDelayed", Plugin.class, Consumer.class, long.class);
                if (m != null) {
                    return new TaskHandle(m.invoke(global, plugin, consumer, safeDelay));
                }
                m = findMethod(global, "runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
                return new TaskHandle(m.invoke(global, plugin, consumer, safeDelay * 50L, TimeUnit.MILLISECONDS));
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule delayed global task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, safeDelay));
    }

    public static TaskHandle runRepeatingGlobal(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (FOLIA_PRESENT) {
            try {
                Object global = getGlobalScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method m = findMethod(global, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                if (m != null) {
                    return new TaskHandle(m.invoke(global, plugin, consumer, safeDelay, safePeriod));
                }
                m = findMethod(global, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                return new TaskHandle(m.invoke(global, plugin, consumer, safeDelay * 50L, safePeriod * 50L, TimeUnit.MILLISECONDS));
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule repeating global task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, runnable, safeDelay, safePeriod));
    }

    // ====================== ASYNC ======================

    public static TaskHandle runAsyncNow(Plugin plugin, Runnable runnable) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                Object async = getAsyncScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method runNow = findMethod(async, "runNow", Plugin.class, Consumer.class);
                if (runNow != null) {
                    return new TaskHandle(runNow.invoke(async, plugin, consumer));
                }
                // Fallback to runDelayed with 0 delay
                Method delayed = findMethod(async, "runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
                if (delayed == null) {
                    delayed = findMethod(async, "runDelayed", Plugin.class, Consumer.class, long.class);
                }
                if (delayed != null) {
                    long delay = delayed.getParameterCount() == 4 ? 0L : 0L;
                    return new TaskHandle(delayed.invoke(async, plugin, consumer, delay, delayed.getParameterCount() == 4 ? TimeUnit.MILLISECONDS : null));
                }
                throw new UnsupportedOperationException("No compatible async run method found");
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule async task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    public static TaskHandle runAsyncLater(Plugin plugin, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        long safeDelay = Math.max(0L, delayTicks);
        if (FOLIA_PRESENT) {
            try {
                Object async = getAsyncScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method m = findMethod(async, "runDelayed", Plugin.class, Consumer.class, long.class);
                if (m != null) {
                    return new TaskHandle(m.invoke(async, plugin, consumer, safeDelay));
                }
                m = findMethod(async, "runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
                return new TaskHandle(m.invoke(async, plugin, consumer, safeDelay * 50L, TimeUnit.MILLISECONDS));
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule delayed async task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, safeDelay));
    }

    public static TaskHandle runAsyncRepeating(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (FOLIA_PRESENT) {
            try {
                Object async = getAsyncScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method m = findMethod(async, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                if (m != null) {
                    return new TaskHandle(m.invoke(async, plugin, consumer, safeDelay, safePeriod));
                }
                m = findMethod(async, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                return new TaskHandle(m.invoke(async, plugin, consumer, safeDelay * 50L, safePeriod * 50L, TimeUnit.MILLISECONDS));
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule repeating async task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, safeDelay, safePeriod));
    }

    // ====================== ENTITY ======================

    public static TaskHandle runEntity(Plugin plugin, Entity entity, Runnable runnable, Runnable retired) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(runnable);
        Runnable retiredTask = retired != null ? retired : () -> {};
        if (FOLIA_PRESENT) {
            try {
                Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Consumer<Object> consumer = ignored -> runnable.run();
                Method m = findMethod(scheduler, "run", Plugin.class, Consumer.class, Runnable.class);
                if (m != null) {
                    m.invoke(scheduler, plugin, consumer, retiredTask);
                    return new TaskHandle(null);
                }
                m = findMethod(scheduler, "execute", Plugin.class, Consumer.class, Runnable.class);
                if (m != null) {
                    m.invoke(scheduler, plugin, consumer, retiredTask);
                    return new TaskHandle(null);
                }
                throw new UnsupportedOperationException("No compatible entity run method found");
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule entity task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
    }

    public static TaskHandle runLaterEntity(Plugin plugin, Entity entity, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(runnable);
        long safeDelay = Math.max(1L, delayTicks);
        if (FOLIA_PRESENT) {
            try {
                Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Consumer<Object> consumer = ignored -> runnable.run();
                Runnable retired = () -> {}; // No-op if entity is removed
                // Folia signature: runDelayed(Plugin, Consumer, Runnable retired, long delayTicks)
                Method m = findMethod(scheduler, "runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
                if (m != null) {
                    return new TaskHandle(m.invoke(scheduler, plugin, consumer, retired, safeDelay));
                }
                // Try without retired param (older versions)
                m = findMethod(scheduler, "runDelayed", Plugin.class, Consumer.class, long.class);
                if (m != null) {
                    return new TaskHandle(m.invoke(scheduler, plugin, consumer, safeDelay));
                }
                throw new UnsupportedOperationException("No compatible runDelayed method found on entity scheduler");
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule delayed entity task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, safeDelay));
    }

    public static TaskHandle runLaterEntity(Plugin plugin, Player player, Runnable runnable, long delayTicks) {
        return runLaterEntity(plugin, (Entity) player, runnable, delayTicks);
    }

    public static TaskHandle runEntityRepeating(Plugin plugin, Entity entity, Runnable runnable, Runnable retired, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(runnable);
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        Runnable retiredTask = retired != null ? retired : () -> {};
        if (FOLIA_PRESENT) {
            try {
                Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Consumer<Object> consumer = ignored -> runnable.run();
                Method m = findMethod(scheduler, "runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
                if (m != null) {
                    m.invoke(scheduler, plugin, consumer, retiredTask, safeDelay, safePeriod);
                    return new TaskHandle(null);
                }
                throw new UnsupportedOperationException("No compatible repeating entity method found");
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule repeating entity task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
    }

    // ====================== LOCATION ======================

    public static TaskHandle runAtLocation(Plugin plugin, Location location, Runnable runnable) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(location);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                Object region = getRegionScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method execute = findMethod(region, "execute", Plugin.class, Location.class, Consumer.class);
                if (execute != null) {
                    execute.invoke(region, plugin, location, consumer);
                    return new TaskHandle(null);
                }
                Method run = findMethod(region, "run", Plugin.class, Location.class, Consumer.class);
                if (run != null) {
                    return new TaskHandle(run.invoke(region, plugin, location, consumer));
                }
                throw new UnsupportedOperationException("No compatible location run method found");
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule location task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
    }

    public static TaskHandle runAtLocation(Plugin plugin, Location location, Runnable runnable, Runnable retired) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(location);
        Objects.requireNonNull(runnable);
        Runnable retiredTask = retired != null ? retired : () -> {};
        if (FOLIA_PRESENT) {
            try {
                Object region = getRegionScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method m = findMethod(region, "run", Plugin.class, Location.class, Consumer.class, Runnable.class);
                if (m != null) {
                    m.invoke(region, plugin, location, consumer, retiredTask);
                    return new TaskHandle(null);
                }
                throw new UnsupportedOperationException("No compatible location run method found");
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule location task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
    }

    public static TaskHandle runAtLocationLater(Plugin plugin, Location location, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(location);
        Objects.requireNonNull(runnable);
        long safeDelay = Math.max(1L, delayTicks);
        if (FOLIA_PRESENT) {
            try {
                Object region = getRegionScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method m = findMethod(region, "runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
                if (m != null) {
                    return new TaskHandle(m.invoke(region, plugin, location, consumer, safeDelay));
                }
                throw new UnsupportedOperationException("No compatible delayed location method found");
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule delayed location task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, safeDelay));
    }

    public static TaskHandle runAtLocationRepeating(Plugin plugin, Location location, Runnable runnable, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(location);
        Objects.requireNonNull(runnable);
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (FOLIA_PRESENT) {
            try {
                Object region = getRegionScheduler();
                Consumer<Object> consumer = ignored -> runnable.run();
                Method m = findMethod(region, "runAtFixedRate", Plugin.class, Location.class, Consumer.class, long.class, long.class);
                if (m != null) {
                    return new TaskHandle(m.invoke(region, plugin, location, consumer, safeDelay, safePeriod));
                }
                throw new UnsupportedOperationException("No compatible repeating location method found");
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Failed to schedule repeating location task on Folia", t);
            }
        }
        return new TaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, runnable, safeDelay, safePeriod));
    }

    // ====================== UTILS ======================

    public static void cancelTasks(Plugin plugin) {
        Objects.requireNonNull(plugin);
        if (FOLIA_PRESENT) {
            try {
                Object global = getGlobalScheduler();
                invokeCancelTasks(global, plugin);
                Object async = getAsyncScheduler();
                invokeCancelTasks(async, plugin);
            } catch (Throwable ignored) {}
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    private static void invokeCancelTasks(Object scheduler, Plugin plugin) throws Exception {
        for (Method m : scheduler.getClass().getMethods()) {
            if (m.getName().equals("cancelTasks") && m.getParameterCount() == 1 && Plugin.class.isAssignableFrom(m.getParameterTypes()[0])) {
                m.invoke(scheduler, plugin);
                break;
            }
        }
    }

    private static Method findMethod(Object instance, String name, Class<?>... paramTypes) {
        for (Method m : instance.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types.length != paramTypes.length) continue;
            boolean matches = true;
            for (int i = 0; i < types.length; i++) {
                if (!paramTypes[i].isAssignableFrom(types[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return m;
        }
        return null;
    }
}