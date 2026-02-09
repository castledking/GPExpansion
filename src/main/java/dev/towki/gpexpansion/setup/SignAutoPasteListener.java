package dev.towki.gpexpansion.setup;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.setup.SetupWizardManager.PendingSignData;

import net.kyori.adventure.text.Component;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
     * Runs at LOWEST so we intercept before other plugins (e.g. GriefPrevention) can cancel.
     * Only intercepts when the sign is being placed in a claim the player owns (or rents for self mailbox).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        
        if (!isSign(block.getType())) {
            return;
        }
        
        if (!wizardManager.hasPendingAutoPaste(player.getUniqueId())) {
            return;
        }
        
        PendingSignData data = wizardManager.getPendingAutoPaste(player.getUniqueId());
        if (data == null) {
            return;
        }

        // Allow paste if: in allowed claim, OR has the .anywhere permission for this sign type
        boolean inAllowedClaim = wizardManager.canCreateSignAtLocation(player, block.getLocation(), data);
        String anywherePerm = wizardManager.getAnywherePermission(data);
        boolean hasAnywhere = anywherePerm != null && player.hasPermission(anywherePerm);
        if (!inAllowedClaim && !hasAnywhere) {
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("[AutoPaste] Skipping " + player.getName() + " - sign not in allowed claim and no .anywhere permission");
            }
            if (wizardManager.hasClaimAt(block.getLocation())) {
                plugin.getMessages().send(player, "wizard.autopaste-place-in-claim");
            } else {
                plugin.getMessages().send(player, "wizard.autopaste-anywhere-required", "{permission}", anywherePerm != null ? anywherePerm : "");
            }
            return;
        }
        
        boolean hanging = block.getType().name().contains("HANGING");
        String[] lines = data.getSignLines(hanging);
        
        if (plugin.getConfigManager().isDebugEnabled()) {
            plugin.getLogger().info("[AutoPaste] Intercepting sign placement for " + player.getName() + " at " + block.getLocation());
        }
        
        event.setCancelled(true);
        
        // Store block info
        Location loc = block.getLocation();
        Material signType = block.getType();
        BlockData blockData = block.getBlockData().clone();
        ItemStack itemInHand = event.getItemInHand().clone();
        
        // Track this pending edit
        pendingEdits.put(player.getUniqueId(), 
            new PendingSignEdit(loc, signType, blockData, itemInHand, lines));
        
        // Schedule sign placement 1 tick later (so cancel is applied), then send content to client and open editor.
        runAtLocationLater(loc, () -> {
            Block targetBlock = loc.getBlock();
            targetBlock.setType(signType, false);
            targetBlock.setBlockData(blockData, false);
            if (!(targetBlock.getState() instanceof Sign sign)) {
                pendingEdits.remove(player.getUniqueId());
                runOnPlayer(player, () -> plugin.getMessages().send(player, "wizard.autopaste-failed-place"), 1L);
                return;
            }
            for (int i = 0; i < 4 && i < lines.length; i++) {
                String line = lines[i] != null ? lines[i] : "";
                try {
                    sign.getSide(Side.FRONT).setLine(i, line);
                } catch (NoSuchMethodError | NoClassDefFoundError e) {
                    sign.setLine(i, line);
                }
            }
            sign.update(true, false);
            List<Component> components = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                String line = i < lines.length && lines[i] != null ? lines[i] : "";
                components.add(LegacyComponentSerializer.legacySection().deserialize(line));
            }
            runOnPlayer(player, () -> {
                Block b = loc.getBlock();
                if (b.getState() instanceof Sign openSign) {
                    player.sendSignChange(loc, components);
                    try {
                        player.openSign(openSign, Side.FRONT);
                    } catch (NoSuchMethodError | NoClassDefFoundError e) {
                        player.openSign(openSign);
                    }
                    plugin.getMessages().send(player, "wizard.autopaste-edit-then-done");
                } else {
                    pendingEdits.remove(player.getUniqueId());
                    plugin.getMessages().send(player, "wizard.autopaste-sign-removed");
                }
            }, 2L);
        }, 1L);
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
                    plugin.getMessages().send(player, "wizard.autopaste-format-filled");
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
        
        if (plugin.getConfigManager().isDebugEnabled()) {
            plugin.getLogger().info("[AutoPaste] Sign completed by " + player.getName());
        }
        plugin.getMessages().send(player, "wizard.autopaste-placed-success");
    }
    
    private boolean isSign(Material material) {
        String name = material.name();
        return name.endsWith("_SIGN") || name.endsWith("_HANGING_SIGN");
    }
    
    private void runOnPlayer(Player player, Runnable task, long delayTicks) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runLaterEntity(plugin, player, task, delayTicks);
    }

    private void runAtLocationLater(Location loc, Runnable task, long delayTicks) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocationLater(plugin, loc, task, delayTicks);
    }
}
