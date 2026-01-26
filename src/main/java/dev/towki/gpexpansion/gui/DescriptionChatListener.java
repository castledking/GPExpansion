package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.GPExpansionPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for chat input used to set claim descriptions.
 */
public class DescriptionChatListener implements Listener {
    
    private final GPExpansionPlugin plugin;
    private final DescriptionInputManager manager;
    
    public DescriptionChatListener(GPExpansionPlugin plugin, DescriptionInputManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!manager.hasPending(player.getUniqueId())) {
            return;
        }
        
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (message.startsWith("/")) {
            return;
        }
        
        event.setCancelled(true);
        plugin.runAtEntity(player, () -> manager.processInput(player, message));
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.cancel(event.getPlayer().getUniqueId());
    }
}
