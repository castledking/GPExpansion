package dev.towki.gpexpansion.setup;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.setup.SetupWizardManager.PendingSignData;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for sign creation to auto-fill format from setup wizard.
 * 
 * Uses Spigot/Paper API: Places sign, pre-fills with format text, 
 * opens sign editor for player to edit, then finalizes on completion.
 */
public class SignAutoPasteListener implements Listener {
    
    private final GPExpansionPlugin plugin;
    private final SetupWizardManager wizardManager;
    
    // Track pending sign edits: player UUID -> sign edit session
    private final Map<UUID, PendingSignEdit> pendingEdits = new HashMap<>();
    
    private static class PendingSignEdit {
        final Location signLocation;
        final Material signType;
        final BlockData blockData;
        final ItemStack itemUsed;
        final String[] formatLines;
        
        PendingSignEdit(Location loc, Material type, BlockData data, ItemStack item, String[] lines) {
            this.signLocation = loc;
            this.signType = type;
            this.blockData = data;
            this.itemUsed = item;
            this.formatLines = lines;
        }
    }
    
    public SignAutoPasteListener(GPExpansionPlugin plugin, SetupWizardManager wizardManager) {
        this.plugin = plugin;
        this.wizardManager = wizardManager;
    }
    
    /**
     * No longer needed - kept for API compatibility but does nothing.
     */
    public void setPacketEventsAvailable(boolean available) {
        // No longer used - we use pure Spigot/Paper API now
    }
    
    /**
     * Handle sign placement: cancel, place sign with format, open editor.
     * Only intercepts when the sign is being placed in a claim the player owns (or rents for self mailbox).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
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
        
        // Get the pending data (don't consume yet - wait for SignChangeEvent)
        PendingSignData data = wizardManager.getPendingAutoPaste(player.getUniqueId());
        if (data == null) {
            return;
        }

        // Prevent griefing: only auto-paste in a claim the player owns (or rents for self mailbox)
        if (!wizardManager.canCreateSignAtLocation(player, block.getLocation(), data)) {
            return; // Do not cancel - let normal placement run; GP will cancel if they can't build
        }
        
        String[] lines = data.getSignLines();
        
        plugin.getLogger().info("[AutoPaste] Intercepting sign placement for " + player.getName());
        
        // Cancel the original event
        event.setCancelled(true);
        
        // Store block info
        Location loc = block.getLocation();
        Material signType = block.getType();
        BlockData blockData = block.getBlockData().clone();
        ItemStack itemInHand = event.getItemInHand().clone();
        
        // Track this pending edit
        pendingEdits.put(player.getUniqueId(), 
            new PendingSignEdit(loc, signType, blockData, itemInHand, lines));
        
        // Schedule sign placement and editor opening
        runAtLocation(loc, () -> {
            // Place the sign block
            Block targetBlock = loc.getBlock();
            targetBlock.setType(signType, false);
            targetBlock.setBlockData(blockData, false);
            
            if (targetBlock.getState() instanceof Sign sign) {
                // Pre-fill with format lines
                for (int i = 0; i < 4 && i < lines.length; i++) {
                    try {
                        sign.getSide(Side.FRONT).setLine(i, lines[i] != null ? lines[i] : "");
                    } catch (NoSuchMethodError | NoClassDefFoundError e) {
                        sign.setLine(i, lines[i] != null ? lines[i] : "");
                    }
                }
                sign.update(true, false);
                
                // Open sign editor for player to edit
                runOnPlayer(player, () -> {
                    try {
                        player.openSign(sign, Side.FRONT);
                    } catch (NoSuchMethodError | NoClassDefFoundError e) {
                        player.openSign(sign);
                    }
                    
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&e&lSign Auto-Paste: &7Edit if needed, then click Done."));
                }, 2L);
            } else {
                // Failed to place sign
                pendingEdits.remove(player.getUniqueId());
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&cFailed to place sign. Please try again."));
            }
        });
    }
    
    /**
     * Handle sign edit completion: finalize the sign and consume the item.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if this is a pending edit we're tracking
        PendingSignEdit pendingEdit = pendingEdits.remove(playerId);
        if (pendingEdit == null) {
            // Not our sign edit - check if there's unclaimed auto-paste data (e.g. they placed sign without our intercept)
            if (wizardManager.hasPendingAutoPaste(playerId)) {
                PendingSignData data = wizardManager.getPendingAutoPaste(playerId);
                if (data != null) {
                    // Prevent griefing: only inject if they're in a claim they own (or rent for self mailbox)
                    if (!wizardManager.canCreateSignAtLocation(player, event.getBlock().getLocation(), data)) {
                        wizardManager.consumePendingAutoPaste(playerId); // Consume so we don't inject elsewhere
                        return;
                    }
                    wizardManager.consumePendingAutoPaste(playerId);
                    String[] lines = data.getSignLines();
                    for (int i = 0; i < 4 && i < lines.length; i++) {
                        event.line(i, LegacyComponentSerializer.legacyAmpersand().deserialize(lines[i] != null ? lines[i] : ""));
                    }
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&a✓ Sign format auto-filled!"));
                }
            }
            return;
        }
        
        // Verify this is the sign we placed
        if (!event.getBlock().getLocation().equals(pendingEdit.signLocation)) {
            // Wrong sign - put pending edit back
            pendingEdits.put(playerId, pendingEdit);
            return;
        }
        
        // Consume the auto-paste data now
        wizardManager.consumePendingAutoPaste(playerId);
        
        // Consume the sign item from player's inventory
        ItemStack itemUsed = pendingEdit.itemUsed;
        if (itemUsed != null && itemUsed.getType() != Material.AIR) {
            // Find and remove one sign from inventory
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            ItemStack offHand = player.getInventory().getItemInOffHand();
            
            if (mainHand.getType() == itemUsed.getType()) {
                if (mainHand.getAmount() > 1) {
                    mainHand.setAmount(mainHand.getAmount() - 1);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
            } else if (offHand.getType() == itemUsed.getType()) {
                if (offHand.getAmount() > 1) {
                    offHand.setAmount(offHand.getAmount() - 1);
                } else {
                    player.getInventory().setItemInOffHand(null);
                }
            }
        }
        
        plugin.getLogger().info("[AutoPaste] Sign completed by " + player.getName());
        
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
            "&a✓ Sign placed successfully!"));
    }
    
    private boolean isSign(Material material) {
        String name = material.name();
        return name.endsWith("_SIGN") || name.endsWith("_HANGING_SIGN");
    }
    
    private void runOnPlayer(Player player, Runnable task, long delayTicks) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runLaterEntity(plugin, player, task, delayTicks);
    }
    
    private void runAtLocation(Location loc, Runnable task) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocation(plugin, loc, task);
    }
}
