package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class ReminderJoinListener implements Listener {
    private final GPExpansionPlugin plugin;
    public ReminderJoinListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        dev.towki.gpexpansion.reminder.RentalReminderService svc = plugin.getReminderService();
        if (svc != null) {
            svc.onPlayerJoin(event.getPlayer());
        }
    }
}
