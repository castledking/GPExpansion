package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.gp.GPFlagsBridge;
import dev.towki.gpexpansion.storage.BanStore;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for editing claim settings like banned players, spawn point, public listing, icon, and description.
 */
public class ClaimSettingsGUI extends BaseGUI {
    
    private final Object claim;
    private final String claimId;
    
    // Row 1: Ban, Spawn, Flags
    private static final int BANNED_PLAYERS_SLOT = 10;
    private static final int SPAWN_POINT_SLOT = 12;
    private static final int CLAIM_FLAGS_SLOT = 14;
    // Row 2: Public, Icon, Description, Rename
    private static final int PUBLIC_LISTING_SLOT = 19;
    private static final int ICON_SLOT = 21;
    private static final int DESCRIPTION_SLOT = 23;
    private static final int RENAME_SLOT = 25;
    // Row 3: Back
    private static final int BACK_SLOT = 40;
    
    public ClaimSettingsGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "claim-settings");
        this.claim = claim;
        this.claimId = claimId;
    }
    
    @Override
    public Inventory createInventory() {
        String title = getString("title", "&e&lClaim Settings - #{id}").replace("{id}", claimId);
        // Force 45 slots - don't use config size as this GUI needs 5 rows
        inventory = org.bukkit.Bukkit.createInventory(null, 45, colorize(title));
        
        fillBorder(createFiller());
        
        inventory.setItem(BANNED_PLAYERS_SLOT, createBannedPlayersItem());
        inventory.setItem(SPAWN_POINT_SLOT, createSpawnPointItem());
        
        // Only show claim flags if GPFlags is available and player has permission
        if (canShowClaimFlags()) {
            inventory.setItem(CLAIM_FLAGS_SLOT, createClaimFlagsItem());
        }
        
        inventory.setItem(PUBLIC_LISTING_SLOT, createPublicListingItem());
        inventory.setItem(ICON_SLOT, createIconItem());
        inventory.setItem(DESCRIPTION_SLOT, createDescriptionItem());
        inventory.setItem(RENAME_SLOT, createRenameItem());
        inventory.setItem(BACK_SLOT, createBackItem());
        
        return inventory;
    }
    
    private ItemStack createBannedPlayersItem() {
        BanStore banStore = plugin.getBanStore();
        BanStore.BanEntry banEntry = banStore.get(claimId);
        int banCount = banEntry != null ? banEntry.players.size() : 0;
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Manage players banned from this claim");
        lore.add("");
        lore.add("&7Currently banned: &6" + banCount);
        lore.add("");
        
        if (player.hasPermission("griefprevention.claim.ban")) {
            lore.add("&a✓ You can manage bans");
            lore.add("");
            lore.add("&eClick to manage banned players");
        } else {
            lore.add("&c✗ No permission to manage bans");
            lore.add("&8Missing: griefprevention.claim.ban");
        }
        
        return createItem(Material.BARRIER, "&c&lBanned Players", lore);
    }
    
    private ItemStack createSpawnPointItem() {
        boolean hasSpawn = plugin.getSpawnStore().get(claimId).isPresent();
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Set the teleport spawn point");
        lore.add("");
        
        if (hasSpawn) {
            lore.add("&7Status: &aSpawn point set");
        } else {
            lore.add("&7Status: &eUsing claim center");
        }
        lore.add("");
        
        if (player.hasPermission("griefprevention.claim.setspawn")) {
            lore.add("&a✓ You can set spawn point");
            lore.add("");
            lore.add("&eClick to set spawn at your location");
        } else {
            lore.add("&c✗ No permission to set spawn");
            lore.add("&8Missing: griefprevention.claim.setspawn");
        }
        
        Material material = hasSpawn ? Material.ENDER_PEARL : Material.ENDER_EYE;
        return createItem(material, "&b&lSpawn Point", lore);
    }
    
    private ItemStack createPublicListingItem() {
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        boolean isPublic = dataStore.isPublicListed(claimId);
        
        List<String> lore = new ArrayList<>();
        lore.add("&7List this claim in the Global Claim List");
        lore.add("&7so other players can see and visit it.");
        lore.add("");
        lore.add("&7Status: " + (isPublic ? "&aPublicly Listed" : "&7Not Listed"));
        lore.add("");
        lore.add("&eClick to " + (isPublic ? "unlist" : "list publicly"));
        
        Material material = isPublic ? Material.ENDER_EYE : Material.ENDER_PEARL;
        return createItem(material, "&d&lPublic Listing", lore);
    }
    
    private ItemStack createIconItem() {
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        Material icon = dataStore.getIcon(claimId).orElse(null);
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Set a custom icon for this claim");
        lore.add("&7in menus and the global list.");
        lore.add("");
        lore.add("&7Current: " + (icon != null ? "&f" + icon.name() : "&7Default"));
        lore.add("");
        lore.add("&eClick to select icon");
        
        Material display = icon != null ? icon : Material.ITEM_FRAME;
        return createItem(display, "&6&lClaim Icon", lore);
    }
    
    private ItemStack createDescriptionItem() {
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        String desc = dataStore.getDescription(claimId).orElse(null);
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Set a short description (max 32 chars)");
        lore.add("&7shown in the global claim list.");
        lore.add("");
        lore.add("&7Current: " + (desc != null ? "&f" + desc : "&7No description set."));
        lore.add("");
        lore.add("&eClick to set description");
        
        return createItem(Material.PAPER, "&e&lDescription", lore);
    }
    
    private ItemStack createRenameItem() {
        String currentName = plugin.getNameStore().get(claimId).orElse("Claim #" + claimId);
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Rename this claim.");
        lore.add("");
        lore.add("&7Current: &f" + currentName);
        lore.add("");
        
        if (player.hasPermission("griefprevention.claim.name")) {
            lore.add("&a✓ You can rename claims");
            lore.add("");
            lore.add("&eClick to rename");
        } else {
            lore.add("&c✗ No permission to rename");
            lore.add("&8Missing: griefprevention.claim.name");
        }
        
        return createItem(Material.NAME_TAG, "&b&lRename Claim", lore);
    }
    
    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to claim options"));
    }
    
    private boolean canShowClaimFlags() {
        if (!GPFlagsBridge.isAvailable()) return false;
        if (!player.hasPermission("gpflags.command.setclaimflag")) return false;
        
        GPBridge gp = new GPBridge();
        boolean isOwner = gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin");
        
        if (isOwner) {
            return player.hasPermission("griefprevention.claim.gui.setclaimflag.own");
        } else {
            return player.hasPermission("griefprevention.claim.gui.setclaimflag.anywhere");
        }
    }
    
    private ItemStack createClaimFlagsItem() {
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
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == BANNED_PLAYERS_SLOT) {
            if (player.hasPermission("griefprevention.claim.ban")) {
                // Open banned players GUI
                manager.openBannedPlayers(player, claim, claimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == CLAIM_FLAGS_SLOT) {
            if (canShowClaimFlags()) {
                manager.openClaimFlags(player, claim, claimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == SPAWN_POINT_SLOT) {
            if (player.hasPermission("griefprevention.claim.setspawn")) {
                closeAndRun(() -> player.performCommand("setclaimspawn"));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == PUBLIC_LISTING_SLOT) {
            ClaimDataStore dataStore = plugin.getClaimDataStore();
            boolean isPublic = dataStore.isPublicListed(claimId);
            dataStore.setPublicListed(claimId, !isPublic);
            dataStore.save();
            plugin.getMessages().send(player, isPublic ? "gui.claim-unlisted" : "gui.claim-listed", "{id}", claimId);
            // Refresh the GUI
            inventory.setItem(PUBLIC_LISTING_SLOT, createPublicListingItem());
        } else if (slot == ICON_SLOT) {
            // Open icon selection GUI
            player.closeInventory();
            manager.openGUI(player, new IconSelectionGUI(manager, player, claimId,
                icon -> {
                    plugin.getClaimDataStore().setIcon(claimId, icon);
                    plugin.getClaimDataStore().save();
                    plugin.getMessages().send(player, "gui.icon-set", "{id}", claimId);
                    manager.openClaimSettings(player, claim, claimId);
                },
                () -> manager.openClaimSettings(player, claim, claimId)));
        } else if (slot == DESCRIPTION_SLOT) {
            // Open sign for description input
            String currentDesc = plugin.getClaimDataStore().getDescription(claimId).orElse("");
            player.closeInventory();
            SignInputGUI.openDescription(plugin, player, currentDesc,
                desc -> {
                    if (desc.length() > 32) desc = desc.substring(0, 32);
                    plugin.getClaimDataStore().setDescription(claimId, desc);
                    plugin.getClaimDataStore().save();
                    plugin.getMessages().send(player, "gui.description-set", "{id}", claimId);
                    manager.openClaimSettings(player, claim, claimId);
                },
                () -> manager.openClaimSettings(player, claim, claimId));
        } else if (slot == RENAME_SLOT) {
            if (player.hasPermission("griefprevention.claim.name")) {
                String currentName = plugin.getNameStore().get(claimId).orElse("");
                String displayName = (currentName != null && !currentName.isEmpty() && !currentName.startsWith("Claim #"))
                    ? currentName : "Unnamed";
                player.closeInventory();
                SignInputGUI.openRename(plugin, player, displayName,
                    newName -> {
                        plugin.getNameStore().set(claimId, newName);
                        plugin.getNameStore().save();
                        plugin.getMessages().send(player, "gui.claim-renamed", "{id}", claimId, "{name}", newName);
                        manager.openClaimSettings(player, claim, claimId);
                    },
                    () -> manager.openClaimSettings(player, claim, claimId));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == BACK_SLOT) {
            manager.openClaimOptions(player, claim, claimId);
        }
    }
}
