package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.storage.ClaimDataStore;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Consolidated GUI for global claim settings.
 * Shows: Name, Icon, Description, Global Toggle, Spawn Point, Global Claims List
 */
public class GlobalClaimSettingsGUI extends BaseGUI {
    
    private final Object claim;
    private final String claimId;
    private final boolean fromSign;
    
    // Slot positions from config
    private int nameSlot = 0;
    private int iconSlot = 1;
    private int descriptionSlot = 2;
    private int globalToggleSlot = 3;
    private int spawnPointSlot = 4;
    private int globalListSlot = 5;
    private int backSlot = 8;
    
    public GlobalClaimSettingsGUI(GUIManager manager, Player player, Object claim, String claimId) {
        this(manager, player, claim, claimId, false);
    }
    
    public GlobalClaimSettingsGUI(GUIManager manager, Player player, Object claim, String claimId, boolean fromSign) {
        super(manager, player, "global-claim-settings");
        this.claim = claim;
        this.claimId = claimId;
        this.fromSign = fromSign;
        
        // Load slot positions from config
        if (config != null) {
            nameSlot = config.getInt("items.name.slot", 0);
            iconSlot = config.getInt("items.icon.slot", 1);
            descriptionSlot = config.getInt("items.description.slot", 2);
            globalToggleSlot = config.getInt("items.global-toggle.slot", 3);
            spawnPointSlot = config.getInt("items.spawn-point.slot", 4);
            globalListSlot = config.getInt("items.global-list.slot", 5);
            backSlot = config.getInt("items.back.slot", 8);
        }
    }
    
    @Override
    public Inventory createInventory() {
        String title = getString("title", "&6&lGlobal Settings - #{id}").replace("{id}", claimId);
        inventory = createBaseInventoryWithTitle(title, 9);
        
        // Name button
        inventory.setItem(nameSlot, createNameItem());
        
        // Icon button
        inventory.setItem(iconSlot, createIconItem());
        
        // Description button
        inventory.setItem(descriptionSlot, createDescriptionItem());
        
        // Global toggle button
        inventory.setItem(globalToggleSlot, createGlobalToggleItem());
        
        // Spawn point button
        inventory.setItem(spawnPointSlot, createSpawnPointItem());
        
        // Global claims list button
        inventory.setItem(globalListSlot, createGlobalClaimsListItem());
        
        // Back button
        inventory.setItem(backSlot, createBackItem());
        
        return inventory;
    }
    
    private ItemStack createNameItem() {
        String currentName = plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim #" + claimId);
        boolean hasPermission = player.hasPermission("griefprevention.claim.name");
        
        Material material = resolveMaterial("items.name.material", Material.NAME_TAG);
        String name = getString("items.name.name", "&b&lName");
        List<String> loreTemplate = getStringList("items.name.lore");
        
        List<String> lore;
        if (!loreTemplate.isEmpty()) {
            lore = new ArrayList<>();
            for (String line : loreTemplate) {
                line = line.replace("{name}", currentName)
                          .replace("{has_permission_rename}", hasPermission ? "&a✓ You can rename claims" : "&c✗ No permission to rename")
                          .replace("{permission_rename}", hasPermission ? "" : "&8Missing: griefprevention.claim.name");
                lore.add(line);
            }
        } else {
            // Fallback hardcoded lore
            lore = new ArrayList<>();
            lore.add("&7Rename this claim");
            lore.add("");
            lore.add("&7Current: &f" + currentName);
            lore.add("");
            if (hasPermission) {
                lore.add("&a✓ You can rename claims");
                lore.add("");
                lore.add("&eClick to rename");
            } else {
                lore.add("&c✗ No permission to rename");
                lore.add("&8Missing: griefprevention.claim.name");
            }
        }
        
        return createItem(material, name, lore);
    }
    
    private ItemStack createIconItem() {
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        Material icon = dataStore.getIcon(claimId).orElse(null);
        
        Material display = icon != null ? icon : resolveMaterial("items.icon.material", Material.ITEM_FRAME);
        String name = getString("items.icon.name", "&6&lIcon");
        List<String> loreTemplate = getStringList("items.icon.lore");
        
        List<String> lore;
        if (!loreTemplate.isEmpty()) {
            lore = new ArrayList<>();
            for (String line : loreTemplate) {
                line = line.replace("{icon}", icon != null ? icon.name() : "Default");
                lore.add(line);
            }
        } else {
            // Fallback hardcoded lore
            lore = new ArrayList<>();
            lore.add("&7Set a custom icon for this claim");
            lore.add("&7in menus and global list.");
            lore.add("");
            lore.add("&7Current: " + (icon != null ? "&f" + icon.name() : "&7Default"));
            lore.add("");
            lore.add("&eClick to select icon");
        }
        
        return createItem(display, name, lore);
    }
    
    private ItemStack createDescriptionItem() {
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        String desc = dataStore.getDescription(claimId).orElse(null);
        
        String name = getString("items.description.name", "&e&lDescription");
        List<String> loreTemplate = getStringList("items.description.lore");
        
        List<String> lore;
        if (!loreTemplate.isEmpty()) {
            lore = new ArrayList<>();
            for (String line : loreTemplate) {
                line = line.replace("{description}", desc != null ? desc : "No description set.");
                lore.add(line);
            }
        } else {
            // Fallback hardcoded lore
            lore = new ArrayList<>();
            lore.add("&7Set a short description (max 32 chars)");
            lore.add("&7shown in global claim list.");
            lore.add("");
            lore.add("&7Current: " + (desc != null ? "&f" + desc : "&7No description set."));
            lore.add("");
            lore.add("&eClick to set description");
        }
        
        return createItem(resolveMaterial("items.description.material", Material.PAPER), name, lore);
    }
    
    private ItemStack createGlobalToggleItem() {
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        boolean isPublic = dataStore.isPublicListed(claimId);
        
        Material material;
        if (config != null && config.contains("items.global-toggle.material")) {
            String configured = config.getString("items.global-toggle.material", "ENDER_PEARL");
            if ("ENDER_PEARL".equalsIgnoreCase(configured)) {
                material = isPublic ? Material.ENDER_EYE : Material.ENDER_PEARL;
            } else {
                material = resolveMaterial("items.global-toggle.material", Material.ENDER_PEARL);
            }
        } else {
            material = isPublic ? Material.ENDER_EYE : Material.ENDER_PEARL;
        }
        String name = getString("items.global-toggle.name", "&d&lPublic Listing");
        List<String> loreTemplate = getStringList("items.global-toggle.lore");
        
        List<String> lore;
        if (!loreTemplate.isEmpty()) {
            lore = new ArrayList<>();
            for (String line : loreTemplate) {
                line = line.replace("{listing_status}", isPublic ? "&aPublicly Listed" : "&7Not Listed")
                          .replace("{listing_action}", isPublic ? "unlist" : "list publicly");
                lore.add(line);
            }
        } else {
            // Fallback hardcoded lore
            lore = new ArrayList<>();
            lore.add("&7Toggle this claim's global listing");
            lore.add("&7so other players can see and visit it.");
            lore.add("");
            lore.add("&7Status: " + (isPublic ? "&aPublicly Listed" : "&7Not Listed"));
            lore.add("");
            lore.add("&eClick to " + (isPublic ? "unlist" : "list publicly"));
        }
        
        return createItem(material, name, lore);
    }
    
    private ItemStack createSpawnPointItem() {
        boolean hasSpawn = plugin.getClaimDataStore().getSpawn(claimId).isPresent();
        boolean hasPermission = player.hasPermission("griefprevention.claim.setspawn");
        
        String materialName = getString("items.spawn-point.material", hasSpawn ? "ENDER_PEARL" : "ENDER_EYE");
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            material = hasSpawn ? Material.ENDER_PEARL : Material.ENDER_EYE;
        }
        String name = getString("items.spawn-point.name", "&b&lSpawn Point");
        List<String> loreTemplate = getStringList("items.spawn-point.lore");
        
        List<String> lore;
        if (!loreTemplate.isEmpty()) {
            lore = new ArrayList<>();
            for (String line : loreTemplate) {
                line = line.replace("{spawn_status}", hasSpawn ? "&aSpawn point set" : "&eUsing claim center")
                          .replace("{has_permission_spawn}", hasPermission ? "&a✓ You can set spawn point" : "&c✗ No permission to set spawn")
                          .replace("{permission_spawn}", hasPermission ? "" : "&8Missing: griefprevention.claim.setspawn");
                lore.add(line);
            }
        } else {
            // Fallback hardcoded lore
            lore = new ArrayList<>();
            lore.add("&7Set the teleport spawn point");
            lore.add("&7for this global claim.");
            lore.add("");
            if (hasSpawn) {
                lore.add("&7Status: &aSpawn point set");
            } else {
                lore.add("&7Status: &eUsing claim center");
            }
            lore.add("");
            if (hasPermission) {
                lore.add("&a✓ You can set spawn point");
                lore.add("");
                lore.add("&eClick to set spawn at your location");
            } else {
                lore.add("&c✗ No permission to set spawn");
                lore.add("&8Missing: griefprevention.claim.setspawn");
            }
        }
        
        return createItem(material, name, lore);
    }
    
    private ItemStack createGlobalClaimsListItem() {
        String name = getString("items.global-list.name", "&a&lGlobal Claims");
        List<String> loreTemplate = getStringList("items.global-list.lore");
        
        List<String> lore;
        if (!loreTemplate.isEmpty()) {
            lore = new ArrayList<>();
            for (String line : loreTemplate) {
                lore.add(line);
            }
        } else {
            // Fallback hardcoded lore
            lore = new ArrayList<>();
            lore.add("&7Browse all publicly listed claims");
            lore.add("&7from all players on the server.");
            lore.add("");
            lore.add("&7Visit and explore other players'");
            lore.add("&7creative builds and creations!");
            lore.add("");
            lore.add("&eClick to browse global claims");
        }
        
        return createItem(resolveMaterial("items.global-list.material", Material.COMPASS), name, lore);
    }
    
    private ItemStack createBackItem() {
        String name = getString("items.back.name", "&c&lBack");
        List<String> lore = getStringList("items.back.lore");
        if (lore.isEmpty()) {
            lore = List.of("&7Return to claim options");
        }
        return createItem(resolveMaterial("items.back.material", Material.ARROW), name, lore);
    }

    private Material resolveMaterial(String path, Material fallback) {
        String materialName = getString(path, fallback.name());
        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == nameSlot) {
            if (player.hasPermission("griefprevention.claim.name")) {
                String currentName = plugin.getClaimDataStore().getCustomName(claimId).orElse("");
                String displayName = (currentName != null && !currentName.isEmpty() && !currentName.startsWith("Claim #"))
                    ? currentName : "Unnamed";
                player.closeInventory();
                SignInputGUI.openRename(plugin, player, displayName,
                    newName -> {
                        plugin.getClaimDataStore().setCustomName(claimId, newName);
                        plugin.getClaimDataStore().save();
                        plugin.getMessages().send(player, "gui.claim-renamed", "{id}", claimId, "{name}", newName);
                        manager.openGlobalClaimSettings(player, claim, claimId, fromSign);
                    },
                    () -> manager.openGlobalClaimSettings(player, claim, claimId, fromSign));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == iconSlot) {
            // Open icon selection GUI
            player.closeInventory();
            new IconSelectionGUI(plugin, player,
                icon -> {
                    plugin.getClaimDataStore().setIcon(claimId, icon);
                    plugin.getClaimDataStore().save();
                    plugin.getMessages().send(player, "gui.icon-set", "{id}", claimId);
                    manager.openGlobalClaimSettings(player, claim, claimId, fromSign);
                },
                () -> manager.openGlobalClaimSettings(player, claim, claimId, fromSign)).open();
        } else if (slot == descriptionSlot) {
            // Ask in chat for description input
            player.closeInventory();
            plugin.getDescriptionInputManager().begin(player, claimId, fromSign);
        } else if (slot == globalToggleSlot) {
            ClaimDataStore dataStore = plugin.getClaimDataStore();
            boolean isPublic = dataStore.isPublicListed(claimId);
            dataStore.setPublicListed(claimId, !isPublic);
            dataStore.save();
            plugin.getMessages().send(player, isPublic ? "gui.claim-unlisted" : "gui.claim-listed", "{id}", claimId);
            // Refresh the GUI
            inventory.setItem(globalToggleSlot, createGlobalToggleItem());
        } else if (slot == spawnPointSlot) {
            if (player.hasPermission("griefprevention.claim.setspawn")) {
                closeAndRunOnMainThread(() -> player.performCommand("claim setspawn " + claimId));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == globalListSlot) {
            manager.openGlobalClaimList(player);
        } else if (slot == backSlot) {
            if (fromSign) {
                // If from sign, go to global claims list
                manager.openGlobalClaimList(player);
            } else {
                // Normal back to claim options
                manager.openClaimOptions(player, claim, claimId);
            }
        }
    }
}
