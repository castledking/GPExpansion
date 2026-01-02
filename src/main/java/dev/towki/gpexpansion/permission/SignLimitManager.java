package dev.towki.gpexpansion.permission;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages permission-based sign limits for players
 */
public class SignLimitManager {
    
    private final GPExpansionPlugin plugin;
    private final PermissionManager permissionManager;
    private final Map<UUID, Integer> sellLimits = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> rentLimits = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> mailboxLimits = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> permissionOverride = new ConcurrentHashMap<>();
    private int defaultSellLimit;
    private int defaultRentLimit;
    private int defaultMailboxLimit;
    
    public SignLimitManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.permissionManager = new PermissionManager(plugin);
        loadConfig();
    }
    
    /**
     * Load configuration values
     */
    private void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        defaultSellLimit = config.getInt("defaults.max-sell-signs", 5);
        defaultRentLimit = config.getInt("defaults.max-rent-signs", 5);
        defaultMailboxLimit = config.getInt("defaults.max-mailbox-signs", 5);
    }
    
    /**
     * Reload configuration values
     */
    public void reloadConfig() {
        loadConfig();
        // Clear cached limits to force re-evaluation
        sellLimits.clear();
        rentLimits.clear();
        mailboxLimits.clear();
    }
    
    /**
     * Get the maximum number of sell signs a player can create
     */
    public int getSellLimit(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Check if we have a cached value and no permission override
        if (sellLimits.containsKey(uuid) && !permissionOverride.getOrDefault(uuid, false)) {
            return sellLimits.get(uuid);
        }
        
        // Check permissions for specific limits
        int limit = defaultSellLimit;
        List<String> foundPerms = new ArrayList<>();
        
        // Check for numbered permissions (griefprevention.sign.create.buy.<amount>)
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (perm.startsWith("griefprevention.sign.create.buy.") && info.getValue()) {
                try {
                    int amount = Integer.parseInt(perm.substring(perm.lastIndexOf('.') + 1));
                    if (amount > limit) {
                        limit = amount;
                    }
                    foundPerms.add(perm);
                } catch (NumberFormatException ignored) {}
            }
        }
        
        // Check for permission desync - multiple permissions found
        if (foundPerms.size() > 1) {
            plugin.getLogger().warning("Permission desync detected for player " + player.getName() + 
                ": Found multiple sell sign permissions: " + String.join(", ", foundPerms));
            // Mark as needing cleanup
            permissionOverride.put(uuid, true);
        }
        
        // Cache the result
        sellLimits.put(uuid, limit);
        return limit;
    }
    
    /**
     * Get the maximum number of rent signs a player can create
     */
    public int getRentLimit(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Check if we have a cached value and no permission override
        if (rentLimits.containsKey(uuid) && !permissionOverride.getOrDefault(uuid, false)) {
            return rentLimits.get(uuid);
        }
        
        // Check permissions for specific limits
        int limit = defaultRentLimit;
        List<String> foundPerms = new ArrayList<>();
        
        // Check for numbered permissions (griefprevention.sign.create.rent.<amount>)
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (perm.startsWith("griefprevention.sign.create.rent.") && info.getValue()) {
                try {
                    int amount = Integer.parseInt(perm.substring(perm.lastIndexOf('.') + 1));
                    if (amount > limit) {
                        limit = amount;
                    }
                    foundPerms.add(perm);
                } catch (NumberFormatException ignored) {}
            }
        }
        
        // Check for permission desync - multiple permissions found
        if (foundPerms.size() > 1) {
            plugin.getLogger().warning("Permission desync detected for player " + player.getName() + 
                ": Found multiple rent sign permissions: " + String.join(", ", foundPerms));
            // Mark as needing cleanup
            permissionOverride.put(uuid, true);
        }
        
        // Cache the result
        rentLimits.put(uuid, limit);
        return limit;
    }
    
    /**
     * Get the maximum number of mailbox signs a player can create
     */
    public int getMailboxLimit(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Check if we have a cached value and no permission override
        if (mailboxLimits.containsKey(uuid) && !permissionOverride.getOrDefault(uuid, false)) {
            return mailboxLimits.get(uuid);
        }
        
        // Check permissions for specific limits
        int limit = defaultMailboxLimit;
        List<String> foundPerms = new ArrayList<>();
        
        // Check for numbered permissions (griefprevention.sign.create.mailbox.<amount>)
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (perm.startsWith("griefprevention.sign.create.mailbox.") && info.getValue()) {
                try {
                    int amount = Integer.parseInt(perm.substring(perm.lastIndexOf('.') + 1));
                    if (amount > limit) {
                        limit = amount;
                    }
                    foundPerms.add(perm);
                } catch (NumberFormatException ignored) {}
            }
        }
        
        // Check for permission desync - multiple permissions found
        if (foundPerms.size() > 1) {
            plugin.getLogger().warning("Permission desync detected for player " + player.getName() + 
                ": Found multiple mailbox sign permissions: " + String.join(", ", foundPerms));
            // Mark as needing cleanup
            permissionOverride.put(uuid, true);
        }
        
        // Cache the result
        mailboxLimits.put(uuid, limit);
        return limit;
    }
    
    /**
     * Set a player's sell limit directly (used for admin commands)
     * Also cleans up permission desync by removing all numbered permissions and setting the highest one
     */
    public void setSellLimit(Player player, int limit) {
        UUID uuid = player.getUniqueId();
        
        // Check for permission desync and clean up if needed
        if (permissionOverride.getOrDefault(uuid, false)) {
            cleanupSellPermissions(player, limit);
            permissionOverride.put(uuid, false);
        }
        
        sellLimits.put(uuid, Math.max(0, limit));
    }
    
    /**
     * Set a player's rent limit directly (used for admin commands)
     * Also cleans up permission desync by removing all numbered permissions and setting the highest one
     */
    public void setRentLimit(Player player, int limit) {
        UUID uuid = player.getUniqueId();
        
        // Check for permission desync and clean up if needed
        if (permissionOverride.getOrDefault(uuid, false)) {
            cleanupRentPermissions(player, limit);
            permissionOverride.put(uuid, false);
        }
        
        rentLimits.put(uuid, Math.max(0, limit));
    }
    
    /**
     * Add to a player's sell limit
     */
    public void addSellLimit(Player player, int amount) {
        int current = getSellLimit(player);
        setSellLimit(player, current + amount);
    }
    
    /**
     * Take from a player's sell limit
     */
    public void takeSellLimit(Player player, int amount) {
        int current = getSellLimit(player);
        setSellLimit(player, Math.max(0, current - amount));
    }
    
    /**
     * Add to a player's rent limit
     */
    public void addRentLimit(Player player, int amount) {
        int current = getRentLimit(player);
        setRentLimit(player, current + amount);
    }
    
    /**
     * Take from a player's rent limit
     */
    public void takeRentLimit(Player player, int amount) {
        int current = getRentLimit(player);
        setRentLimit(player, Math.max(0, current - amount));
    }
    
    /**
     * Set a player's mailbox limit directly (used for admin commands)
     * Also cleans up permission desync by removing all numbered permissions and setting the highest one
     */
    public void setMailboxLimit(Player player, int limit) {
        UUID uuid = player.getUniqueId();
        
        // Check for permission desync and clean up if needed
        if (permissionOverride.getOrDefault(uuid, false)) {
            cleanupMailboxPermissions(player, limit);
            permissionOverride.put(uuid, false);
        }
        
        // Cache the new limit
        mailboxLimits.put(uuid, limit);
    }
    
    /**
     * Add to a player's mailbox limit
     */
    public void addMailboxLimit(Player player, int amount) {
        int current = getMailboxLimit(player);
        setMailboxLimit(player, current + amount);
    }
    
    /**
     * Take from a player's mailbox limit
     */
    public void takeMailboxLimit(Player player, int amount) {
        int current = getMailboxLimit(player);
        setMailboxLimit(player, Math.max(0, current - amount));
    }
    
    /**
     * Clear cached limits for a player (call when permissions change)
     */
    public void clearCache(Player player) {
        UUID uuid = player.getUniqueId();
        sellLimits.remove(uuid);
        rentLimits.remove(uuid);
        mailboxLimits.remove(uuid);
        permissionOverride.remove(uuid);
    }
    
    /**
     * Clear all cached limits
     */
    public void clearAllCache() {
        sellLimits.clear();
        rentLimits.clear();
        mailboxLimits.clear();
        permissionOverride.clear();
    }
    
    /**
     * Check if player has permission desync
     */
    public boolean hasPermissionDesync(Player player) {
        return permissionOverride.getOrDefault(player.getUniqueId(), false);
    }
    
    /**
     * Clean up sell sign permissions by removing all numbered permissions and adding the highest one
     */
    private void cleanupSellPermissions(Player player, int newLimit) {
        if (permissionManager.cleanupSellPermissions(player, newLimit)) {
            plugin.getLogger().info("Successfully cleaned up sell sign permissions for " + player.getName());
        } else {
            plugin.getLogger().warning("Could not clean up sell sign permissions for " + player.getName() + 
                " - no supported permission plugin found");
        }
    }
    
    /**
     * Clean up rent sign permissions by removing all numbered permissions and adding the highest one
     */
    private void cleanupRentPermissions(Player player, int newLimit) {
        if (permissionManager.cleanupRentPermissions(player, newLimit)) {
            plugin.getLogger().info("Successfully cleaned up rent sign permissions for " + player.getName());
        } else {
            plugin.getLogger().warning("Could not clean up rent sign permissions for " + player.getName() + 
                " - no supported permission plugin found");
        }
    }
    
    /**
     * Clean up mailbox sign permissions by removing all numbered permissions and adding the highest one
     */
    private void cleanupMailboxPermissions(Player player, int newLimit) {
        if (permissionManager.cleanupMailboxPermissions(player, newLimit)) {
            plugin.getLogger().info("Successfully cleaned up mailbox sign permissions for " + player.getName());
        } else {
            plugin.getLogger().warning("Could not clean up mailbox sign permissions for " + player.getName() + 
                " - no supported permission plugin found");
        }
    }
    
    /**
     * Get the current number of signs a player has created
     * This would need to be tracked separately or counted from existing signs
     */
    public int getCurrentSellSigns(Player player) {
        // TODO: Implement tracking of current signs
        // This could be done by counting signs in the world owned by the player
        // or by maintaining a database of created signs
        return 0;
    }
    
    /**
     * Get the current number of rent signs a player has created
     */
    public int getCurrentRentSigns(Player player) {
        // TODO: Implement tracking of current signs
        return 0;
    }
    
    /**
     * Get the current number of mailbox signs a player has created
     */
    public int getCurrentMailboxSigns(Player player) {
        // TODO: Implement tracking of current signs
        return 0;
    }
    
    /**
     * Check if a player can create more sell signs
     */
    public boolean canCreateSellSign(Player player) {
        return getCurrentSellSigns(player) < getSellLimit(player);
    }
    
    /**
     * Check if a player can create more rent signs
     */
    public boolean canCreateRentSign(Player player) {
        return getCurrentRentSigns(player) < getRentLimit(player);
    }
    
    /**
     * Check if a player can create more mailbox signs
     */
    public boolean canCreateMailboxSign(Player player) {
        return getCurrentMailboxSigns(player) < getMailboxLimit(player);
    }
    
    /**
     * Check if permission cleanup is supported
     */
    public boolean isPermissionCleanupSupported() {
        return permissionManager.isCleanupSupported();
    }
    
    /**
     * Get the name of the supported permission plugin
     */
    public String getSupportedPermissionPlugin() {
        return permissionManager.getSupportedPlugin();
    }
}
