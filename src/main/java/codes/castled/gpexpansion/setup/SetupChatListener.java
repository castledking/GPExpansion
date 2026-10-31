package codes.castled.gpexpansion.setup;

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
 * Listens for chat messages from players in active setup wizard sessions.
 *
 * <p>Uses nested static listener classes to avoid statically referencing Paper-only
 * {@code io.papermc.paper.event.player.AsyncChatEvent} on Spigot.
 */
public class SetupChatListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final SetupWizardManager wizardManager;

    public SetupChatListener(GPExpansionPlugin plugin, SetupWizardManager wizardManager) {
        this.plugin = plugin;
        this.wizardManager = wizardManager;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        if (!wizardManager.hasActiveSession(event.getPlayer().getUniqueId())) {
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
        // Clean up session when player leaves
        wizardManager.cancelSession(event.getPlayer().getUniqueId());
    }

    /**
     * Paper-only listener for AsyncChatEvent, registered only when the class exists.
     * This avoids NoClassDefFoundError on Spigot which lacks this event.
     */
    public static final class PaperSetupChatListener implements Listener {
        private final GPExpansionPlugin plugin;
        private final SetupWizardManager wizardManager;

        public PaperSetupChatListener(GPExpansionPlugin plugin, SetupWizardManager wizardManager) {
            this.plugin = plugin;
            this.wizardManager = wizardManager;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerChat(io.papermc.paper.event.player.AsyncChatEvent event) {
            Player player = event.getPlayer();

            // Check if player has an active session
            if (!wizardManager.hasActiveSession(player.getUniqueId())) {
                return;
            }

            // Extract plain text from the message component
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());

            // Don't intercept commands
            if (message.startsWith("/")) {
                return;
            }

            // Process the input on the player's thread (Folia compatible)
            event.setCancelled(true);

            codes.castled.gpexpansion.scheduler.SchedulerAdapter.runEntity(plugin, player, () -> {
                wizardManager.processInput(player, message);
            }, null);
        }
    }
}