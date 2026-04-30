package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Hopper-style confirmation GUI for {@code /buyclaimblocks <amount>}.
 * Routed through {@link GUIManager}: clicks are auto-cancelled and dispatched to
 * {@link #handleClick(InventoryClickEvent)}.
 */
public class BuyClaimBlocksConfirmationGUI extends BaseGUI {

    private static final int INFO_SLOT = 0;
    private static final int FILLER_SLOT_1 = 1;
    private static final int CONFIRM_SLOT = 2;
    private static final int FILLER_SLOT_3 = 3;
    private static final int CANCEL_SLOT = 4;

    private final GPBridge gp;
    private final int amount;
    private final double costPerBlock;
    private final double totalCost;
    private boolean resolved;

    public BuyClaimBlocksConfirmationGUI(GUIManager manager, Player player, int amount, double costPerBlock) {
        // No backing YAML config for this GUI; title/items are built inline.
        super(manager, player, "buy-claim-blocks");
        this.gp = new GPBridge();
        this.amount = amount;
        this.costPerBlock = costPerBlock;
        this.totalCost = amount * costPerBlock;
    }

    @Override
    public Inventory createInventory() {
        String title = ChatColor.GOLD + "Buy " + amount + " blocks for " + plugin.formatMoney(totalCost);
        this.inventory = Bukkit.createInventory(null, InventoryType.HOPPER, colorize(title));

        ItemStack filler = createSimpleFiller();
        inventory.setItem(FILLER_SLOT_1, filler);
        inventory.setItem(FILLER_SLOT_3, filler);
        inventory.setItem(INFO_SLOT, buildInfoItem());
        inventory.setItem(CONFIRM_SLOT, buildConfirmItem());
        inventory.setItem(CANCEL_SLOT, buildCancelItem());
        return inventory;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (resolved) return;
        int slot = event.getRawSlot();
        if (slot == CONFIRM_SLOT) {
            resolved = true;
            playClickSound();
            processPurchase();
            player.closeInventory();
        } else if (slot == CANCEL_SLOT) {
            resolved = true;
            playClickSound();
            player.sendMessage(colorize("&e" + gp.getGPMessageOr("EconomyPurchaseCancelled", "Purchase cancelled.")));
            player.closeInventory();
        }
    }

    @Override
    public void onClose(Player closingPlayer) {
        // If the player closes without clicking Confirm/Cancel, treat as cancel silently.
        // Nothing to clean up since we hold no pending-purchase state outside this instance.
        resolved = true;
    }

    private void processPurchase() {
        // Re-check economy and balance at the moment of confirmation.
        if (!plugin.isEconomyAvailable()) {
            player.sendMessage(colorize("&c" + gp.getGPMessageOr("EconomyNoVault",
                    "Economy is not available; purchase aborted.")));
            return;
        }
        if (!plugin.hasMoney(player, totalCost)) {
            String fmtCost = plugin.formatMoney(totalCost);
            String fmtBalance = plugin.formatMoney(plugin.getBalance(player));
            player.sendMessage(colorize("&c" + gp.getGPMessageOr("EconomyNotEnoughMoney",
                    "You don't have enough money. Cost: " + fmtCost + ", Your balance: " + fmtBalance,
                    fmtCost, fmtBalance)));
            return;
        }

        if (!plugin.withdrawMoney(player, totalCost)) {
            player.sendMessage(colorize("&cCould not withdraw funds for this purchase."));
            return;
        }

        if (!gp.creditBonusClaimBlocks(player, amount)) {
            // Refund on failure to credit.
            plugin.depositMoney(player, totalCost);
            player.sendMessage(colorize("&cFailed to credit claim blocks. Your money has been refunded."));
            return;
        }

        int newRemaining = gp.getRemainingClaimBlocks(player);
        String fmtCost = plugin.formatMoney(totalCost);
        String fallback = "Purchased " + amount + " claim blocks for " + fmtCost
                + ". You now have " + newRemaining + " claim blocks.";
        player.sendMessage(colorize("&a" + gp.getGPMessageOr("EconomyBuyBlocksConfirmation",
                fallback,
                String.valueOf(amount), fmtCost, String.valueOf(newRemaining))));
    }

    private ItemStack createSimpleFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildInfoItem() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + amount + " Claim Blocks");
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + "Cost per block: " + ChatColor.YELLOW + plugin.formatMoney(costPerBlock));
            lore.add(ChatColor.GRAY + "Total cost: " + ChatColor.GREEN + plugin.formatMoney(totalCost));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildConfirmItem() {
        ItemStack item = new ItemStack(Material.LIME_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "CONFIRM");
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + "Click to purchase");
            lore.add(ChatColor.YELLOW + String.valueOf(amount) + ChatColor.GRAY + " claim blocks");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildCancelItem() {
        ItemStack item = new ItemStack(Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "CANCEL");
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + "Click to cancel");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
