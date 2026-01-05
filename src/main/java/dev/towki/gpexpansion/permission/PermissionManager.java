package dev.towki.gpexpansion.permission;

import dev.towki.gpexpansion.GPExpansionPlugin;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for integrating with Vault Permission API to manage dynamic permissions
 */
public class PermissionManager {
    
    private final GPExpansionPlugin plugin;
    private Permission vaultPermission = null;
    
    public PermissionManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        setupVault();
    }
    
    /**
     * Setup Vault permission provider
     */
    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().info("Vault not found - permission management via /gpx max will use in-memory limits only");
            return;
        }
        
        try {
            RegisteredServiceProvider<Permission> rsp = Bukkit.getServicesManager().getRegistration(Permission.class);
            if (rsp != null) {
                vaultPermission = rsp.getProvider();
                plugin.getLogger().info("Vault permission provider found: " + vaultPermission.getName());
            } else {
                plugin.getLogger().warning("Vault found but no permission provider registered");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to setup Vault permissions: " + e.getMessage());
        }
    }
    
    /**
     * Clean up sell sign permissions for a player
     */
    public boolean cleanupSellPermissions(Player player, int newLimit) {
        return cleanupPermissions(player, "griefprevention.sign.create.buy.", newLimit, "sell sign");
    }
    
    /**
     * Clean up rent sign permissions for a player
     */
    public boolean cleanupRentPermissions(Player player, int newLimit) {
        return cleanupPermissions(player, "griefprevention.sign.create.rent.", newLimit, "rent sign");
    }
    
    /**
     * Clean up mailbox sign permissions for a player
     */
    public boolean cleanupMailboxPermissions(Player player, int newLimit) {
        return cleanupPermissions(player, "griefprevention.sign.create.mailbox.", newLimit, "mailbox sign");
    }
    
    /**
     * Clean up global claim permissions for a player
     */
    public boolean cleanupGlobalClaimPermissions(Player player, int newLimit) {
        return cleanupPermissions(player, "griefprevention.claim.makeglobal.", newLimit, "global claim");
    }
    
    /**
     * Generic permission cleanup using Vault API
     */
    private boolean cleanupPermissions(Player player, String permissionPrefix, int newLimit, String typeName) {
        if (vaultPermission == null) {
            plugin.getLogger().warning("Cannot cleanup permissions - Vault not available");
            return false;
        }
        
        try {
            // Find all existing numbered permissions with this prefix
            List<String> toRemove = new ArrayList<>();
            for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                String perm = info.getPermission();
                if (perm.startsWith(permissionPrefix) && info.getValue()) {
                    // Check if it ends with a number
                    String suffix = perm.substring(permissionPrefix.length());
                    try {
                        Integer.parseInt(suffix);
                        toRemove.add(perm);
                    } catch (NumberFormatException ignored) {
                        // Not a numbered permission, skip
                    }
                }
            }
            
            // Remove old permissions
            for (String perm : toRemove) {
                vaultPermission.playerRemove(null, player, perm);
            }
            
            // Add new permission
            String newPerm = permissionPrefix + newLimit;
            vaultPermission.playerAdd(null, player, newPerm);
            
            plugin.getLogger().info("Cleaned up " + typeName + " permissions for " + player.getName() + 
                " (removed " + toRemove.size() + " old permissions, set to " + newLimit + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to cleanup permissions via Vault: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if permission cleanup is supported (Vault available)
     */
    public boolean isCleanupSupported() {
        return vaultPermission != null;
    }
    
    /**
     * Get the name of the permission provider
     */
    public String getSupportedPlugin() {
        if (vaultPermission != null) {
            return "Vault (" + vaultPermission.getName() + ")";
        }
        return "None";
    }
}
