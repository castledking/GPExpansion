package codes.castled.gpexpansion.gui;

import net.kyori.adventure.text.Component;
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

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.util.ClaimCustomizationUtil;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * GUI for selecting an icon for a claim using a hopper inventory.
 *
 * <p>The player offers an item by clicking it onto the center slot, pressing a hotbar key or
 * the offhand-swap key over it, or shift-clicking it from their inventory. Every click is
 * cancelled: the offered item is copied (with all of its custom data) and never leaves the
 * player's inventory.
 */
public class IconSelectionGUI extends BaseGUI implements org.bukkit.event.Listener, InventoryHolder {

    private final Consumer<ItemStack> onIconSelected;
    private final Runnable onCancel;
    private final ItemStack currentIcon;
    private Inventory inventory;
    private boolean selectionHandled = false;

    // Slot positions from config
    private int instructionsSlot = 0;
    private int centerSlot = 2;
    private int backSlot = 4;

    public IconSelectionGUI(GPExpansionPlugin plugin, Player player, Consumer<ItemStack> onIconSelected, Runnable onCancel) {
        this(plugin, player, null, onIconSelected, onCancel);
    }

    /**
     * @param currentIcon the claim's current icon, previewed in the center slot (may be null)
     */
    public IconSelectionGUI(GPExpansionPlugin plugin, Player player, ItemStack currentIcon,
                            Consumer<ItemStack> onIconSelected, Runnable onCancel) {
        super(plugin.getGUIManager(), player, "icon-selection");
        this.onIconSelected = onIconSelected;
        this.onCancel = onCancel;
        this.currentIcon = currentIcon;

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
        return Bukkit.createInventory(null, InventoryType.CHEST, Component.text("Icon Selection"));
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

        // Preview the current icon; clicks on it are cancelled like every other slot.
        if (currentIcon != null && currentIcon.getType() != Material.AIR) {
            inventory.setItem(centerSlot, restyleItem(currentIcon,
                getString("items.current.name", "&a&lCurrent Icon"),
                getStringList("items.current.lore").isEmpty()
                    ? List.of("&7Click an item onto this slot", "&7to replace it.")
                    : getStringList("items.current.lore"),
                false));
        }

        // Register events and open the hopper
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (inventory != null) {
            player.openInventory(inventory);
        }
    }

    private ItemStack createInstructionsItem() {
        List<String> lore = getStringList("items.instructions.lore");
        if (lore.isEmpty()) {
            lore = Arrays.asList(
                "&7Click any item onto the center slot,",
                "&7press its hotbar key over the slot,",
                "&7or shift-click it in your inventory",
                "&7to set it as the claim icon.",
                "",
                "&7Custom items keep their look.",
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

        // Nothing ever moves in or out of this menu; the offered item is only copied.
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (selectionHandled) return;
        int rawSlot = event.getRawSlot();
        int topSize = inventory.getSize();

        if (rawSlot >= 0 && rawSlot < topSize) {
            if (rawSlot == backSlot) {
                selectionHandled = true;
                player.closeInventory();
                runLater(onCancel, 1L);
                return;
            }
            if (rawSlot != centerSlot) return;

            ItemStack offered = switch (event.getClick()) {
                case NUMBER_KEY -> {
                    int hotbar = event.getHotbarButton();
                    yield hotbar >= 0 ? player.getInventory().getItem(hotbar) : null;
                }
                case SWAP_OFFHAND -> player.getInventory().getItemInOffHand();
                default -> event.getCursor();
            };
            selectIcon(offered);
            return;
        }

        // Shift-click in the player's own inventory offers that item.
        if (event.isShiftClick() && event.getClick() != ClickType.DOUBLE_CLICK) {
            selectIcon(event.getCurrentItem());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (inventory == null) return;
        if (!event.getWhoClicked().equals(player)) return;
        if (!isOurSession()) return;

        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < inventory.getSize());
        if (!touchesTop) return;

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
        if (!selectionHandled && event.getRawSlots().contains(centerSlot)) {
            selectIcon(event.getOldCursor());
        }
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

    private void selectIcon(ItemStack offered) {
        if (offered == null || offered.getType() == Material.AIR) return;
        if (!ClaimCustomizationUtil.isIconAllowed(plugin, offered)) {
            plugin.getMessages().send(player, "claim.icon-denied", "{icon}", offered.getType().name());
            return;
        }
        ItemStack copy = offered.clone();
        copy.setAmount(1);
        selectionHandled = true;
        player.closeInventory();
        runLater(() -> onIconSelected.accept(copy), 1L);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private boolean isOurSession() {
        return inventory != null && inventory.getViewers().contains(player);
    }
}
