package dev.towki.gpexpansion.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Simplified static facade over SchedulerAdapter for Folia compatibility.
 * Mirrors the API style of SkipNight's FoliaSupport for consistency.
 */
public final class FoliaSupport {

    private FoliaSupport() {}

    public static boolean isFolia() {
        return SchedulerAdapter.isFolia();
    }

    public static TaskHandle runGlobal(Plugin plugin, Runnable runnable) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runGlobal(plugin, runnable);
    }

    public static TaskHandle runLaterGlobal(Plugin plugin, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runLaterGlobal(plugin, runnable, delayTicks);
    }

    public static TaskHandle runRepeatingGlobal(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runRepeatingGlobal(plugin, runnable, delayTicks, periodTicks);
    }

    public static TaskHandle runAsyncNow(Plugin plugin, Runnable runnable) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runAsyncNow(plugin, runnable);
    }

    public static TaskHandle runAsyncLater(Plugin plugin, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runAsyncLater(plugin, runnable, delayTicks);
    }

    public static TaskHandle runAsyncRepeating(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runAsyncRepeating(plugin, runnable, delayTicks, periodTicks);
    }

    public static TaskHandle runEntity(Plugin plugin, Entity entity, Runnable runnable, Runnable retired) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runEntity(plugin, entity, runnable, retired);
    }

    public static TaskHandle runLaterEntity(Plugin plugin, Entity entity, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runLaterEntity(plugin, entity, runnable, delayTicks);
    }

    public static TaskHandle runAtLocation(Plugin plugin, Location location, Runnable runnable) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(location);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runAtLocation(plugin, location, runnable);
    }

    public static TaskHandle runAtLocationLater(Plugin plugin, Location location, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(location);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runAtLocationLater(plugin, location, runnable, delayTicks);
    }

    public static TaskHandle runAtLocationRepeating(Plugin plugin, Location location, Runnable runnable, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(location);
        Objects.requireNonNull(runnable);
        return SchedulerAdapter.runAtLocationRepeating(plugin, location, runnable, delayTicks, periodTicks);
    }

    public static void cancelTasks(Plugin plugin) {
        Objects.requireNonNull(plugin);
        SchedulerAdapter.cancelTasks(plugin);
    }
}
