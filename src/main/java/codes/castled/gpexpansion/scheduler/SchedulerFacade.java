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
        if (SchedulerAdapter.isFolia()) {
            try {
                player.teleportAsync(to);
            } catch (Throwable ignored) {}
        } else {
            try {
                player.teleport(to);
            } catch (Throwable ignored) {}
        }
    }

    public void runAtLocation(Location loc, Runnable task) {
        SchedulerAdapter.runAtLocation(plugin, loc, task);
    }
}
