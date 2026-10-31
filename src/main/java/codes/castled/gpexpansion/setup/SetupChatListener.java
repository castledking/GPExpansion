package codes.castled.gpexpansion.setup;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.platform.PaperEvents;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for chat messages from players in active setup wizard sessions across both Paper and Spigot.
 */
public class SetupChatListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final SetupWizardManager wizardManager;
    private boolean paperChatHooked;

    public SetupChatListener(GPExpansionPlugin plugin, SetupWizardManager wizardManager) {
        this.plugin = plugin;
        this.wizardManager = wizardManager;
    }

    public boolean registerPaperHook() {
        this.paperChatHooked = PaperEvents.registerAsyncChat(plugin, this, (player, message) -> {
            if (!wizardManager.hasActiveSession(player.getUniqueId()) || message.startsWith("/")) {
                return false;
            }
            SchedulerAdapter.runEntity(plugin, player, () -> wizardManager.processInput(player, message), null);
            return true;
        });
        return this.paperChatHooked;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!wizardManager.hasActiveSession(player.getUniqueId())) {
            return;
        }

        String message = event.getMessage();
        if (message.startsWith("/")) {
            return;
        }

        event.setCancelled(true);
        if (!paperChatHooked) {
            SchedulerAdapter.runEntity(plugin, player, () -> wizardManager.processInput(player, message), null);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        wizardManager.cancelSession(event.getPlayer().getUniqueId());
    }
}
