package codes.castled.gpexpansion.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import codes.castled.gpexpansion.market.MarketManager;

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
    private int claimMarketSlot = 13;
    private int globalListSlot = 15;
    
    // Slot positions for admin (4 items centered)
    private int adminOwnedClaimsSlot = 10;
    private int adminClaimMarketSlot = 12;
    private int adminGlobalListSlot = 14;
    private int adminMenuSlot = 16;

    private boolean globalClaimsEnabled() {
        return plugin.getConfigManager().isGlobalClaimsEnabled();
    }
    
    public MainMenuGUI(GUIManager manager, Player player) {
        super(manager, player, "main-menu");
        this.isAdmin = player.hasPermission(ADMIN_PERMISSION);
        
        // Load slot positions from config (with backward-compatible fallback to trusted-claims slot keys)
        if (config != null) {
            ownedClaimsSlot = config.getInt("slots.owned-claims", 11);
            claimMarketSlot = config.contains("slots.claim-market")
                ? config.getInt("slots.claim-market", 13)
                : config.getInt("slots.trusted-claims", 13);
            globalListSlot = config.getInt("slots.global-list", 15);

            adminOwnedClaimsSlot = config.getInt("slots-admin.admin-owned-claims", 10);
            adminClaimMarketSlot = config.contains("slots-admin.admin-claim-market")
                ? config.getInt("slots-admin.admin-claim-market", 12)
                : config.getInt("slots-admin.admin-trusted-claims", 12);
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
            // Admin view - show all options: owned claims, claim market, global list, admin menu
            inventory.setItem(adminOwnedClaimsSlot, createOwnedClaimsItem());
            if (plugin.getConfigManager().isMarketEnabled() && player.hasPermission(MarketManager.PERM_MARKET)) {
                inventory.setItem(adminClaimMarketSlot, createClaimMarketItem());
            }
            if (globalClaimsEnabled() && player.hasPermission(GLOBALLIST_PERMISSION)) {
                inventory.setItem(adminGlobalListSlot, createGlobalListItem());
            }
            inventory.setItem(adminMenuSlot, createAdminMenuItem());
        } else {
            // Player view - show owned claims, claim market, global list (centered for 3 items)
            inventory.setItem(ownedClaimsSlot, createOwnedClaimsItem());
            if (plugin.getConfigManager().isMarketEnabled() && player.hasPermission(MarketManager.PERM_MARKET)) {
                inventory.setItem(claimMarketSlot, createClaimMarketItem());
            }
            if (globalClaimsEnabled() && player.hasPermission(GLOBALLIST_PERMISSION)) {
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
            "&7View owned, trusted, rented, bought,",
            "&7mailbox, and listed claims",
            "",
            "&eClick to view your claims"
        );
        return createItem(Material.DIAMOND, "&b&lMy Claims", lore);
    }
    
    private ItemStack createClaimMarketItem() {
        Map<String, String> placeholders = new HashMap<>();
        
        if (config != null && config.contains("items.claim-market")) {
            return createItemFromConfig("items.claim-market", placeholders);
        }
        
        List<String> lore = Arrays.asList(
            "&7Browse claims & mailboxes for rent or sale",
            "&7and send remote gifts or letters.",
            "",
            "&eClick to open Claim Market"
        );
        return createItem(Material.EMERALD, "&a&lClaim Market", lore);
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
            if (slot == adminOwnedClaimsSlot) {
                manager.openOwnedClaims(player);
            } else if (slot == adminClaimMarketSlot && plugin.getConfigManager().isMarketEnabled()
                    && player.hasPermission(MarketManager.PERM_MARKET)) {
                manager.openClaimMarket(player);
            } else if (slot == adminGlobalListSlot && globalClaimsEnabled() && player.hasPermission(GLOBALLIST_PERMISSION)) {
                manager.openGlobalClaimList(player);
            } else if (slot == adminMenuSlot) {
                manager.openAdminMenu(player);
            }
        } else {
            if (slot == ownedClaimsSlot) {
                manager.openOwnedClaims(player);
            } else if (slot == claimMarketSlot && plugin.getConfigManager().isMarketEnabled()
                    && player.hasPermission(MarketManager.PERM_MARKET)) {
                manager.openClaimMarket(player);
            } else if (slot == globalListSlot && globalClaimsEnabled() && player.hasPermission(GLOBALLIST_PERMISSION)) {
                manager.openGlobalClaimList(player);
            }
        }
    }
}
