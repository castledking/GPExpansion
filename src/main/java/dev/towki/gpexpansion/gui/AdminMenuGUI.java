package dev.towki.gpexpansion.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin menu GUI - entry point for admin claim management.
 * Shows options to view admin claims or all player claims.
 */
public class AdminMenuGUI extends BaseGUI {
    
    // Slot positions (configurable)
    private int adminClaimsSlot = 2;
    private int allPlayerClaimsSlot = 6;
    private int backSlot = 4;
    
    public AdminMenuGUI(GUIManager manager, Player player) {
        super(manager, player, "admin-menu");
        
        // Load slot positions from config
        if (config != null) {
            adminClaimsSlot = config.getInt("slots.admin-claims", 2);
            allPlayerClaimsSlot = config.getInt("slots.all-player-claims", 6);
            backSlot = config.getInt("slots.back", 4);
        }
    }
    
    @Override
    public Inventory createInventory() {
        inventory = createBaseInventory("&c&lAdmin Claim Menu", 9);
        
        // Fill with glass panes
        ItemStack filler = createFiller();
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, filler);
        }
        
        // Add menu items
        inventory.setItem(adminClaimsSlot, createAdminClaimsItem());
        inventory.setItem(allPlayerClaimsSlot, createAllPlayerClaimsItem());
        inventory.setItem(backSlot, createBackItem());
        
        return inventory;
    }
    
    private ItemStack createAdminClaimsItem() {
        Map<String, String> placeholders = new HashMap<>();
        
        if (config != null && config.contains("items.admin-claims")) {
            return createItemFromConfig("items.admin-claims", placeholders);
        }
        
        // Default item
        List<String> lore = Arrays.asList(
            "&7View and manage all admin claims",
            "&7on the server.",
            "",
            "&7• Search by ID or name",
            "&7• Filter and sort claims",
            "&7• View children claims",
            "&7• Manage claim settings",
            "",
            "&eClick to view admin claims"
        );
        return createItem(Material.COMMAND_BLOCK, "&c&lAdmin Claims", lore);
    }
    
    private ItemStack createAllPlayerClaimsItem() {
        Map<String, String> placeholders = new HashMap<>();
        
        if (config != null && config.contains("items.all-player-claims")) {
            return createItemFromConfig("items.all-player-claims", placeholders);
        }
        
        // Default item
        List<String> lore = Arrays.asList(
            "&7View all player claims",
            "&7on the server.",
            "",
            "&7• Search by player, ID, or name",
            "&7• Filter and sort claims",
            "&7• View children claims",
            "&7• Manage any claim settings",
            "",
            "&eClick to view all player claims"
        );
        return createItem(Material.PLAYER_HEAD, "&a&lAll Player Claims", lore);
    }
    
    private ItemStack createBackItem() {
        Map<String, String> placeholders = new HashMap<>();
        
        if (config != null && config.contains("items.back")) {
            return createItemFromConfig("items.back", placeholders);
        }
        
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to main menu"));
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == adminClaimsSlot) {
            manager.openAdminClaims(player);
        } else if (slot == allPlayerClaimsSlot) {
            manager.openAllPlayerClaims(player);
        } else if (slot == backSlot) {
            manager.openMainMenu(player);
        }
    }
}
