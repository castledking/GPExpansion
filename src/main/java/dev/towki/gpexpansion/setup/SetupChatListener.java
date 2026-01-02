package dev.towki.gpexpansion.setup;

import dev.towki.gpexpansion.GPExpansionPlugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for chat messages from players in active setup wizard sessions.
 */
public class SetupChatListener implements Listener {
    
    private final GPExpansionPlugin plugin;
    private final SetupWizardManager wizardManager;
    
    public SetupChatListener(GPExpansionPlugin plugin, SetupWizardManager wizardManager) {
        this.plugin = plugin;
        this.wizardManager = wizardManager;
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
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
        
        // Process the input on the main thread
        event.setCancelled(true);
        
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            wizardManager.processInput(player, message);
        });
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up session when player leaves
        wizardManager.cancelSession(event.getPlayer().getUniqueId());
    }
}
