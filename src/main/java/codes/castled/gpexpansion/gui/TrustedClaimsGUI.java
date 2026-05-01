package codes.castled.gpexpansion.gui;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.storage.ClaimDataStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * GUI for viewing claims the player is trusted in.
 * Includes both main claims and subdivisions.
 */
public class TrustedClaimsGUI extends BaseGUI {
    
    private final GPBridge gp;
    
    public enum FilterType {
        ALL("All Claims", Material.CHEST),
        RENTED("Rent Claims", Material.CLOCK),
        SOLD("Sale Claims", Material.EMERALD),
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
    
    private static final int FILTER_SLOT = 49;
    private static final int PREV_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int BACK_SLOT = 48;
    private static final int[] CLAIM_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    
    public TrustedClaimsGUI(GUIManager manager, Player player) {
        super(manager, player, "trusted-claims");
        this.gp = new GPBridge();
        loadClaims();
    }
    
    private void loadClaims() {
        filteredClaims.clear();
        
        // Get all claims where player has trust
        List<Object> trustedClaims = gp.getClaimsWhereTrusted(player.getUniqueId());
        
        for (Object claim : trustedClaims) {
            Optional<String> claimIdOpt = gp.getClaimId(claim);
            if (!claimIdOpt.isPresent()) continue;
            
            String claimId = claimIdOpt.get();
            
            // Skip claims owned by the player
            try {
                Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
                if (ownerId != null && ownerId.equals(player.getUniqueId())) continue;
            } catch (Exception e) {
                // Continue anyway
            }
            
            ClaimInfo info = new ClaimInfo(claim, claimId);
            
            // Determine claim type
            info.isRented = isClaimRented(claimId);
            info.isSold = false; // Would need sell sign tracking
            info.isMailbox = plugin.getClaimDataStore().isMailbox(claimId);
            info.isSubdivision = gp.isSubdivision(claim);
            
            // Get claim details
            info.name = plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim #" + claimId);
            info.ownerName = getOwnerName(claim);
            info.area = getClaimArea(claim);
            info.location = getClaimLocation(claim);
            
            // Check if player can renew (is renter)
            if (info.isRented) {
                ClaimDataStore.RentalData entry = plugin.getClaimDataStore().getRental(claimId).orElse(null);
                info.canRenew = entry != null && entry.renter.equals(player.getUniqueId());
            }
            
            if (matchesFilter(info)) {
                filteredClaims.add(info);
            }
        }
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
    
    private String getOwnerName(Object claim) {
        try {
            Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
            if (ownerId instanceof UUID) {
                return plugin.getServer().getOfflinePlayer((UUID) ownerId).getName();
            }
        } catch (Exception e) {
            // Ignore
        }
        return "Unknown";
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
        inventory = createBaseInventory(getString("title", "&a&lTrusted Claims"), 54);
        populateInventory();
        return inventory;
    }
    
    private void populateInventory() {
        inventory.clear();
        fillBorder(createFiller());
        
        inventory.setItem(FILTER_SLOT, createFilterItem());
        inventory.setItem(BACK_SLOT, createBackItem());
        
        if (currentPage > 0) {
            inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        }
        
        int maxPage = (filteredClaims.size() - 1) / CLAIM_SLOTS.length;
        if (currentPage < maxPage) {
            inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        }

        String currentTrustedClaimId = getCurrentTrustedClaimId();
        
        int startIndex = currentPage * CLAIM_SLOTS.length;
        for (int i = 0; i < CLAIM_SLOTS.length && startIndex + i < filteredClaims.size(); i++) {
            ClaimInfo info = filteredClaims.get(startIndex + i);
            inventory.setItem(CLAIM_SLOTS[i], createClaimItem(info, currentTrustedClaimId));
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
    
    private ItemStack createPrevPageItem() {
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + currentPage + "/" + ((filteredClaims.size() - 1) / CLAIM_SLOTS.length + 1)));
    }
    
    private ItemStack createNextPageItem() {
        return createItem(Material.ARROW, "&e&lNext Page »", List.of("&7Page " + (currentPage + 2) + "/" + ((filteredClaims.size() - 1) / CLAIM_SLOTS.length + 1)));
    }
    
    private String getCurrentTrustedClaimId() {
        Location location = player.getLocation();
        if (location.getWorld() == null) return null;

        String worldName = location.getWorld().getName();
        String currentClaimId = null;
        long smallestContainingClaim = Long.MAX_VALUE;

        for (ClaimInfo info : filteredClaims) {
            if (info.claim == null || info.claimId == null) continue;
            if (!worldName.equals(gp.getClaimWorld(info.claim).orElse(null))) continue;
            if (!containsLocation(info.claim, location)) continue;

            long sizeMetric = getClaimSizeMetric(info.claim);
            if (sizeMetric < smallestContainingClaim) {
                smallestContainingClaim = sizeMetric;
                currentClaimId = info.claimId;
            }
        }

        return currentClaimId;
    }

    private boolean containsLocation(Object claim, Location location) {
        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        if (corners == null || location.getWorld() == null) return false;

        String claimWorld = gp.getClaimWorld(claim).orElse(null);
        if (claimWorld == null || !claimWorld.equals(location.getWorld().getName())) {
            return false;
        }

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        if (x < corners.x1 || x > corners.x2 || z < corners.z1 || z > corners.z2) {
            return false;
        }

        if (gp.is3DClaim(claim)) {
            return y >= corners.y1 && y <= corners.y2;
        }

        return true;
    }

    private long getClaimSizeMetric(Object claim) {
        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        if (corners == null) return Long.MAX_VALUE;

        long width = (long) corners.x2 - corners.x1 + 1L;
        long length = (long) corners.z2 - corners.z1 + 1L;

        if (gp.is3DClaim(claim)) {
            long height = (long) corners.y2 - corners.y1 + 1L;
            return width * length * height;
        }

        return width * length;
    }

    private ItemStack createClaimItem(ClaimInfo info, String currentTrustedClaimId) {
        Material material = Material.GRASS_BLOCK;
        if (info.isRented) material = Material.CLOCK;
        else if (info.isSold) material = Material.EMERALD;
        else if (info.isMailbox) material = Material.ENDER_CHEST;
        
        List<String> lore = new ArrayList<>();
        lore.add("&7ID: &f" + info.claimId);
        lore.add("&7Owner: &f" + info.ownerName);
        lore.add("&7Location: &f" + info.location);
        lore.add("&7Area: &f" + info.area + " blocks");
        
        if (info.isSubdivision) {
            lore.add("&7Type: &eSubdivision");
        }
        
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
        
        if (player.hasPermission("griefprevention.claim.teleport")) {
            lore.add("&a▸ Left-click to teleport");
        }
        if (info.canRenew) {
            lore.add("&b▸ Right-click to renew rental");
        }

        if (info.claimId != null && info.claimId.equals(currentTrustedClaimId)) {
            lore.add("");
            lore.add("&b✦ You are here");
        }

        boolean glow = info.claimId != null && info.claimId.equals(currentTrustedClaimId);
        return createItem(material, "&a" + info.name, lore, glow);
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == FILTER_SLOT) {
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
            if (player.hasPermission("griefprevention.claim.teleport")) {
                closeAndRunOnMainThread("claimtp " + info.claimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (isRightClick(event) && !event.isShiftClick()) {
            if (info.canRenew) {
                // Renew rental - find the sign and simulate interaction
                closeAndRun(() -> {
                    plugin.getMessages().send(player, "gui.rental-renew-hint", "{id}", info.claimId);
                });
            }
        }
    }
    
    private static class ClaimInfo {
        final Object claim;
        final String claimId;
        String name;
        String ownerName;
        int area;
        String location;
        boolean isRented;
        boolean isSold;
        boolean isMailbox;
        boolean isSubdivision;
        boolean canRenew;
        
        ClaimInfo(Object claim, String claimId) {
            this.claim = claim;
            this.claimId = claimId;
        }
    }
}
