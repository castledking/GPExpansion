package codes.castled.gpexpansion.gui;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.platform.PlatformDetection;

/**
 * Listens for chat input used to set claim descriptions.
 *
 * <p>Uses nested static listener classes to avoid statically referencing Paper-only
 * {@code io.papermc.paper.event.player.AsyncChatEvent} on Spigot.
 */
public class DescriptionChatListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final DescriptionInputManager manager;

    public DescriptionChatListener(GPExpansionPlugin plugin, DescriptionInputManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        if (!manager.hasPending(event.getPlayer().getUniqueId())) {
            return;
        }

        if (event.getMessage().startsWith("/")) {
            return;
        }

        // On Paper, this event is also fired, but we still cancel it to prevent
        // the message from appearing in chat. The Paper-specific listener will
        // also run and handle the input.
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.cancel(event.getPlayer().getUniqueId());
    }

    /**
     * Paper-only listener for AsyncChatEvent, registered only when the class exists.
     * This avoids NoClassDefFoundError on Spigot which lacks this event.
     */
    public static final class PaperDescriptionChatListener implements Listener {
        private final GPExpansionPlugin plugin;
        private final DescriptionInputManager manager;

        public PaperDescriptionChatListener(GPExpansionPlugin plugin, DescriptionInputManager manager) {
            this.plugin = plugin;
            this.manager = manager;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerChat(io.papermc.paper.event.player.AsyncChatEvent event) {
            Player player = event.getPlayer();
            if (!manager.hasPending(player.getUniqueId())) {
                return;
            }

            String message = PlainTextComponentSerializer.plainText().serialize(event.message());
            if (message.startsWith("/")) {
                return;
            }

            event.setCancelled(true);
            plugin.getSchedulerFacade().runAtEntity(player, () -> manager.processInput(player, message));
        }
    }
}