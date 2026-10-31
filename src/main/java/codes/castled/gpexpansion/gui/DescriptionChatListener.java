package codes.castled.gpexpansion.gui;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.platform.PaperEvents;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for chat input used to set claim descriptions across both Paper and Spigot.
 */
public class DescriptionChatListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final DescriptionInputManager manager;
    private boolean paperChatHooked;

    public DescriptionChatListener(GPExpansionPlugin plugin, DescriptionInputManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean registerPaperHook() {
        this.paperChatHooked = PaperEvents.registerAsyncChat(plugin, this, (player, message) -> {
            if (!manager.hasPending(player.getUniqueId()) || message.startsWith("/")) {
                return false;
            }
            plugin.getSchedulerFacade().runAtEntity(player, () -> manager.processInput(player, message));
            return true;
        });
        return this.paperChatHooked;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!manager.hasPending(player.getUniqueId())) {
            return;
        }

        String message = event.getMessage();
        if (message.startsWith("/")) {
            return;
        }

        event.setCancelled(true);
        if (!paperChatHooked) {
            plugin.getSchedulerFacade().runAtEntity(player, () -> manager.processInput(player, message));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.cancel(event.getPlayer().getUniqueId());
    }
}
