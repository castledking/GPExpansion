package codes.castled.gpexpansion.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import codes.castled.gpexpansion.GPExpansionPlugin;

public class ReminderJoinListener implements Listener {
    private final GPExpansionPlugin plugin;
    public ReminderJoinListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        codes.castled.gpexpansion.reminder.RentalReminderService svc = plugin.getReminderService();
        if (svc != null) {
            svc.onPlayerJoin(event.getPlayer());
        }
    }
}
