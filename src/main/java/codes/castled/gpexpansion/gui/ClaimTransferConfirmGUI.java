package codes.castled.gpexpansion.gui;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.transfer.ClaimTransferService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Final confirmation before giving a claim to another player.
 */
public class ClaimTransferConfirmGUI extends BaseGUI {

    private final Object claim;
    private final String claimId;
    private final OfflinePlayer recipient;
    private final ClaimTransferService transfers;
    private final Map<String, String> placeholders = new HashMap<>();

    private int confirmSlot = 11;
    private int recipientSlot = 13;
    private int cancelSlot = 15;

    public ClaimTransferConfirmGUI(GUIManager manager, Player player, Object claim, String claimId, OfflinePlayer recipient) {
        super(manager, player, "claim-transfer");
        this.claim = claim;
        this.claimId = claimId;
        this.recipient = recipient;
        this.transfers = plugin.getClaimTransferService();

        if (config != null) {
            confirmSlot = config.getInt("items.confirm.slot", confirmSlot);
            recipientSlot = config.getInt("items.recipient.slot", recipientSlot);
            cancelSlot = config.getInt("items.cancel.slot", cancelSlot);
        }

        String recipientName = ClaimTransferService.displayName(recipient);
        placeholders.put("{id}", claimId);
        placeholders.put("{name}", plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim #" + claimId));
        placeholders.put("{player}", recipientName);
        placeholders.put("{player_uuid}", recipient.getUniqueId().toString());
        placeholders.put("{area}", String.valueOf(new GPBridge().getClaimAreaSafe(claim)));
        placeholders.put("{fee}", transfers.formatFee(transfers.fee(player)));
    }

    @Override
    public Inventory createInventory() {
        String title = applyPlaceholders(getString("title", "&6&lGive Claim #{id}?"), placeholders);
        inventory = createBaseInventoryWithTitle(title, 27);
        fillEmpty(createFiller());
        inventory.setItem(confirmSlot, item("items.confirm", Material.LIME_STAINED_GLASS_PANE, "&a&lConfirm Transfer"));
        inventory.setItem(recipientSlot, item("items.recipient", Material.PLAYER_HEAD, "&e&l{player}"));
        inventory.setItem(cancelSlot, item("items.cancel", Material.RED_STAINED_GLASS_PANE, "&c&lCancel"));
        return inventory;
    }

    private ItemStack item(String path, Material fallback, String fallbackName) {
        if (getConfigSection(path) != null) {
            return createItemFromConfig(path, placeholders);
        }
        return createItem(fallback, applyPlaceholders(fallbackName, placeholders), List.of());
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == confirmSlot) {
            playClickSound();
            player.closeInventory();
            if (transfers.transfer(player, claim, claimId, recipient)) {
                runLater(() -> manager.openOwnedClaims(player), 1L);
            } else {
                runLater(() -> manager.openClaimOptions(player, claim, claimId), 1L);
            }
        } else if (slot == cancelSlot) {
            playClickSound();
            manager.openClaimOptions(player, claim, claimId);
        }
    }
}
