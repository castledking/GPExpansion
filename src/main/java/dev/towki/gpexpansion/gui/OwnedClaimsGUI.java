package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GUI for viewing owned claims with filter options.
 * Shows main claims (not subdivisions) that the player owns.
 */
public class OwnedClaimsGUI extends BaseGUI {
    
    private final GPBridge gp;
    
    // Filter types
    public enum FilterType {
        ALL("All Claims", Material.CHEST),
        RENTED("Rented Claims", Material.CLOCK),
        SOLD("Sold Claims", Material.EMERALD),
        MAILBOXES("Mailbox Claims", Material.ENDER_CHEST),
        REGULAR("Regular Claims", Material.GRASS_BLOCK);
        
        private final String displayName;
        private final Material icon;
        
        FilterType(String displayName, Material icon) {
            this.displayName = displayName;
            this.icon = icon;
        }
        
        public String getDisplayName() { return displayName; }
        public Material getIcon() { return icon; }
        
        public FilterType next() {
            FilterType[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }
    
    private FilterType currentFilter = FilterType.ALL;
    private int currentPage = 0;
    private List<ClaimInfo> filteredClaims = new ArrayList<>();
    private String searchQuery = null;
    
    // Slot positions
    private static final int FILTER_SLOT = 49; // Bottom center (hopper)
    private static final int PREV_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int BACK_SLOT = 48;
    private static final int SEARCH_SLOT = 47;
    private static final int[] CLAIM_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    
    public OwnedClaimsGUI(GUIManager manager, Player player) {
        this(manager, player, null);
    }

    public OwnedClaimsGUI(GUIManager manager, Player player, String searchQuery) {
        super(manager, player, "owned-claims");
        this.gp = new GPBridge();
        this.searchQuery = searchQuery;
        // Claims loaded via openAsync or synchronously
    }
    
    /**
     * Open GUI with async claim loading to prevent server hang.
     */
    public static void openAsync(GUIManager manager, Player player, String searchQuery) {
        openAsyncWithState(manager, player, searchQuery, null, 0);
    }
    
    /**
     * Open GUI with async claim loading and restore previous state (filter, page).
     */
    public static void openAsyncWithState(GUIManager manager, Player player, String searchQuery, String filterType, int page) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAsyncNow(manager.getPlugin(), () -> {
            OwnedClaimsGUI gui = new OwnedClaimsGUI(manager, player, searchQuery);
            
            // Restore filter state if provided
            if (filterType != null) {
                try {
                    gui.currentFilter = FilterType.valueOf(filterType);
                } catch (IllegalArgumentException ignored) {}
            }
            gui.currentPage = page;
            
            gui.loadClaims();
            dev.towki.gpexpansion.scheduler.SchedulerAdapter.runEntity(manager.getPlugin(), player, () -> {
                // Save state for /claim ! command
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.OWNED_CLAIMS, 
                    gui.searchQuery, gui.currentFilter.name(), gui.currentPage);
                manager.openGUI(player, gui);
            }, null);
        });
    }
    
    private void loadClaims() {
        filteredClaims.clear();
        
        // Get all claims owned by player
        List<Object> allClaims = gp.getClaimsFor(player);
        
        for (Object claim : allClaims) {
            // Skip subdivisions - only show main claims
            if (gp.isSubdivision(claim)) continue;
            
            Optional<String> claimIdOpt = gp.getClaimId(claim);
            if (!claimIdOpt.isPresent()) continue;
            
            String claimId = claimIdOpt.get();
            ClaimInfo info = new ClaimInfo(claim, claimId);
            
            // Determine claim type
            info.isRented = isClaimRented(claimId);
            info.isSold = isClaimForSale(claimId);
            info.isMailbox = isClaimMailbox(claimId);
            
            // Get claim details
            info.name = plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim #" + claimId);
            info.ownerName = player.getName();
            info.childCount = gp.getSubclaims(claim).size();
            info.area = getClaimArea(claim);
            info.location = getClaimLocation(claim);
            
            // Apply filter
            if (matchesFilter(info) && matchesSearch(info)) {
                filteredClaims.add(info);
            }
        }
    }

    private boolean matchesSearch(ClaimInfo info) {
        if (searchQuery == null || searchQuery.isEmpty()) return true;
        String query = searchQuery.toLowerCase();
        if (info.claimId != null && info.claimId.equalsIgnoreCase(searchQuery)) return true;
        if (info.name != null && info.name.toLowerCase().contains(query)) return true;
        return info.ownerName != null && info.ownerName.toLowerCase().contains(query);
    }
    
    private boolean matchesFilter(ClaimInfo info) {
        switch (currentFilter) {
            case ALL: return true;
            case RENTED: return info.isRented;
            case SOLD: return info.isSold;
            case MAILBOXES: return info.isMailbox;
            case REGULAR: return !info.isRented && !info.isSold && !info.isMailbox;
            default: return true;
        }
    }
    
    private boolean isClaimRented(String claimId) {
        return plugin.getClaimDataStore().isRented(claimId);
    }
    
    private boolean isClaimForSale(String claimId) {
        // Check if claim has a sell sign
        // This would need to be tracked - for now return false
        return false;
    }
    
    private boolean isClaimMailbox(String claimId) {
        // Check if claim is used as mailbox
        return plugin.getClaimDataStore().isMailbox(claimId);
    }
    
    private int getClaimArea(Object claim) {
        try {
            Object lesserCorner = claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
            Object greaterCorner = claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);
            
            int width = Math.abs(((Location) greaterCorner).getBlockX() - ((Location) lesserCorner).getBlockX()) + 1;
            int length = Math.abs(((Location) greaterCorner).getBlockZ() - ((Location) lesserCorner).getBlockZ()) + 1;
            
            return width * length;
        } catch (Exception e) {
            return 0;
        }
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
            ? getString("title-search", "&6&lMy Claims - \"{query}\"").replace("{query}", searchQuery)
            : getString("title", "&6&lMy Claims");
        inventory = createBaseInventoryWithTitle(title, 54);
        
        populateInventory();
        
        return inventory;
    }
    
    private void populateInventory() {
        inventory.clear();
        
        // Fill border
        fillBorder(createFiller());
        
        // Add filter button (hopper)
        inventory.setItem(FILTER_SLOT, createFilterItem());
        
        // Add navigation
        inventory.setItem(BACK_SLOT, createBackItem());
        inventory.setItem(SEARCH_SLOT, createSearchItem());
        
        if (currentPage > 0) {
            inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        }
        
        int maxPage = (filteredClaims.size() - 1) / CLAIM_SLOTS.length;
        if (currentPage < maxPage) {
            inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        }
        
        // Add claim items
        int startIndex = currentPage * CLAIM_SLOTS.length;
        for (int i = 0; i < CLAIM_SLOTS.length && startIndex + i < filteredClaims.size(); i++) {
            ClaimInfo info = filteredClaims.get(startIndex + i);
            inventory.setItem(CLAIM_SLOTS[i], createClaimItem(info));
        }
    }
    
    private ItemStack createFilterItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Current: &e" + currentFilter.getDisplayName());
        lore.add("");
        lore.add("&eClick to cycle filter");
        
        return createItem(Material.HOPPER, "&6&lFilter: " + currentFilter.getDisplayName(), lore);
    }
    
    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to main menu"));
    }
    
    private ItemStack createSearchItem() {
        List<String> lore = new ArrayList<>();
        if (searchQuery != null && !searchQuery.isEmpty()) {
            lore.add("&7Current search: &e" + searchQuery);
            lore.add("");
        }
        lore.add("&7Search by owner, ID, or name");
        lore.add("");
        lore.add("&eClick to search");
        return createItem(Material.COMPASS, "&b&lSearch Claims", lore);
    }
    
    private ItemStack createPrevPageItem() {
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + currentPage + "/" + ((filteredClaims.size() - 1) / CLAIM_SLOTS.length + 1)));
    }
    
    private ItemStack createNextPageItem() {
        return createItem(Material.ARROW, "&e&lNext Page »", List.of("&7Page " + (currentPage + 2) + "/" + ((filteredClaims.size() - 1) / CLAIM_SLOTS.length + 1)));
    }
    
    private ItemStack createClaimItem(ClaimInfo info) {
        Material material = Material.GRASS_BLOCK;
        if (info.isRented) material = Material.CLOCK;
        else if (info.isSold) material = Material.EMERALD;
        else if (info.isMailbox) material = Material.ENDER_CHEST;
        
        List<String> lore = new ArrayList<>();
        lore.add("&7ID: &f" + info.claimId);
        lore.add("&7Location: &f" + info.location);
        lore.add("&7Area: &f" + info.area + " blocks");
        lore.add("&7Children: &6" + info.childCount);
        
        if (info.isRented) {
            lore.add("");
            lore.add("&e⚡ Currently Rented");
        } else if (info.isSold) {
            lore.add("");
            lore.add("&a💰 For Sale");
        } else if (info.isMailbox) {
            lore.add("");
            lore.add("&d📬 Mailbox Claim");
        }
        
        lore.add("");
        
        // Dynamic lore based on permissions
        if (player.hasPermission("griefprevention.claim.teleport")) {
            lore.add("&a▸ Left-click to teleport");
        }
        if (player.hasPermission("griefprevention.claim.name")) {
            lore.add("&b▸ Right-click to rename");
        }
        lore.add("&e▸ Shift+Left-click for options");
        
        return createItem(material, "&6" + info.name, lore);
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == FILTER_SLOT) {
            // Cycle filter
            currentFilter = currentFilter.next();
            currentPage = 0;
            loadClaims();
            populateInventory();
            return;
        }
        
        if (slot == BACK_SLOT) {
            manager.openMainMenu(player);
            return;
        }
        
        if (slot == SEARCH_SLOT) {
            // Open sign editor for search
            player.closeInventory();
            SignInputGUI.openSearch(plugin, player,
                query -> {
                    OwnedClaimsGUI.openAsync(manager, player, query);
                },
                () -> OwnedClaimsGUI.openAsync(manager, player, searchQuery));
            return;
        }
        
        if (slot == PREV_PAGE_SLOT && currentPage > 0) {
            currentPage--;
            populateInventory();
            return;
        }
        
        if (slot == NEXT_PAGE_SLOT) {
            int maxPage = (filteredClaims.size() - 1) / CLAIM_SLOTS.length;
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
            if (claimIndex < filteredClaims.size()) {
                ClaimInfo info = filteredClaims.get(claimIndex);
                handleClaimClick(event, info);
            }
        }
    }
    
    private void handleClaimClick(InventoryClickEvent event, ClaimInfo info) {
        if (isLeftClick(event) && !event.isShiftClick()) {
            // Teleport to claim
            if (player.hasPermission("griefprevention.claim.teleport")) {
                closeAndRunOnMainThread("claimtp " + info.claimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (isRightClick(event) && !event.isShiftClick()) {
            // Rename claim via sign editor
            if (player.hasPermission("griefprevention.claim.name")) {
                player.closeInventory();
                String displayName = (info.name != null && !info.name.isEmpty() && !info.name.startsWith("Claim #"))
                    ? info.name : "Unnamed";
                SignInputGUI.openRename(plugin, player, displayName,
                    newName -> {
                        plugin.getClaimDataStore().setCustomName(info.claimId, newName);
                        plugin.getClaimDataStore().save();
                        plugin.getMessages().send(player, "gui.claim-renamed", "{id}", info.claimId, "{name}", newName);
                        manager.openOwnedClaims(player);
                    },
                    () -> manager.openOwnedClaims(player));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (isShiftLeftClick(event)) {
            // Open options menu
            manager.openClaimOptions(player, info.claim, info.claimId);
        }
    }
    
    /**
     * Helper class to store claim information.
     */
    private static class ClaimInfo {
        final Object claim;
        final String claimId;
        String name;
        String ownerName;
        int childCount;
        int area;
        String location;
        boolean isRented;
        boolean isSold;
        boolean isMailbox;
        
        ClaimInfo(Object claim, String claimId) {
            this.claim = claim;
            this.claimId = claimId;
        }
    }
}
