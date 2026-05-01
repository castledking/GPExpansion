package codes.castled.gpexpansion.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import codes.castled.gpexpansion.gp.GPBridge;

import java.util.HashMap;
import java.util.Map;

/**
 * Hopper-style confirmation GUI for {@code /buyclaimblocks <amount>}.
 * Routed through {@link GUIManager}: clicks are auto-cancelled and dispatched to
 * {@link #handleClick(InventoryClickEvent)}.
 */
public class BuyClaimBlocksConfirmationGUI extends BaseGUI {

    // Default slot indices when guis/buyclaimblocks-confirm.yml is absent or missing keys.
    private static final int DEFAULT_CANCEL_SLOT = 0;
    private static final int DEFAULT_INFO_SLOT = 2;
    private static final int DEFAULT_CONFIRM_SLOT = 4;

    private final GPBridge gp;
    private final int amount;
    private final double costPerBlock;
    private final double totalCost;
    private final int cancelSlot;
    private final int infoSlot;
    private final int confirmSlot;
    private boolean resolved;

    public BuyClaimBlocksConfirmationGUI(GUIManager manager, Player player, int amount, double costPerBlock) {
        // Backing YAML: src/main/resources/guis/buyclaimblocks-confirm.yml
        super(manager, player, "buyclaimblocks-confirm");
        this.gp = new GPBridge();
        this.amount = amount;
        this.costPerBlock = costPerBlock;
        this.totalCost = amount * costPerBlock;
        this.cancelSlot = clampSlot(getInt("slots.cancel", DEFAULT_CANCEL_SLOT), DEFAULT_CANCEL_SLOT);
        this.infoSlot = clampSlot(getInt("slots.info", DEFAULT_INFO_SLOT), DEFAULT_INFO_SLOT);
        this.confirmSlot = clampSlot(getInt("slots.confirm", DEFAULT_CONFIRM_SLOT), DEFAULT_CONFIRM_SLOT);
    }

    @Override
    public Inventory createInventory() {
        Map<String, String> placeholders = buildPlaceholders();
        String title = applyPlaceholders(
                getString("title", "&6Buy {amount} blocks for {totalCost}"),
                placeholders);
        this.inventory = Bukkit.createInventory(null, InventoryType.HOPPER, colorize(title));

        ItemStack filler = createFiller();
        // Fill all non-button slots so layout stays clean even if user customized indices.
        for (int i = 0; i < inventory.getSize(); i++) {
            if (i != cancelSlot && i != infoSlot && i != confirmSlot) {
                inventory.setItem(i, filler);
            }
        }
        inventory.setItem(infoSlot, buildItem("info", placeholders, Material.GOLD_INGOT,
                "&6&l{amount} Claim Blocks",
                "",
                "&7Cost per block: &e{cost}",
                "&7Total cost: &a{totalCost}"));
        inventory.setItem(confirmSlot, buildItem("confirm", placeholders, Material.LIME_WOOL,
                "&a&lCONFIRM",
                "",
                "&7Click to purchase",
                "&e{amount} &7claim blocks"));
        inventory.setItem(cancelSlot, buildItem("cancel", placeholders, Material.RED_WOOL,
                "&c&lCANCEL",
                "",
                "&7Click to cancel"));
        return inventory;
    }

    private Map<String, String> buildPlaceholders() {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{amount}", String.valueOf(amount));
        placeholders.put("{cost}", plugin.formatMoney(costPerBlock));
        placeholders.put("{totalCost}", plugin.formatMoney(totalCost));
        return placeholders;
    }

    /**
     * Build an item from {@code items.<key>} in the GUI config, or fall back to the supplied
     * material/name/lore defaults if the section is absent. Placeholders are applied to all
     * strings.
     */
    private ItemStack buildItem(String key, Map<String, String> placeholders, Material defaultMaterial,
                                String defaultName, String... defaultLore) {
        if (config != null && config.isConfigurationSection("items." + key)) {
            ItemStack fromConfig = createItemFromConfig("items." + key, placeholders);
            if (fromConfig != null && fromConfig.getType() != Material.AIR && fromConfig.getType() != Material.STONE) {
                return fromConfig;
            }
        }
        // Fallback: build from English defaults so the GUI still works without the YAML.
        java.util.List<String> lore = new java.util.ArrayList<>();
        for (String line : defaultLore) lore.add(applyPlaceholders(line, placeholders));
        return createItem(defaultMaterial, applyPlaceholders(defaultName, placeholders), lore);
    }

    private int clampSlot(int requested, int fallback) {
        return (requested >= 0 && requested <= 4) ? requested : fallback;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (resolved) return;
        int slot = event.getRawSlot();
        if (slot == confirmSlot) {
            resolved = true;
            playClickSound();
            processPurchase();
            player.closeInventory();
        } else if (slot == cancelSlot) {
            resolved = true;
            playClickSound();
            sendLegacy("&e" + gp.getGPMessageOr("EconomyPurchaseCancelled", "Purchase cancelled."));
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
            sendLegacy("&c" + gp.getGPMessageOr("EconomyNoVault",
                    "Economy is not available; purchase aborted."));
            return;
        }
        if (!plugin.hasMoney(player, totalCost)) {
            String fmtCost = plugin.formatMoney(totalCost);
            String fmtBalance = plugin.formatMoney(plugin.getBalance(player));
            sendLegacy("&c" + gp.getGPMessageOr("EconomyNotEnoughMoney",
                    "You don't have enough money. Cost: " + fmtCost + ", Your balance: " + fmtBalance,
                    fmtCost, fmtBalance));
            return;
        }

        if (!plugin.withdrawMoney(player, totalCost)) {
            sendLegacy("&cCould not withdraw funds for this purchase.");
            return;
        }

        if (!gp.creditBonusClaimBlocks(player, amount)) {
            // Refund on failure to credit.
            plugin.depositMoney(player, totalCost);
            sendLegacy("&cFailed to credit claim blocks. Your money has been refunded.");
            return;
        }

        int newRemaining = gp.getRemainingClaimBlocks(player);
        String fmtCost = plugin.formatMoney(totalCost);
        String fallback = "Purchased " + amount + " claim blocks for " + fmtCost
                + ". You now have " + newRemaining + " claim blocks.";
        sendLegacy("&a" + gp.getGPMessageOr("EconomyBuyBlocksConfirmation",
                fallback,
                String.valueOf(amount), fmtCost, String.valueOf(newRemaining)));
    }

    /** Translate &-codes and send the resulting legacy-section string to the player. */
    private void sendLegacy(String text) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }
}
