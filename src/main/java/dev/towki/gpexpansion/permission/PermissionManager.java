package dev.towki.gpexpansion.permission;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for integrating with permission plugins to clean up permission desync
 */
public class PermissionManager {
    
    private final GPExpansionPlugin plugin;
    private final ConcurrentHashMap<String, Boolean> supportedPlugins = new ConcurrentHashMap<>();
    
    public PermissionManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        detectPermissionPlugins();
    }
    
    /**
     * Detect which permission plugins are available
     */
    private void detectPermissionPlugins() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            supportedPlugins.put("luckperms", true);
            plugin.getLogger().info("LuckPerms detected - permission cleanup available");
        }
        if (Bukkit.getPluginManager().getPlugin("PermissionsEx") != null) {
            supportedPlugins.put("pex", true);
            plugin.getLogger().info("PermissionsEx detected - permission cleanup available");
        }
        if (Bukkit.getPluginManager().getPlugin("zPermissions") != null) {
            supportedPlugins.put("zpermissions", true);
            plugin.getLogger().info("zPermissions detected - permission cleanup available");
        }
    }
    
    /**
     * Clean up sell sign permissions for a player
     */
    public boolean cleanupSellPermissions(Player player, int newLimit) {
        if (supportedPlugins.containsKey("luckperms")) {
            return cleanupLuckPermsSell(player, newLimit);
        }
        // Add other plugin support here
        return false;
    }
    
    /**
     * Clean up rent sign permissions for a player
     */
    public boolean cleanupRentPermissions(Player player, int newLimit) {
        if (supportedPlugins.containsKey("luckperms")) {
            return cleanupLuckPermsRent(player, newLimit);
        }
        // Add other plugin support here
        return false;
    }
    
    /**
     * Clean up mailbox sign permissions for a player
     */
    public boolean cleanupMailboxPermissions(Player player, int newLimit) {
        if (supportedPlugins.containsKey("luckperms")) {
            return cleanupLuckPermsMailbox(player, newLimit);
        }
        // Add other plugin support here
        return false;
    }
    
    /**
     * Clean up permissions using LuckPerms
     */
    private boolean cleanupLuckPermsSell(Player player, int newLimit) {
        try {
            // Remove all existing sell sign permissions
            String clearCommand = String.format("lp user %s permission cleartemp griefprevention.sign.create.buy.*", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clearCommand);
            
            // Set the new permission
            String setCommand = String.format("lp user %s permission set griefprevention.sign.create.buy.%d", player.getName(), newLimit);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), setCommand);
            
            plugin.getLogger().info("Cleaned up LuckPerms sell sign permissions for " + player.getName() + 
                " (set to " + newLimit + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to cleanup LuckPerms permissions: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Clean up permissions using LuckPerms
     */
    private boolean cleanupLuckPermsRent(Player player, int newLimit) {
        try {
            // Remove all existing rent sign permissions
            String clearCommand = String.format("lp user %s permission cleartemp griefprevention.sign.create.rent.*", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clearCommand);
            
            // Set the new permission
            String setCommand = String.format("lp user %s permission set griefprevention.sign.create.rent.%d", player.getName(), newLimit);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), setCommand);
            
            plugin.getLogger().info("Cleaned up LuckPerms rent sign permissions for " + player.getName() + 
                " (set to " + newLimit + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to cleanup LuckPerms permissions: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Clean up permissions using LuckPerms
     */
    private boolean cleanupLuckPermsMailbox(Player player, int newLimit) {
        try {
            // Remove all existing mailbox sign permissions
            String clearCommand = String.format("lp user %s permission cleartemp griefprevention.sign.create.mailbox.*", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clearCommand);
            
            // Set the new permission
            String setCommand = String.format("lp user %s permission set griefprevention.sign.create.mailbox.%d", player.getName(), newLimit);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), setCommand);
            
            plugin.getLogger().info("Cleaned up LuckPerms mailbox sign permissions for " + player.getName() + 
                " (set to " + newLimit + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to cleanup LuckPerms permissions: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if permission cleanup is supported
     */
    public boolean isCleanupSupported() {
        return !supportedPlugins.isEmpty();
    }
    
    /**
     * Get the name of the supported permission plugin
     */
    public String getSupportedPlugin() {
        if (supportedPlugins.containsKey("luckperms")) return "LuckPerms";
        if (supportedPlugins.containsKey("pex")) return "PermissionsEx";
        if (supportedPlugins.containsKey("zpermissions")) return "zPermissions";
        return "None";
    }
}
