package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Intercepts claim abandonment commands to prevent abandoning claims with active rentals.
 */
public class ClaimAbandonListener implements Listener {
    
    private final GPExpansionPlugin plugin;
    
    public ClaimAbandonListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase();
        Player player = event.getPlayer();
        
        // Check for abandonclaim commands
        if (command.startsWith("/abandonclaim") || command.startsWith("/claim abandon")) {
            // Extract claim ID if present
            String[] args = command.split(" ");
            String claimId = null;
            
            // Find claim ID in arguments
            for (int i = 1; i < args.length; i++) {
                if (args[i].matches("\\d+")) {
                    claimId = args[i];
                    break;
                }
            }
            
            // If no claim ID specified, try to get claim at player's location
            if (claimId == null) {
                claimId = getCurrentClaimId(player);
            }
            
            if (claimId != null) {
                // Check if claim has active rental
                if (hasActiveRental(claimId)) {
                    // Check if eviction process is in place
                    ClaimDataStore dataStore = plugin.getClaimDataStore();
                    ClaimDataStore.EvictionData eviction = dataStore.getEviction(claimId).orElse(null);
                    if (eviction == null) {
                        // Block the abandonment
                        event.setCancelled(true);
                        plugin.getMessages().send(player, "eviction.active-renter");
                        plugin.getMessages().send(player, "eviction.start-eviction-notice", 
                            "{command}", plugin.getMessages().getRaw("eviction.abandon-eviction-command", "{id}", claimId),
                            "{duration}", plugin.getEvictionNoticePeriodDisplay());
                        return;
                    } else {
                        boolean effective = System.currentTimeMillis() >= eviction.effectiveAt;
                        if (!effective && !player.hasPermission("griefprevention.eviction.bypass")) {
                            // Eviction is still pending
                            event.setCancelled(true);
                            plugin.getMessages().send(player, "eviction.notice-pending");
                            long remaining = eviction.effectiveAt - System.currentTimeMillis();
                            String timeRemaining = formatDuration(remaining);
                            plugin.getMessages().send(player, "eviction.time-remaining", "{time}", timeRemaining);
                            plugin.getMessages().send(player, "eviction.cannot-abandon-during-eviction");
                            return;
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Get the claim ID at the player's current location.
     */
    private String getCurrentClaimId(Player player) {
        try {
            var gp = new dev.towki.gpexpansion.gp.GPBridge();
            java.util.Optional<Object> claimOpt = gp.getClaimAt(player.getLocation());
            if (claimOpt.isPresent()) {
                Object claim = claimOpt.get();
                Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
                if (ownerId != null && ownerId.equals(player.getUniqueId())) {
                    // Try to get claim ID from claim data
                    Object claimId = claim.getClass().getMethod("getID").invoke(claim);
                    if (claimId != null) {
                        return claimId.toString();
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting claim ID for abandon check: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Check if a claim has an active rental.
     */
    private boolean hasActiveRental(String claimId) {
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        ClaimDataStore.RentalData rental = dataStore.getRental(claimId).orElse(null);
        if (rental != null && rental.expiry > System.currentTimeMillis()) {
            return true;
        }
        
        // Also check sign PDC for renter info
        // This catches cases where rental store might be out of sync
        return hasRenterSign(claimId);
    }
    
    /**
     * Check if there's a rental sign with a renter for this claim.
     */
    private boolean hasRenterSign(String claimId) {
        try {
            var gp = new dev.towki.gpexpansion.gp.GPBridge();
            var claimOpt = gp.findClaimById(claimId);
            if (claimOpt.isPresent()) {
                Object claim = claimOpt.get();
                var locationOpt = gp.getClaimCenter(claim);
                if (locationOpt.isPresent()) {
                    org.bukkit.Location center = locationOpt.get();
                    // Check for rental signs around the claim center
                    for (int x = -5; x <= 5; x++) {
                        for (int y = -3; y <= 3; y++) {
                            for (int z = -5; z <= 5; z++) {
                                org.bukkit.Location checkLoc = center.clone().add(x, y, z);
                                org.bukkit.block.Block block = checkLoc.getBlock();
                                if (block.getType().name().contains("SIGN")) {
                                    org.bukkit.block.Sign sign = (org.bukkit.block.Sign) block.getState();
                                    var keyRenter = new org.bukkit.NamespacedKey(plugin, "rent.renter");
                                    String renterStr = sign.getPersistentDataContainer().get(keyRenter, org.bukkit.persistence.PersistentDataType.STRING);
                                    if (renterStr != null && !renterStr.isEmpty()) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking for renter signs: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Format duration in milliseconds to human readable format.
     */
    private String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return days + " day" + (days != 1 ? "s" : "");
        } else if (hours > 0) {
            return hours + " hour" + (hours != 1 ? "s" : "");
        } else if (minutes > 0) {
            return minutes + " minute" + (minutes != 1 ? "s" : "");
        } else {
            return seconds + " second" + (seconds != 1 ? "s" : "");
        }
    }
}
