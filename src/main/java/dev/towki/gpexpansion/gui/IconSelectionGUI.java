package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * GUI for selecting an icon for a claim using a hopper inventory.
 * Shows a hopper where players can place any item to set it as the claim icon.
 */
public class IconSelectionGUI extends BaseGUI implements org.bukkit.event.Listener, InventoryHolder {
    
    private final Consumer<Material> onIconSelected;
    private final Runnable onCancel;
    private Inventory inventory;
    private boolean selectionHandled = false;
    
    // Slot positions from config
    private int instructionsSlot = 0;
    private int centerSlot = 2;
    private int backSlot = 4;
    
    public IconSelectionGUI(GPExpansionPlugin plugin, Player player, Consumer<Material> onIconSelected, Runnable onCancel) {
        super(new GUIManager(plugin), player, "icon-selection");
        this.onIconSelected = onIconSelected;
        this.onCancel = onCancel;
        
        // Load slot positions from config
        if (config != null) {
            instructionsSlot = config.getInt("slots.instructions", 0);
            centerSlot = config.getInt("slots.center", 2);
            backSlot = config.getInt("slots.back", 4);
        }
    }
    
    @Override
    public Inventory createInventory() {
        // This method is required but we won't use it since we create the hopper directly in open()
        return Bukkit.createInventory(null, 9, "Icon Selection");
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        // This method is required but we handle events separately for the hopper
        playClickSound();
    }
    
    public void open() {
        // Create a hopper inventory for icon selection
        String title = getString("title", "&6&lSelect Icon - Place item in center");
        inventory = plugin.getServer().createInventory(this, InventoryType.HOPPER, colorize(title));
        
        // Fill with glass panes, leaving center empty
        ItemStack filler = createFiller();
        for (int i = 0; i < 5; i++) {
            if (i == centerSlot) continue;
            inventory.setItem(i, filler);
        }
        
        // Add instruction item
        inventory.setItem(instructionsSlot, createInstructionsItem());
        
        // Add back button
        inventory.setItem(backSlot, createBackItem());

        // Leave center slot empty for selection
        
        // Register events and open the hopper
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        player.openInventory(inventory);
    }
    
    private ItemStack createInstructionsItem() {
        List<String> lore = getStringList("items.instructions.lore");
        if (lore.isEmpty()) {
            lore = Arrays.asList(
                "&7Place any item from your inventory",
                "&7into the center slot below",
                "&7to set it as the claim icon.",
                "",
                "&7The item will NOT be consumed."
            );
        }
        
        String materialName = getString("items.instructions.material", "PAPER");
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            material = Material.PAPER;
        }
        
        String name = getString("items.instructions.name", "&e&lInstructions");
        
        return createItem(material, name, lore);
    }
    
    private ItemStack createBackItem() {
        List<String> lore = getStringList("items.back.lore");
        if (lore.isEmpty()) {
            lore = Arrays.asList("&7Return to previous menu");
        }
        
        String materialName = getString("items.back.material", "ARROW");
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            material = Material.ARROW;
        }
        
        String name = getString("items.back.name", "&c&lCancel");
        
        return createItem(material, name, lore);
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (inventory == null) return;
        if (!event.getWhoClicked().equals(player)) return;

        if (!isOurSession()) return;
        Inventory clicked = event.getClickedInventory();
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        int rawSlot = event.getRawSlot();
        int topSize = inventory.getSize();
        
        // Prevent double-click from stealing GUI items
        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
            return;
        }
        
        // Handle back button click
        if (rawSlot < topSize) {
            if (rawSlot == backSlot) {
                event.setCancelled(true);
                event.setResult(org.bukkit.event.Event.Result.DENY);
                selectionHandled = true;
                player.closeInventory();
                runLater(onCancel, 1L);
                return;
            }
            
            // Protect all non-center slots in the hopper
            if (rawSlot != centerSlot) {
                event.setCancelled(true);
                event.setResult(org.bukkit.event.Event.Result.DENY);
                return;
            }
        }
        
        // Set icon from a click into the center slot (allowed)
        if (rawSlot < topSize && rawSlot == centerSlot) {
            ItemStack offered = null;
            switch (event.getClick()) {
                case NUMBER_KEY -> {
                    int hotbar = event.getHotbarButton();
                    if (hotbar >= 0) {
                        offered = player.getInventory().getItem(hotbar);
                    }
                }
                case SWAP_OFFHAND -> offered = player.getInventory().getItemInOffHand();
                default -> {
                    if (cursor != null && cursor.getType() != Material.AIR) {
                        offered = cursor;
                    }
                }
            }
            if (offered != null && offered.getType() != Material.AIR) {
                selectIcon(offered.getType());
            }
            return;
        }

        // Shift-click from player inventory to set icon (without moving items)
        if (event.isShiftClick() && clicked != null && clicked != inventory) {
            // Allow the shift-click to place into the only empty slot (center)
            runLater(this::checkCenterAndSelect, 1L);
            return;
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (inventory == null) return;
        if (!event.getWhoClicked().equals(player)) return;

        if (!isOurSession()) return;

        for (int slot : event.getInventorySlots()) {
            if (slot != centerSlot) {
                event.setCancelled(true);
                event.setResult(org.bukkit.event.Event.Result.DENY);
                return;
            }
        }
        
        // Only dragging into center is allowed
        runLater(this::checkCenterAndSelect, 1L);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (inventory == null || event.getInventory() != inventory) return;
        if (!event.getPlayer().equals(player)) return;
        
        // Unregister events
        org.bukkit.event.HandlerList.unregisterAll(this);
        if (!selectionHandled) {
            runLater(onCancel, 1L);
        }
    }

    private void selectIcon(Material material) {
        if (material == null || material == Material.AIR) return;
        selectionHandled = true;
        if (inventory != null) {
            inventory.setItem(centerSlot, new ItemStack(material));
        }
        player.closeInventory();
        runLater(() -> onIconSelected.accept(material), 1L);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
    
    private boolean isOurSession() {
        return inventory != null && inventory.getViewers().contains(player);
    }
    
    private void checkCenterAndSelect() {
        if (selectionHandled || inventory == null) return;
        ItemStack inCenter = inventory.getItem(centerSlot);
        if (inCenter != null && inCenter.getType() != Material.AIR) {
            selectIcon(inCenter.getType());
        }
    }
    
    // Helper method to check if item is a filler
    // No filler check needed; selection uses cursor or shift-click.
}
