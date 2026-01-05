package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * GUI showing all publicly listed claims from all players.
 */
public class GlobalClaimListGUI extends BaseGUI {
    
    private final GPBridge gp;
    private int currentPage = 0;
    private List<ClaimInfo> publicClaims = new ArrayList<>();
    private String searchQuery = null;
    
    private static final int SEARCH_SLOT = 47;
    private static final int PREV_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int BACK_SLOT = 49;
    private static final int[] CLAIM_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    
    public GlobalClaimListGUI(GUIManager manager, Player player) {
        this(manager, player, null);
    }
    
    public GlobalClaimListGUI(GUIManager manager, Player player, String searchQuery) {
        super(manager, player, "global-claim-list");
        this.gp = new GPBridge();
        this.searchQuery = searchQuery;
        loadPublicClaims();
    }
    
    private void loadPublicClaims() {
        publicClaims.clear();
        
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        Set<String> publicClaimIds = dataStore.getPublicListedClaims();
        
        for (String claimId : publicClaimIds) {
            Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (!claimOpt.isPresent()) continue;
            
            Object claim = claimOpt.get();
            ClaimInfo info = new ClaimInfo(claim, claimId);
            
            // Get claim data
            ClaimDataStore.ClaimData data = dataStore.get(claimId);
            info.icon = data.icon;
            info.description = data.description;
            
            // Get claim name
            info.name = plugin.getNameStore().get(claimId).orElse("Claim #" + claimId);
            
            // Get owner name
            info.ownerName = getOwnerName(claim);
            info.ownerUUID = getOwnerUUID(claim);
            
            // Get location
            info.location = getClaimLocation(claim);
            
            // Apply search filter if present
            if (searchQuery != null && !searchQuery.isEmpty()) {
                String query = searchQuery.toLowerCase();
                if (!info.name.toLowerCase().contains(query) && 
                    !info.ownerName.toLowerCase().contains(query) &&
                    (info.description == null || !info.description.toLowerCase().contains(query))) {
                    continue;
                }
            }
            
            publicClaims.add(info);
        }
    }
    
    private String getOwnerName(Object claim) {
        try {
            Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
            if (ownerId instanceof UUID) {
                String name = plugin.getServer().getOfflinePlayer((UUID) ownerId).getName();
                return name != null ? name : "Unknown";
            }
        } catch (Exception e) {
            // Ignore
        }
        return "Unknown";
    }
    
    private UUID getOwnerUUID(Object claim) {
        try {
            Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
            if (ownerId instanceof UUID) {
                return (UUID) ownerId;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    private String getClaimLocation(Object claim) {
        try {
            Object lesserCorner = claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
            Location loc = (Location) lesserCorner;
            return loc.getWorld().getName() + " @ " + loc.getBlockX() + ", " + loc.getBlockZ();
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    @Override
    public Inventory createInventory() {
        String title = searchQuery != null && !searchQuery.isEmpty() 
            ? getString("title-search", "&e&lGlobal Claims - \"{query}\"").replace("{query}", searchQuery)
            : getString("title", "&e&lGlobal Claim List");
        inventory = createBaseInventory(title, 54);
        populateInventory();
        return inventory;
    }
    
    private void populateInventory() {
        inventory.clear();
        fillBorder(createFiller());
        
        // Search button
        inventory.setItem(SEARCH_SLOT, createSearchItem());
        
        // Back button
        inventory.setItem(BACK_SLOT, createBackItem());
        
        // Navigation
        if (currentPage > 0) {
            inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        }
        
        int maxPage = Math.max(0, (publicClaims.size() - 1) / CLAIM_SLOTS.length);
        if (currentPage < maxPage) {
            inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        }
        
        // Claim items
        int startIndex = currentPage * CLAIM_SLOTS.length;
        for (int i = 0; i < CLAIM_SLOTS.length && startIndex + i < publicClaims.size(); i++) {
            ClaimInfo info = publicClaims.get(startIndex + i);
            inventory.setItem(CLAIM_SLOTS[i], createClaimItem(info));
        }
    }
    
    private ItemStack createSearchItem() {
        List<String> lore = new ArrayList<>();
        if (searchQuery != null && !searchQuery.isEmpty()) {
            lore.add("&7Current search: &e" + searchQuery);
            lore.add("");
        }
        lore.add("&eClick to search claims");
        return createItem(Material.COMPASS, "&b&lSearch Claims", lore);
    }
    
    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to main menu"));
    }
    
    private ItemStack createPrevPageItem() {
        int maxPage = Math.max(1, (publicClaims.size() - 1) / CLAIM_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + currentPage + "/" + maxPage));
    }
    
    private ItemStack createNextPageItem() {
        int maxPage = Math.max(1, (publicClaims.size() - 1) / CLAIM_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&lNext Page »", List.of("&7Page " + (currentPage + 2) + "/" + maxPage));
    }
    
    private ItemStack createClaimItem(ClaimInfo info) {
        Material material = info.icon != null ? info.icon : Material.GRASS_BLOCK;
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Owner: &f" + info.ownerName);
        lore.add("&7Name: &f" + info.name);
        lore.add("&7Description: &f" + (info.description != null ? info.description : "No description set."));
        lore.add("&7Location: &f" + info.location);
        lore.add("");
        
        if (player.hasPermission("griefprevention.claim.teleport")) {
            lore.add("&a▸ Click to teleport");
        }
        
        return createItem(material, "&6" + info.name, lore);
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == SEARCH_SLOT) {
            // Open sign editor for search
            player.closeInventory();
            SignInputGUI.openSearch(plugin, player, 
                query -> {
                    // Search for matching claim by ID or name
                    String searchLower = query.toLowerCase();
                    ClaimInfo match = null;
                    
                    // First try exact ID match
                    for (ClaimInfo info : publicClaims) {
                        if (info.claimId.equals(query)) {
                            match = info;
                            break;
                        }
                    }
                    
                    // Then try name/owner/description contains match
                    if (match == null) {
                        for (ClaimInfo info : publicClaims) {
                            if (info.name.toLowerCase().contains(searchLower) ||
                                info.ownerName.toLowerCase().contains(searchLower) ||
                                (info.description != null && info.description.toLowerCase().contains(searchLower))) {
                                match = info;
                                break;
                            }
                        }
                    }
                    
                    if (match != null) {
                        // Teleport to matched claim if player has permission
                        if (player.hasPermission("griefprevention.claim.teleport")) {
                            player.performCommand("claim tp " + match.claimId);
                        } else {
                            plugin.getMessages().send(player, "gui.search-found", "{name}", match.name, "{id}", match.claimId);
                            manager.openGUI(player, new GlobalClaimListGUI(manager, player, query));
                        }
                    } else {
                        plugin.getMessages().send(player, "gui.search-no-results", "{query}", query);
                        manager.openGUI(player, new GlobalClaimListGUI(manager, player));
                    }
                },
                () -> manager.openGUI(player, new GlobalClaimListGUI(manager, player)));
            return;
        }
        
        if (slot == BACK_SLOT) {
            manager.openMainMenu(player);
            return;
        }
        
        if (slot == PREV_PAGE_SLOT && currentPage > 0) {
            currentPage--;
            populateInventory();
            return;
        }
        
        if (slot == NEXT_PAGE_SLOT) {
            int maxPage = Math.max(0, (publicClaims.size() - 1) / CLAIM_SLOTS.length);
            if (currentPage < maxPage) {
                currentPage++;
                populateInventory();
            }
            return;
        }
        
        // Check if clicked on a claim
        int slotIndex = -1;
        for (int i = 0; i < CLAIM_SLOTS.length; i++) {
            if (CLAIM_SLOTS[i] == slot) {
                slotIndex = i;
                break;
            }
        }
        
        if (slotIndex >= 0) {
            int claimIndex = currentPage * CLAIM_SLOTS.length + slotIndex;
            if (claimIndex < publicClaims.size()) {
                ClaimInfo info = publicClaims.get(claimIndex);
                if (player.hasPermission("griefprevention.claim.teleport")) {
                    closeAndRun(() -> player.performCommand("claim tp " + info.claimId));
                } else {
                    plugin.getMessages().send(player, "general.no-permission");
                }
            }
        }
    }
    
    private static class ClaimInfo {
        final Object claim;
        final String claimId;
        String name;
        String ownerName;
        UUID ownerUUID;
        String description;
        Material icon;
        String location;
        
        ClaimInfo(Object claim, String claimId) {
            this.claim = claim;
            this.claimId = claimId;
        }
    }
}
