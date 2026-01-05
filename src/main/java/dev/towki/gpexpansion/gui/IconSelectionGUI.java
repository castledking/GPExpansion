package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.scheduler.SchedulerAdapter;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Double chest GUI for selecting a claim icon from available materials.
 * Uses async loading to show the first page immediately while loading more in the background.
 */
public class IconSelectionGUI extends BaseGUI {
    
    private final String claimId;
    private final Consumer<Material> onSelect;
    private final Runnable onCancel;
    
    private int currentPage = 0;
    private final CopyOnWriteArrayList<Material> availableMaterials = new CopyOnWriteArrayList<>();
    private final AtomicInteger loadedPages = new AtomicInteger(0);
    private final AtomicBoolean fullyLoaded = new AtomicBoolean(false);
    
    private static final int PREV_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int BACK_SLOT = 49;
    private static final int CLEAR_SLOT = 47;
    private static final int ITEMS_PER_PAGE = 45;
    
    // Item slots (excluding bottom row for navigation)
    private static final int[] ITEM_SLOTS;
    static {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            slots.add(i);
        }
        ITEM_SLOTS = slots.stream().mapToInt(Integer::intValue).toArray();
    }
    
    // Non-survival/unobtainable items to exclude
    private static final Set<Material> EXCLUDED_MATERIALS = EnumSet.of(
        // Command/Admin blocks
        Material.COMMAND_BLOCK,
        Material.CHAIN_COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK,
        Material.COMMAND_BLOCK_MINECART,
        Material.STRUCTURE_BLOCK,
        Material.STRUCTURE_VOID,
        Material.JIGSAW,
        Material.BARRIER,
        Material.LIGHT,
        
        // Bedrock and indestructible
        Material.BEDROCK,
        Material.REINFORCED_DEEPSLATE,
        
        // Portal blocks
        Material.END_PORTAL_FRAME,
        Material.END_GATEWAY,
        
        // Debug/technical items
        Material.DEBUG_STICK,
        Material.KNOWLEDGE_BOOK,
        Material.BUNDLE, // Often buggy in older versions
        
        // Perishable/internal
        Material.PETRIFIED_OAK_SLAB,
        
        // Air variants
        Material.AIR,
        Material.CAVE_AIR,
        Material.VOID_AIR
    );
    
    // Additional name patterns to exclude
    private static final String[] EXCLUDED_PATTERNS = {
        "SPAWN_EGG",
        "LEGACY",
        "POTTED_",
        "WALL_",
        "_BANNER_PATTERN",
        "INFESTED_",
        "BUDDING_AMETHYST", // Can't be obtained with silk touch
        "SPAWNER", // Monster spawner
        "TRIAL_SPAWNER",
        "VAULT" // Trial vault
    };
    
    public IconSelectionGUI(GUIManager manager, Player player, String claimId, 
                            Consumer<Material> onSelect, Runnable onCancel) {
        super(manager, player, "icon-selection");
        this.claimId = claimId;
        this.onSelect = onSelect;
        this.onCancel = onCancel;
        // Start async loading - first page will be ready quickly
        startAsyncLoading();
    }
    
    private void startAsyncLoading() {
        // Load materials asynchronously
        SchedulerAdapter.runAsync(plugin, () -> {
            List<Material> allMaterials = new ArrayList<>();
            
            for (Material m : Material.values()) {
                if (isValidIconMaterial(m)) {
                    allMaterials.add(m);
                }
            }
            
            // Sort alphabetically
            allMaterials.sort((a, b) -> a.name().compareTo(b.name()));
            
            // Add materials in batches and update loaded pages count
            int batchSize = ITEMS_PER_PAGE;
            for (int i = 0; i < allMaterials.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allMaterials.size());
                List<Material> batch = allMaterials.subList(i, end);
                availableMaterials.addAll(batch);
                
                int pagesLoaded = (availableMaterials.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
                loadedPages.set(pagesLoaded);
                
                // Update inventory on main thread if player is viewing first page while it loads
                if (pagesLoaded == 1 && inventory != null) {
                    SchedulerAdapter.runOnEntity(plugin, player, () -> {
                        if (player.getOpenInventory().getTopInventory() == inventory) {
                            populateInventory();
                        }
                    }, null);
                }
            }
            
            fullyLoaded.set(true);
            
            // Final update to refresh navigation buttons
            SchedulerAdapter.runOnEntity(plugin, player, () -> {
                if (player.getOpenInventory().getTopInventory() == inventory) {
                    updateNavigationButtons();
                }
            }, null);
        });
    }
    
    private boolean isValidIconMaterial(Material m) {
        // Must be an item
        if (!m.isItem()) return false;
        if (m.isAir()) return false;
        
        // Check exclusion set
        if (EXCLUDED_MATERIALS.contains(m)) return false;
        
        // Check name patterns
        String name = m.name();
        for (String pattern : EXCLUDED_PATTERNS) {
            if (name.contains(pattern)) return false;
        }
        
        return true;
    }
    
    @Override
    public Inventory createInventory() {
        String title = getString("title", "&6&lSelect Icon");
        inventory = createBaseInventory(title, 54);
        populateInventory();
        return inventory;
    }
    
    private void populateInventory() {
        inventory.clear();
        
        // Fill navigation row
        ItemStack filler = createFiller();
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }
        
        // Navigation buttons
        inventory.setItem(BACK_SLOT, createBackItem());
        inventory.setItem(CLEAR_SLOT, createClearItem());
        
        updateNavigationButtons();
        
        // Material items
        int startIndex = currentPage * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE && startIndex + i < availableMaterials.size(); i++) {
            Material mat = availableMaterials.get(startIndex + i);
            inventory.setItem(ITEM_SLOTS[i], createMaterialItem(mat));
        }
        
        // Show loading indicator if not fully loaded and on last available page
        if (!fullyLoaded.get() && availableMaterials.size() > 0) {
            int lastItemIndex = availableMaterials.size() - 1;
            int lastItemPage = lastItemIndex / ITEMS_PER_PAGE;
            if (currentPage == lastItemPage) {
                // Show a loading indicator in an empty slot if we're on the loading edge
                int itemsOnPage = availableMaterials.size() - (currentPage * ITEMS_PER_PAGE);
                if (itemsOnPage < ITEMS_PER_PAGE) {
                    inventory.setItem(itemsOnPage, createItem(Material.CLOCK, "&e&lLoading...", 
                        List.of("&7More items are being loaded")));
                }
            }
        }
    }
    
    /**
     * Update navigation buttons based on current loading state.
     */
    private void updateNavigationButtons() {
        // Previous page - only show if not on first page
        if (currentPage > 0) {
            inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        } else {
            inventory.setItem(PREV_PAGE_SLOT, createFiller());
        }
        
        // Next page - only show if there's a next page AND it's loaded
        int currentMaxPage = Math.max(0, (availableMaterials.size() - 1) / ITEMS_PER_PAGE);
        boolean hasNextPage = currentPage < currentMaxPage;
        boolean nextPageLoaded = loadedPages.get() > currentPage + 1 || fullyLoaded.get();
        
        if (hasNextPage && nextPageLoaded) {
            inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        } else if (hasNextPage && !nextPageLoaded) {
            // Next page exists but not loaded yet - show loading indicator
            inventory.setItem(NEXT_PAGE_SLOT, createItem(Material.CLOCK, "&7&lLoading...", 
                List.of("&7Next page is loading")));
        } else {
            inventory.setItem(NEXT_PAGE_SLOT, createFiller());
        }
    }
    
    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return without selecting"));
    }
    
    private ItemStack createClearItem() {
        return createItem(Material.BARRIER, "&e&lClear Icon", List.of("&7Remove custom icon", "&7(use default)"));
    }
    
    private ItemStack createPrevPageItem() {
        int totalPages = fullyLoaded.get() 
            ? Math.max(1, (availableMaterials.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE)
            : loadedPages.get();
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + currentPage + "/" + totalPages));
    }
    
    private ItemStack createNextPageItem() {
        int totalPages = fullyLoaded.get() 
            ? Math.max(1, (availableMaterials.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE)
            : loadedPages.get();
        String pageInfo = fullyLoaded.get() 
            ? "&7Page " + (currentPage + 2) + "/" + totalPages
            : "&7Page " + (currentPage + 2) + "/...";
        return createItem(Material.ARROW, "&e&lNext Page »", List.of(pageInfo));
    }
    
    private ItemStack createMaterialItem(Material material) {
        String name = material.name().toLowerCase().replace('_', ' ');
        // Capitalize each word
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Click to select as icon");
        
        return createItem(material, "&f" + sb.toString(), lore);
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == BACK_SLOT) {
            player.closeInventory();
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }
        
        if (slot == CLEAR_SLOT) {
            player.closeInventory();
            if (onSelect != null) {
                onSelect.accept(null);
            }
            return;
        }
        
        if (slot == PREV_PAGE_SLOT && currentPage > 0) {
            currentPage--;
            populateInventory();
            return;
        }
        
        if (slot == NEXT_PAGE_SLOT) {
            int maxPage = Math.max(0, (availableMaterials.size() - 1) / ITEMS_PER_PAGE);
            // Only allow navigation if page is loaded
            boolean nextPageLoaded = loadedPages.get() > currentPage + 1 || fullyLoaded.get();
            if (currentPage < maxPage && nextPageLoaded) {
                currentPage++;
                populateInventory();
            }
            return;
        }
        
        // Check if clicked on a material
        if (slot >= 0 && slot < 45) {
            int materialIndex = currentPage * ITEMS_PER_PAGE + slot;
            if (materialIndex < availableMaterials.size()) {
                Material selected = availableMaterials.get(materialIndex);
                player.closeInventory();
                if (onSelect != null) {
                    onSelect.accept(selected);
                }
            }
        }
    }
}
