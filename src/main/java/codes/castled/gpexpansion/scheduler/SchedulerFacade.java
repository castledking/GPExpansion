package codes.castled.gpexpansion.scheduler;

import codes.castled.gpexpansion.GPExpansionPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class SchedulerFacade {

    private final GPExpansionPlugin plugin;

    public SchedulerFacade(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    public void runGlobal(Runnable task) {
        SchedulerAdapter.runGlobal(plugin, task);
    }

    public void runAtEntity(Player player, Runnable task) {
        SchedulerAdapter.runEntity(plugin, player, () -> {
            if (player.isValid()) {
                task.run();
            }
        }, () -> {});
    }

    public void teleportEntity(Player player, Location to) {
        teleportEntity((org.bukkit.entity.Entity) player, to);
    }

    /** Same as {@link #teleportEntity(Player, Location)} for any entity - vehicles, mounts, items. */
    public void teleportEntity(org.bukkit.entity.Entity entity, Location to) {
        if (SchedulerAdapter.isFolia()) {
            try {
                entity.getClass().getMethod("teleportAsync", Location.class).invoke(entity, to);
            } catch (Throwable ignored) {
                try { entity.teleport(to); } catch (Throwable ignored2) {}
            }
        } else {
            try {
                entity.teleport(to);
            } catch (Throwable ignored) {}
        }
    }

    public void runAtLocation(Location loc, Runnable task) {
        SchedulerAdapter.runAtLocation(plugin, loc, task);
    }
}
