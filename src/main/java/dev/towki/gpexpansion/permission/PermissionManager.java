package dev.towki.gpexpansion.permission;

import dev.towki.gpexpansion.GPExpansionPlugin;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.permissions.PermissionDefault;

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
        updatePlayerCommandPermissions();
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
    
    /**
     * Update gpx.player permission children based on config.yml player-commands section
     * This dynamically adds/removes child permissions to gpx.player
     */
    public void updatePlayerCommandPermissions() {
        FileConfiguration config = plugin.getConfig();
        List<String> playerCommands = config.getStringList("player-commands");
        
        if (playerCommands.isEmpty()) {
            plugin.getLogger().warning("No player-commands found in config.yml, skipping permission update");
            return;
        }
        
        // Get or create gpx.player permission
        org.bukkit.permissions.Permission gpxPlayerPerm = Bukkit.getPluginManager().getPermission("gpx.player");
        if (gpxPlayerPerm == null) {
            // Create the permission if it doesn't exist
            gpxPlayerPerm = new org.bukkit.permissions.Permission("gpx.player", 
                "Base permission for GPExpansion player commands (children permissions are dynamically managed)",
                PermissionDefault.TRUE);
            Bukkit.getPluginManager().addPermission(gpxPlayerPerm);
            plugin.getLogger().info("Created gpx.player permission");
        }
        
        // Build list of full permission names (with griefprevention. prefix)
        List<String> childPermissions = new ArrayList<>();
        for (String perm : playerCommands) {
            String fullPerm = "griefprevention." + perm;
            childPermissions.add(fullPerm);
            
            // Ensure the child permission exists
            org.bukkit.permissions.Permission childPerm = Bukkit.getPluginManager().getPermission(fullPerm);
            if (childPerm == null) {
                plugin.getLogger().warning("Permission " + fullPerm + " not found in plugin.yml, skipping");
                continue;
            }
        }
        
        // Update children - need to recreate permission with new children map
        java.util.Map<String, Boolean> newChildren = new java.util.HashMap<>();
        
        // Keep non-griefprevention children
        for (java.util.Map.Entry<String, Boolean> entry : gpxPlayerPerm.getChildren().entrySet()) {
            if (!entry.getKey().startsWith("griefprevention.")) {
                newChildren.put(entry.getKey(), entry.getValue());
            }
        }
        
        // Add all child permissions from config
        for (String child : childPermissions) {
            org.bukkit.permissions.Permission childPerm = Bukkit.getPluginManager().getPermission(child);
            if (childPerm != null) {
                newChildren.put(child, true);
            } else {
                plugin.getLogger().warning("Permission " + child + " not found in plugin.yml, skipping");
            }
        }
        
        // Calculate changes for logging (before removing permission)
        long oldGriefPreventionChildren = gpxPlayerPerm.getChildren().keySet().stream()
            .filter(k -> k.startsWith("griefprevention.")).count();
        long newGriefPreventionChildren = newChildren.keySet().stream()
            .filter(k -> k.startsWith("griefprevention.")).count();
        
        int added = (int) (newGriefPreventionChildren - oldGriefPreventionChildren);
        int removed = (int) (oldGriefPreventionChildren - newGriefPreventionChildren);
        
        // Recreate permission with updated children
        Bukkit.getPluginManager().removePermission(gpxPlayerPerm);
        org.bukkit.permissions.Permission newGpxPlayerPerm = new org.bukkit.permissions.Permission(
            "gpx.player",
            "Base permission for GPExpansion player commands (children permissions are dynamically managed)",
            PermissionDefault.TRUE,
            newChildren
        );
        Bukkit.getPluginManager().addPermission(newGpxPlayerPerm);
        
        if (added > 0 || removed > 0) {
            plugin.getLogger().info("Updated gpx.player permission: added " + added + 
                " children, removed " + removed + " children");
        } else {
            plugin.getLogger().fine("gpx.player permission children are up to date");
        }
    }
    
    /**
     * Reload permission manager (called on /gpx reload)
     */
    public void reload() {
        updatePlayerCommandPermissions();
    }
}
