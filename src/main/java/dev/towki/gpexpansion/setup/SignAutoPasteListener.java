package dev.towki.gpexpansion.setup;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.setup.SetupWizardManager.PendingSignData;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listens for sign creation to auto-fill format from setup wizard.
 * 
 * Fallback mode (no PacketEvents): Cancels BlockPlaceEvent, places sign directly
 * with content, bypassing the sign GUI entirely.
 */
public class SignAutoPasteListener implements Listener {
    
    private final GPExpansionPlugin plugin;
    private final SetupWizardManager wizardManager;
    private boolean packetEventsAvailable = false;
    
    public SignAutoPasteListener(GPExpansionPlugin plugin, SetupWizardManager wizardManager) {
        this.plugin = plugin;
        this.wizardManager = wizardManager;
    }
    
    /**
     * Set whether PacketEvents is available (called from plugin after PacketEvents is registered).
     * When true, this listener will NOT intercept BlockPlaceEvent - PacketEvents handles it.
     */
    public void setPacketEventsAvailable(boolean available) {
        this.packetEventsAvailable = available;
        plugin.getLogger().info("[AutoPaste] PacketEvents mode: " + (available ? "ENABLED" : "DISABLED (fallback)"));
    }
    
    /**
     * Fallback mode: Cancel sign placement, place sign directly with content.
     * This bypasses the sign GUI entirely.
     * Only runs when PacketEvents is NOT available.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        // Skip if PacketEvents is handling sign pre-fill
        if (packetEventsAvailable) {
            return;
        }
        
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        
        // Only handle sign blocks
        if (!isSign(block.getType())) {
            return;
        }
        
        // Check if player has pending auto-paste
        if (!wizardManager.hasPendingAutoPaste(player.getUniqueId())) {
            return;
        }
        
        // Consume the pending data (one-time use)
        PendingSignData data = wizardManager.consumePendingAutoPaste(player.getUniqueId());
        if (data == null) {
            return;
        }
        
        String[] lines = data.getSignLines();
        
        plugin.getLogger().info("[AutoPaste] Fallback mode: Bypassing sign GUI for " + player.getName());
        
        // Cancel the event to prevent normal sign placement (which opens GUI)
        event.setCancelled(true);
        
        // Store block info before cancellation takes effect
        Location loc = block.getLocation();
        Material signType = block.getType();
        BlockData blockData = block.getBlockData().clone();
        ItemStack itemInHand = event.getItemInHand();
        
        // Schedule sign placement for next tick (after cancel takes effect)
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Re-place the sign block manually
            Block targetBlock = loc.getBlock();
            targetBlock.setType(signType, false);
            targetBlock.setBlockData(blockData, false);
            
            // Now set the sign content
            if (targetBlock.getState() instanceof Sign sign) {
                for (int i = 0; i < 4 && i < lines.length; i++) {
                    plugin.getLogger().info("[AutoPaste]   Line " + i + ": " + lines[i]);
                    sign.getSide(Side.FRONT).line(i, LegacyComponentSerializer.legacyAmpersand().deserialize(lines[i]));
                }
                sign.setWaxed(true); // Prevent further editing
                sign.update(true, false);
                
                // Trigger SignChangeEvent so SignListener processes it
                SignChangeEvent signEvent = new SignChangeEvent(targetBlock, player, lines);
                Bukkit.getPluginManager().callEvent(signEvent);
                
                // If SignListener modified the lines, update the sign
                if (!signEvent.isCancelled()) {
                    for (int i = 0; i < 4; i++) {
                        var line = signEvent.line(i);
                        if (line != null) {
                            sign.getSide(Side.FRONT).line(i, line);
                        }
                    }
                    sign.update(true, false);
                }
                
                // Remove item from player's hand (consume the sign)
                if (itemInHand.getAmount() > 1) {
                    itemInHand.setAmount(itemInHand.getAmount() - 1);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
                
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&a✓ Sign placed with auto-filled format!"
                ));
            } else {
                plugin.getLogger().warning("[AutoPaste] Failed to get sign state after placement");
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&cFailed to auto-fill sign. Please try again."
                ));
            }
        });
    }
    
    /**
     * Backup injection if BlockPlaceEvent didn't handle it
     * (e.g., if PacketEvents is handling sign pre-fill)
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        
        // Check if player has pending auto-paste
        if (!wizardManager.hasPendingAutoPaste(player.getUniqueId())) {
            return;
        }
        
        // Consume the pending data (one-time use)
        PendingSignData data = wizardManager.consumePendingAutoPaste(player.getUniqueId());
        if (data == null) {
            return;
        }
        
        // Get the sign lines and inject them into the event
        String[] lines = data.getSignLines();
        
        plugin.getLogger().info("[AutoPaste] SignChangeEvent injection for " + player.getName());
        for (int i = 0; i < 4 && i < lines.length; i++) {
            plugin.getLogger().info("[AutoPaste]   Line " + i + ": " + lines[i]);
            event.line(i, LegacyComponentSerializer.legacyAmpersand().deserialize(lines[i]));
        }
        
        // Send confirmation message
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
            "&a✓ Sign format auto-filled and placed!"
        ));
    }
    
    private boolean isSign(Material material) {
        String name = material.name();
        return name.endsWith("_SIGN") || name.endsWith("_HANGING_SIGN");
    }
}
