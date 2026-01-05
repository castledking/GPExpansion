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
 * Main menu GUI - entry point for /claim command when GUI mode is enabled.
 * Shows admin options if player has admin permission, otherwise shows player menu.
 */
public class MainMenuGUI extends BaseGUI {
    
    private static final String ADMIN_PERMISSION = "griefprevention.admin";
    
    private final boolean isAdmin;
    
    private static final String GLOBALLIST_PERMISSION = "griefprevention.claim.gui.globallist";
    
    // Slot positions for non-admin (3 items centered)
    private int ownedClaimsSlot = 11;
    private int trustedClaimsSlot = 13;
    private int globalListSlot = 15;
    
    // Slot positions for admin (4 items centered)
    private int adminOwnedClaimsSlot = 10;
    private int adminTrustedClaimsSlot = 12;
    private int adminGlobalListSlot = 14;
    private int adminMenuSlot = 16;
    
    public MainMenuGUI(GUIManager manager, Player player) {
        super(manager, player, "main-menu");
        this.isAdmin = player.hasPermission(ADMIN_PERMISSION);
        
        // Load slot positions from config
        if (config != null) {
            // Non-admin slots
            ownedClaimsSlot = config.getInt("slots.owned-claims", 11);
            trustedClaimsSlot = config.getInt("slots.trusted-claims", 13);
            globalListSlot = config.getInt("slots.global-list", 15);
            // Admin slots
            adminOwnedClaimsSlot = config.getInt("slots-admin.admin-owned-claims", 10);
            adminTrustedClaimsSlot = config.getInt("slots-admin.admin-trusted-claims", 12);
            adminGlobalListSlot = config.getInt("slots-admin.admin-global-list", 14);
            adminMenuSlot = config.getInt("slots-admin.admin-menu", 16);
        }
    }
    
    @Override
    public Inventory createInventory() {
        String defaultTitle = isAdmin ? "&6&lClaim Menu &7(Admin)" : "&6&lClaim Menu";
        inventory = createBaseInventory(defaultTitle, 27);
        
        // Fill border
        fillBorder(createFiller());
        
        if (isAdmin) {
            // Admin view - show all options: owned claims, trusted claims, global list, admin menu
            inventory.setItem(adminOwnedClaimsSlot, createOwnedClaimsItem());
            inventory.setItem(adminTrustedClaimsSlot, createTrustedClaimsItem());
            if (player.hasPermission(GLOBALLIST_PERMISSION)) {
                inventory.setItem(adminGlobalListSlot, createGlobalListItem());
            }
            inventory.setItem(adminMenuSlot, createAdminMenuItem());
        } else {
            // Player view - show owned claims, trusted claims, global list (centered for 3 items)
            inventory.setItem(ownedClaimsSlot, createOwnedClaimsItem());
            inventory.setItem(trustedClaimsSlot, createTrustedClaimsItem());
            if (player.hasPermission(GLOBALLIST_PERMISSION)) {
                inventory.setItem(globalListSlot, createGlobalListItem());
            }
        }
        
        return inventory;
    }
    
    private ItemStack createAdminMenuItem() {
        Map<String, String> placeholders = new HashMap<>();
        
        if (config != null && config.contains("items.admin-menu")) {
            return createItemFromConfig("items.admin-menu", placeholders);
        }
        
        // Default item
        List<String> lore = Arrays.asList(
            "&7Access admin claim management",
            "",
            "&eClick to open admin menu"
        );
        return createItem(Material.COMMAND_BLOCK, "&c&lAdmin Menu", lore);
    }
    
    private ItemStack createOwnedClaimsItem() {
        Map<String, String> placeholders = new HashMap<>();
        
        if (config != null && config.contains("items.owned-claims")) {
            return createItemFromConfig("items.owned-claims", placeholders);
        }
        
        // Default item
        List<String> lore = Arrays.asList(
            "&7View all claims you own",
            "",
            "&eClick to view your claims"
        );
        return createItem(Material.DIAMOND, "&b&lMy Claims", lore);
    }
    
    private ItemStack createTrustedClaimsItem() {
        Map<String, String> placeholders = new HashMap<>();
        
        if (config != null && config.contains("items.trusted-claims")) {
            return createItemFromConfig("items.trusted-claims", placeholders);
        }
        
        // Default item
        List<String> lore = Arrays.asList(
            "&7View claims you are trusted in",
            "",
            "&eClick to view trusted claims"
        );
        return createItem(Material.EMERALD, "&a&lTrusted Claims", lore);
    }
    
    private ItemStack createGlobalListItem() {
        Map<String, String> placeholders = new HashMap<>();
        
        if (config != null && config.contains("items.global-list")) {
            return createItemFromConfig("items.global-list", placeholders);
        }
        
        // Default item
        List<String> lore = Arrays.asList(
            "&7Browse publicly listed claims",
            "&7from all players on the server.",
            "",
            "&eClick to view global claim list"
        );
        return createItem(Material.ENDER_EYE, "&d&lGlobal Claim List", lore);
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (isAdmin) {
            // Admin view click handling
            if (slot == adminOwnedClaimsSlot) {
                manager.openOwnedClaims(player);
            } else if (slot == adminTrustedClaimsSlot) {
                manager.openTrustedClaims(player);
            } else if (slot == adminGlobalListSlot && player.hasPermission(GLOBALLIST_PERMISSION)) {
                manager.openGlobalClaimList(player);
            } else if (slot == adminMenuSlot) {
                manager.openAdminMenu(player);
            }
        } else {
            // Player view click handling
            if (slot == ownedClaimsSlot) {
                manager.openOwnedClaims(player);
            } else if (slot == trustedClaimsSlot) {
                manager.openTrustedClaims(player);
            } else if (slot == globalListSlot && player.hasPermission(GLOBALLIST_PERMISSION)) {
                manager.openGlobalClaimList(player);
            }
        }
    }
}
