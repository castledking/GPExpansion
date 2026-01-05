package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.RentalStore;
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
 * GUI for viewing all admin claims with filter options.
 * Similar to OwnedClaimsGUI but shows admin claims instead.
 */
public class AdminClaimsGUI extends BaseGUI {
    
    private final GPBridge gp;
    
    // Filter types
    public enum FilterType {
        ALL("All Claims", Material.CHEST),
        RENTED("Rented Claims", Material.CLOCK),
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
    
    public AdminClaimsGUI(GUIManager manager, Player player) {
        super(manager, player, "admin-claims");
        this.gp = new GPBridge();
        loadClaims();
    }
    
    private void loadClaims() {
        filteredClaims.clear();
        
        // Get all admin claims
        List<Object> allAdminClaims = gp.getAdminClaims();
        
        for (Object claim : allAdminClaims) {
            // Skip subdivisions - only show main claims
            if (gp.isSubdivision(claim)) continue;
            
            Optional<String> claimIdOpt = gp.getClaimId(claim);
            if (!claimIdOpt.isPresent()) continue;
            
            String claimId = claimIdOpt.get();
            ClaimInfo info = new ClaimInfo(claim, claimId);
            
            // Determine claim type
            info.isRented = isClaimRented(claimId);
            info.isMailbox = isClaimMailbox(claimId);
            
            // Get claim details
            info.name = plugin.getNameStore().get(claimId).orElse("Admin Claim #" + claimId);
            info.childCount = gp.getSubclaims(claim).size();
            info.area = getClaimArea(claim);
            info.location = getClaimLocation(claim);
            
            // Apply filter
            if (matchesFilter(info)) {
                filteredClaims.add(info);
            }
        }
    }
    
    private boolean matchesFilter(ClaimInfo info) {
        switch (currentFilter) {
            case ALL: return true;
            case RENTED: return info.isRented;
            case MAILBOXES: return info.isMailbox;
            case REGULAR: return !info.isRented && !info.isMailbox;
            default: return true;
        }
    }
    
    private boolean isClaimRented(String claimId) {
        RentalStore rentalStore = plugin.getRentalStore();
        return rentalStore.isRented(claimId);
    }
    
    private boolean isClaimMailbox(String claimId) {
        return plugin.getMailboxStore().isMailbox(claimId);
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
        inventory = createBaseInventory(getString("title", "&c&lAdmin Claims"), 54);
        
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
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to admin menu"));
    }
    
    private ItemStack createSearchItem() {
        return createItem(Material.COMPASS, "&b&lSearch Claims", List.of("&7Search claims by ID or name", "", "&eClick to search"));
    }
    
    private ItemStack createPrevPageItem() {
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + currentPage + "/" + ((filteredClaims.size() - 1) / CLAIM_SLOTS.length + 1)));
    }
    
    private ItemStack createNextPageItem() {
        return createItem(Material.ARROW, "&e&lNext Page »", List.of("&7Page " + (currentPage + 2) + "/" + ((filteredClaims.size() - 1) / CLAIM_SLOTS.length + 1)));
    }
    
    private ItemStack createClaimItem(ClaimInfo info) {
        // Try to get custom icon first
        Material material = plugin.getIconStore().get(info.claimId)
            .map(Material::matchMaterial)
            .filter(m -> m != null)
            .orElse(Material.COMMAND_BLOCK);
        
        if (info.isRented) material = Material.CLOCK;
        else if (info.isMailbox) material = Material.ENDER_CHEST;
        
        List<String> lore = new ArrayList<>();
        lore.add("&7ID: &f" + info.claimId);
        lore.add("&7Location: &f" + info.location);
        lore.add("&7Area: &f" + info.area + " blocks");
        lore.add("&7Children: &6" + info.childCount);
        
        if (info.isRented) {
            lore.add("");
            lore.add("&e⚡ Currently Rented");
        } else if (info.isMailbox) {
            lore.add("");
            lore.add("&d📬 Mailbox Claim");
        }
        
        lore.add("");
        lore.add("&a▸ Left-click to teleport");
        lore.add("&b▸ Right-click to rename");
        lore.add("&e▸ Shift+Left-click for options");
        
        return createItem(material, "&c" + info.name, lore);
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
            manager.openAdminMenu(player);
            return;
        }
        
        if (slot == SEARCH_SLOT) {
            // Open sign editor for search
            player.closeInventory();
            SignInputGUI.openSearch(plugin, player,
                query -> {
                    String searchLower = query.toLowerCase();
                    ClaimInfo match = null;
                    
                    // First try exact ID match
                    for (ClaimInfo info : filteredClaims) {
                        if (info.claimId.equals(query)) {
                            match = info;
                            break;
                        }
                    }
                    
                    // Then try name contains match
                    if (match == null) {
                        for (ClaimInfo info : filteredClaims) {
                            if (info.name.toLowerCase().contains(searchLower)) {
                                match = info;
                                break;
                            }
                        }
                    }
                    
                    if (match != null) {
                        manager.openClaimOptions(player, match.claim, match.claimId);
                    } else {
                        plugin.getMessages().send(player, "gui.search-no-results", "{query}", query);
                        manager.openAdminClaims(player);
                    }
                },
                () -> manager.openAdminClaims(player));
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
            closeAndRun(() -> player.performCommand("claim tp " + info.claimId));
        } else if (isRightClick(event) && !event.isShiftClick()) {
            // Rename claim via sign editor
            player.closeInventory();
            String displayName = (info.name != null && !info.name.isEmpty() && !info.name.startsWith("Admin Claim #"))
                ? info.name : "Unnamed";
            SignInputGUI.openRename(plugin, player, displayName,
                newName -> {
                    plugin.getNameStore().set(info.claimId, newName);
                    plugin.getNameStore().save();
                    plugin.getMessages().send(player, "gui.claim-renamed", "{id}", info.claimId, "{name}", newName);
                    manager.openAdminClaims(player);
                },
                () -> manager.openAdminClaims(player));
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
        int childCount;
        int area;
        String location;
        boolean isRented;
        boolean isMailbox;
        
        ClaimInfo(Object claim, String claimId) {
            this.claim = claim;
            this.claimId = claimId;
        }
    }
}
