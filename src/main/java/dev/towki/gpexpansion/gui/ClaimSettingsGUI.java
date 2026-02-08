package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.gp.GPFlagsBridge;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GUI for claim options (9-slot menu).
 */
public class ClaimSettingsGUI extends BaseGUI {
    
    private final Object claim;
    private final String claimId;
    private final GPBridge gp;
    
    private int viewChildrenSlot = 0;
    private int bannedPlayersSlot = 2;
    private int claimFlagsSlot = 4;
    private int globalSettingsSlot = 6;
    private int backSlot = 8;
    
    public ClaimSettingsGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "claim-settings");
        this.claim = claim;
        this.claimId = claimId;
        this.gp = new GPBridge();
        
        if (config != null) {
            viewChildrenSlot = config.getInt("slots.view-children", 0);
            bannedPlayersSlot = config.getInt("slots.banned-players", 2);
            claimFlagsSlot = config.getInt("slots.claim-flags", 4);
            globalSettingsSlot = config.getInt("slots.global-settings", 6);
            backSlot = config.getInt("slots.back", 8);
        }
    }
    
    @Override
    public Inventory createInventory() {
        String title = getString("title", "&6&lClaim Options - #{id}").replace("{id}", claimId);
        inventory = createBaseInventoryWithTitle(title, 9);
        
        fillEmpty(createFiller());
        
        inventory.setItem(viewChildrenSlot, createViewChildrenItem());
        inventory.setItem(bannedPlayersSlot, createBannedPlayersItem());
        
        if (canShowClaimFlags()) {
            inventory.setItem(claimFlagsSlot, createClaimFlagsItem());
        }
        
        inventory.setItem(globalSettingsSlot, createGlobalSettingsItem());
        inventory.setItem(backSlot, createBackItem());
        
        return inventory;
    }
    
    private ItemStack createViewChildrenItem() {
        List<Object> children = gp.getSubclaims(claim);
        int childCount = children.size();
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{count}", String.valueOf(childCount));
        
        if (config != null && config.contains("items.view-children")) {
            return createItemFromConfig("items.view-children", placeholders);
        }
        
        List<String> lore = new ArrayList<>();
        lore.add("&7View subdivisions of this claim");
        lore.add("");
        lore.add("&7Children: &6" + childCount);
        lore.add("");
        if (childCount == 0) {
            lore.add("&7No subdivisions found");
        } else {
            lore.add("&7Shift+Left click to navigate");
            lore.add("&7through nested subdivisions");
        }
        lore.add("");
        lore.add("&eClick to view children");
        return createItem(Material.CHEST, "&b&lView Children", lore);
    }
    
    private ItemStack createBannedPlayersItem() {
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        int banCount = dataStore.getBannedPlayers(claimId).size();
        boolean canBan = player.hasPermission("griefprevention.claim.ban");
        
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{count}", String.valueOf(banCount));
        placeholders.put("{ban_permission}", canBan ? getString("permissions.ban-allowed", "&a✓ You can manage bans")
            : getString("permissions.ban-denied", "&c✗ No permission to manage bans"));
        placeholders.put("{ban_permission_detail}", canBan ? "" : getString("permissions.ban-denied-detail", "&8Missing: griefprevention.claim.ban"));
        
        if (config != null && config.contains("items.banned-players")) {
            return createItemFromConfig("items.banned-players", placeholders);
        }
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Manage players banned from this claim");
        lore.add("");
        lore.add("&7Currently banned: &6" + banCount);
        lore.add("");
        if (canBan) {
            lore.add("&a✓ You can manage bans");
            lore.add("");
            lore.add("&eClick to manage banned players");
        } else {
            lore.add("&c✗ No permission to manage bans");
            lore.add("&8Missing: griefprevention.claim.ban");
        }
        return createItem(Material.BARRIER, "&c&lBanned Players", lore);
    }
    
    private boolean canShowClaimFlags() {
        if (!GPFlagsBridge.isAvailable()) return false;
        if (!player.hasPermission("gpflags.command.setclaimflag")) return false;
        
        boolean isOwner = gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin");
        
        if (isOwner) {
            return player.hasPermission("griefprevention.claim.gui.setclaimflag.own");
        } else {
            return player.hasPermission("griefprevention.claim.gui.setclaimflag.anywhere");
        }
    }
    
    private ItemStack createClaimFlagsItem() {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{flags_status}", getString("permissions.flags-available", "&a✓ GPFlags available"));
        
        if (config != null && config.contains("items.claim-flags")) {
            return createItemFromConfig("items.claim-flags", placeholders);
        }
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Manage GPFlags for this claim");
        lore.add("");
        lore.add("&7Toggle flags like PvP, mob spawns,");
        lore.add("&7enter/exit messages, and more.");
        lore.add("");
        lore.add("&a✓ GPFlags available");
        lore.add("");
        lore.add("&eClick to manage claim flags");
        return createItem(Material.COMPARATOR, "&d&lClaim Flags", lore);
    }
    
    private ItemStack createGlobalSettingsItem() {
        if (config != null && config.contains("items.global-settings")) {
            return createItemFromConfig("items.global-settings", new HashMap<>());
        }
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Manage global claim settings");
        lore.add("");
        lore.add("&7• Claim name & icon");
        lore.add("&7• Description & listing");
        lore.add("&7• Spawn point");
        lore.add("&7• Global claims list");
        lore.add("");
        lore.add("&eClick to manage global settings");
        return createItem(Material.ENDER_CHEST, "&6&lGlobal Settings", lore);
    }
    
    private ItemStack createBackItem() {
        if (config != null && config.contains("items.back")) {
            return createItemFromConfig("items.back", new HashMap<>());
        }
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to claims list"));
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == viewChildrenSlot) {
            manager.openChildrenClaims(player, claim, claimId);
        } else if (slot == bannedPlayersSlot) {
            if (player.hasPermission("griefprevention.claim.ban")) {
                manager.openBannedPlayers(player, claim, claimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == claimFlagsSlot) {
            if (canShowClaimFlags()) {
                manager.openClaimFlags(player, claim, claimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == globalSettingsSlot) {
            manager.openGlobalClaimSettings(player, claim, claimId);
        } else if (slot == backSlot) {
            manager.openOwnedClaims(player);
        }
    }
}
