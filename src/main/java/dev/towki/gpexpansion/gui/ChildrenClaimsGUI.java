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
 * GUI for viewing children (subdivisions) of a claim.
 * Supports recursive navigation for nested subdivisions (GP 3D fork).
 */
public class ChildrenClaimsGUI extends BaseGUI {
    
    private final GPBridge gp;
    private final Object parentClaim;
    private final String parentClaimId;
    
    public enum FilterType {
        ALL("All Subdivisions", Material.CHEST),
        SUBDIVISION_2D("2D Subdivisions", Material.GRASS_BLOCK),
        SUBDIVISION_3D("3D Subdivisions", Material.STONE);
        
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
    private List<ChildInfo> filteredChildren = new ArrayList<>();
    
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
    
    public ChildrenClaimsGUI(GUIManager manager, Player player, Object parentClaim, String parentClaimId) {
        super(manager, player, "children-claims");
        this.gp = new GPBridge();
        this.parentClaim = parentClaim;
        this.parentClaimId = parentClaimId;
        loadChildren();
    }
    
    private void loadChildren() {
        filteredChildren.clear();
        
        List<Object> children = gp.getSubclaims(parentClaim);
        
        for (Object child : children) {
            Optional<String> claimIdOpt = gp.getClaimId(child);
            if (!claimIdOpt.isPresent()) continue;
            
            String claimId = claimIdOpt.get();
            ChildInfo info = new ChildInfo(child, claimId);
            
            // Detect if 3D subdivision
            info.is3D = is3DSubdivision(child);
            
            // Get claim details
            info.name = plugin.getNameStore().get(claimId).orElse("Subdivision #" + claimId);
            info.area = getClaimArea(child);
            info.location = getClaimLocation(child);
            info.hasChildren = !gp.getSubclaims(child).isEmpty();
            info.childCount = gp.getSubclaims(child).size();
            
            if (matchesFilter(info)) {
                filteredChildren.add(info);
            }
        }
    }
    
    private boolean matchesFilter(ChildInfo info) {
        switch (currentFilter) {
            case ALL: return true;
            case SUBDIVISION_2D: return !info.is3D;
            case SUBDIVISION_3D: return info.is3D;
            default: return true;
        }
    }
    
    private boolean is3DSubdivision(Object claim) {
        try {
            // Check if claim has height differences (3D)
            Object lesserCorner = claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
            Object greaterCorner = claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);
            
            int minY = ((Location) lesserCorner).getBlockY();
            int maxY = ((Location) greaterCorner).getBlockY();
            
            // If Y range is not full world height, it's a 3D claim
            return (maxY - minY) < 300; // Less than typical world height
        } catch (Exception e) {
            return false;
        }
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
        String title = getString("title", "&e&lSubdivisions - #{id}").replace("{id}", parentClaimId);
        inventory = createBaseInventory(title, 54);
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
        
        int maxPage = Math.max(0, (filteredChildren.size() - 1) / CLAIM_SLOTS.length);
        if (currentPage < maxPage) {
            inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        }
        
        int startIndex = currentPage * CLAIM_SLOTS.length;
        for (int i = 0; i < CLAIM_SLOTS.length && startIndex + i < filteredChildren.size(); i++) {
            ChildInfo info = filteredChildren.get(startIndex + i);
            inventory.setItem(CLAIM_SLOTS[i], createChildItem(info));
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
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to claim options"));
    }
    
    private ItemStack createPrevPageItem() {
        int maxPage = Math.max(1, (filteredChildren.size() - 1) / CLAIM_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + currentPage + "/" + maxPage));
    }
    
    private ItemStack createNextPageItem() {
        int maxPage = Math.max(1, (filteredChildren.size() - 1) / CLAIM_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&lNext Page »", List.of("&7Page " + (currentPage + 2) + "/" + maxPage));
    }
    
    private ItemStack createChildItem(ChildInfo info) {
        Material material = info.is3D ? Material.STONE : Material.GRASS_BLOCK;
        
        List<String> lore = new ArrayList<>();
        lore.add("&7ID: &f" + info.claimId);
        lore.add("&7Type: &f" + (info.is3D ? "3D Subdivision" : "2D Subdivision"));
        lore.add("&7Location: &f" + info.location);
        lore.add("&7Area: &f" + info.area + " blocks");
        
        if (info.hasChildren) {
            lore.add("&7Inner Subdivisions: &6" + info.childCount);
        }
        
        lore.add("");
        
        if (player.hasPermission("griefprevention.claim.teleport")) {
            lore.add("&a▸ Left-click to teleport");
        }
        if (player.hasPermission("griefprevention.claim.name")) {
            lore.add("&b▸ Right-click to rename");
        }
        if (info.hasChildren) {
            lore.add("&e▸ Shift+Left-click to view inner subdivisions");
        } else {
            lore.add("&e▸ Shift+Left-click for options");
        }
        
        return createItem(material, "&e" + info.name, lore);
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == FILTER_SLOT) {
            currentFilter = currentFilter.next();
            currentPage = 0;
            loadChildren();
            populateInventory();
            return;
        }
        
        if (slot == BACK_SLOT) {
            manager.openClaimOptions(player, parentClaim, parentClaimId);
            return;
        }
        
        if (slot == PREV_PAGE_SLOT && currentPage > 0) {
            currentPage--;
            populateInventory();
            return;
        }
        
        if (slot == NEXT_PAGE_SLOT) {
            int maxPage = Math.max(0, (filteredChildren.size() - 1) / CLAIM_SLOTS.length);
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
            int childIndex = currentPage * CLAIM_SLOTS.length + slotIndex;
            if (childIndex < filteredChildren.size()) {
                ChildInfo info = filteredChildren.get(childIndex);
                handleChildClick(event, info);
            }
        }
    }
    
    private void handleChildClick(InventoryClickEvent event, ChildInfo info) {
        if (isLeftClick(event) && !event.isShiftClick()) {
            if (player.hasPermission("griefprevention.claim.teleport")) {
                closeAndRun(() -> player.performCommand("claim tp " + info.claimId));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (isRightClick(event) && !event.isShiftClick()) {
            if (player.hasPermission("griefprevention.claim.name")) {
                player.closeInventory();
                String displayName = (info.name != null && !info.name.isEmpty() && !info.name.startsWith("Claim #"))
                    ? info.name : "Unnamed";
                SignInputGUI.openRename(plugin, player, displayName,
                    newName -> {
                        plugin.getNameStore().set(info.claimId, newName);
                        plugin.getNameStore().save();
                        plugin.getMessages().send(player, "gui.claim-renamed", "{id}", info.claimId, "{name}", newName);
                        manager.openChildrenClaims(player, parentClaim, parentClaimId);
                    },
                    () -> manager.openChildrenClaims(player, parentClaim, parentClaimId));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (isShiftLeftClick(event)) {
            if (info.hasChildren) {
                // Open nested children view (recursive)
                manager.openChildrenClaims(player, info.claim, info.claimId);
            } else {
                // Open options for this subdivision
                manager.openClaimOptions(player, info.claim, info.claimId);
            }
        }
    }
    
    private static class ChildInfo {
        final Object claim;
        final String claimId;
        String name;
        int area;
        String location;
        boolean is3D;
        boolean hasChildren;
        int childCount;
        
        ChildInfo(Object claim, String claimId) {
            this.claim = claim;
            this.claimId = claimId;
        }
    }
}
