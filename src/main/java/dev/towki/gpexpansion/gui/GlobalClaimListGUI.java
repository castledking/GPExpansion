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
import java.util.Arrays;
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
    
    // Slot positions from config
    private int searchSlot = 4;
    private int filterSlot = 49;
    private int prevPageSlot = 45;
    private int nextPageSlot = 53;
    private int backSlot = 48;
    private int[] claimSlots = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    
    // Filter types
    public enum FilterType {
        ALL("All Claims", "all", Material.GRASS_BLOCK),
        RENTED("Rented Claims", "rented", Material.GOLD_BLOCK),
        SOLD("Sold Claims", "sold", Material.DIAMOND_BLOCK),
        MAILBOXES("Mailboxes", "mailboxes", Material.CHEST),
        REGULAR("Regular Claims", "regular", Material.DIRT);
        
        private final String displayName;
        private final String configKey;
        private final Material defaultMaterial;
        
        FilterType(String displayName, String configKey, Material defaultMaterial) {
            this.displayName = displayName;
            this.configKey = configKey;
            this.defaultMaterial = defaultMaterial;
        }
        
        public String getDisplayName() { return displayName; }
        public String getConfigKey() { return configKey; }
        public Material getDefaultMaterial() { return defaultMaterial; }
        
        public FilterType next() {
            FilterType[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }
    
    private FilterType currentFilter = FilterType.ALL;
    
    public GlobalClaimListGUI(GUIManager manager, Player player) {
        this(manager, player, null);
    }
    
    public GlobalClaimListGUI(GUIManager manager, Player player, String searchQuery) {
        super(manager, player, "global-claim-list");
        this.gp = new GPBridge();
        this.searchQuery = searchQuery;
        
        // Load slot positions from config
        if (config != null) {
            searchSlot = config.getInt("slots.search", 4);
            filterSlot = config.getInt("slots.filter", 49);
            prevPageSlot = config.getInt("slots.prev-page", 45);
            nextPageSlot = config.getInt("slots.next-page", 53);
            backSlot = config.getInt("slots.back", 48);
            
            // Load claim slots from config
            List<Integer> slots = config.getIntegerList("slots.claim-slots");
            if (!slots.isEmpty()) {
                claimSlots = slots.stream().mapToInt(i -> i).toArray();
            }
        }
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
            GlobalClaimListGUI gui = new GlobalClaimListGUI(manager, player, searchQuery);
            gui.currentPage = page;
            
            gui.loadPublicClaims();
            dev.towki.gpexpansion.scheduler.SchedulerAdapter.runEntity(manager.getPlugin(), player, () -> {
                // Save state for /claim ! command
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.GLOBAL_CLAIM_LIST, 
                    gui.searchQuery, null, gui.currentPage);
                manager.openGUI(player, gui);
            }, null);
        });
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
            info.name = plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim #" + claimId);
            
            // Get owner name
            info.ownerName = getOwnerName(claim);
            info.ownerUUID = getOwnerUUID(claim);
            
            // Get location
            info.location = getClaimLocation(claim);
            
            // Apply search filter if present
            if (searchQuery != null && !searchQuery.isEmpty()) {
                String query = searchQuery.toLowerCase();
                if (!(info.claimId != null && info.claimId.equalsIgnoreCase(searchQuery)) &&
                    !info.name.toLowerCase().contains(query) && 
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
        inventory = createBaseInventoryWithTitle(title, 54);
        populateInventory();
        return inventory;
    }
    
    private void populateInventory() {
        inventory.clear();
        fillBorder(createFiller());
        
        // Search button
        inventory.setItem(searchSlot, createSearchItem());
        
        // Filter button
        inventory.setItem(filterSlot, createFilterItem());
        
        // Back button
        inventory.setItem(backSlot, createBackItem());
        
        // Navigation
        if (currentPage > 0) {
            inventory.setItem(prevPageSlot, createPrevPageItem());
        }
        
        int maxPage = Math.max(0, (publicClaims.size() - 1) / claimSlots.length);
        if (currentPage < maxPage) {
            inventory.setItem(nextPageSlot, createNextPageItem());
        }
        
        // Claim items
        int startIndex = currentPage * claimSlots.length;
        for (int i = 0; i < claimSlots.length && startIndex + i < publicClaims.size(); i++) {
            ClaimInfo info = publicClaims.get(startIndex + i);
            inventory.setItem(claimSlots[i], createClaimItem(info));
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
    
    private ItemStack createFilterItem() {
        String materialName = getString("filters." + currentFilter.getConfigKey() + ".material", currentFilter.getDefaultMaterial().name());
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            material = currentFilter.getDefaultMaterial();
        }
        
        String name = getString("filters." + currentFilter.getConfigKey() + ".name", currentFilter.getDisplayName());
        
        return createItem(material, "&6&lFilter: " + name, Arrays.asList("&eClick to cycle filters"));
    }
    
    private ItemStack createPrevPageItem() {
        int maxPage = Math.max(1, (publicClaims.size() - 1) / claimSlots.length + 1);
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + (currentPage + 1) + "/" + maxPage));
    }
    
    private ItemStack createNextPageItem() {
        int maxPage = Math.max(1, (publicClaims.size() - 1) / claimSlots.length + 1);
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
        
        if (slot == searchSlot) {
            // Open sign editor for search
            player.closeInventory();
            SignInputGUI.openSearch(plugin, player, 
                query -> {
                    GlobalClaimListGUI.openAsync(manager, player, query);
                },
                () -> GlobalClaimListGUI.openAsync(manager, player, null));
            return;
        }
        
        if (slot == filterSlot) {
            // Cycle through filters
            currentFilter = currentFilter.next();
            loadPublicClaims();
            populateInventory();
            return;
        }
        
        if (slot == backSlot) {
            manager.openMainMenu(player);
            return;
        }
        
        if (slot == prevPageSlot && currentPage > 0) {
            currentPage--;
            populateInventory();
            return;
        }
        
        if (slot == nextPageSlot) {
            int maxPage = Math.max(0, (publicClaims.size() - 1) / claimSlots.length);
            if (currentPage < maxPage) {
                currentPage++;
                populateInventory();
            }
            return;
        }
        
        // Check if clicked on a claim
        int slotIndex = -1;
        for (int i = 0; i < claimSlots.length; i++) {
            if (claimSlots[i] == slot) {
                slotIndex = i;
                break;
            }
        }
        
        if (slotIndex >= 0) {
            int claimIndex = currentPage * claimSlots.length + slotIndex;
            if (claimIndex < publicClaims.size()) {
                ClaimInfo info = publicClaims.get(claimIndex);
                if (player.hasPermission("griefprevention.claim.teleport")) {
                    closeAndRunOnMainThread("claim tp " + info.claimId);
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
