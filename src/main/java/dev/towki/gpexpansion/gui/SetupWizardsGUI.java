package dev.towki.gpexpansion.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for starting setup wizards (rent/sell signs) for a claim.
 * Note: Mailbox is not available here - mailboxes must be subdivisions.
 */
public class SetupWizardsGUI extends BaseGUI {
    
    private final Object claim;
    private final String claimId;
    
    private static final int RENT_WIZARD_SLOT = 11;
    private static final int SELL_WIZARD_SLOT = 15;
    private static final int BACK_SLOT = 22;
    
    public SetupWizardsGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "setup-wizards");
        this.claim = claim;
        this.claimId = claimId;
    }
    
    @Override
    public Inventory createInventory() {
        String title = getString("title", "&d&lSetup Wizards - #{id}").replace("{id}", claimId);
        inventory = createBaseInventoryWithTitle(title, 27);
        
        fillBorder(createFiller());
        
        inventory.setItem(RENT_WIZARD_SLOT, createRentWizardItem());
        inventory.setItem(SELL_WIZARD_SLOT, createSellWizardItem());
        inventory.setItem(BACK_SLOT, createBackItem());
        
        return inventory;
    }
    
    private ItemStack createRentWizardItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Create a rent sign for this claim");
        lore.add("");
        lore.add("&7Players will be able to rent your");
        lore.add("&7claim for a set duration and price.");
        lore.add("");
        
        if (player.hasPermission("griefprevention.sign.create.rent")) {
            lore.add("&a✓ You can create rent signs");
            lore.add("");
            lore.add("&eClick to start rent wizard");
        } else {
            lore.add("&c✗ No permission to create rent signs");
            lore.add("&8Missing: griefprevention.sign.create.rent");
        }
        
        return createItem(Material.CLOCK, "&6&lRent Sign Wizard", lore);
    }
    
    private ItemStack createSellWizardItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Create a sell sign for this claim");
        lore.add("");
        lore.add("&7Players will be able to purchase");
        lore.add("&7your claim permanently.");
        lore.add("");
        
        if (player.hasPermission("griefprevention.sign.create.sell")) {
            lore.add("&a✓ You can create sell signs");
            lore.add("");
            lore.add("&eClick to start sell wizard");
        } else {
            lore.add("&c✗ No permission to create sell signs");
            lore.add("&8Missing: griefprevention.sign.create.sell");
        }
        
        return createItem(Material.EMERALD, "&a&lSell Sign Wizard", lore);
    }
    
    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to claim options"));
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == RENT_WIZARD_SLOT) {
            if (player.hasPermission("griefprevention.sign.create.rent")) {
                closeAndRunOnMainThread(() -> player.performCommand("rentclaim " + claimId));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == SELL_WIZARD_SLOT) {
            if (player.hasPermission("griefprevention.sign.create.sell")) {
                closeAndRunOnMainThread(() -> player.performCommand("sellclaim " + claimId));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (slot == BACK_SLOT) {
            manager.openClaimOptions(player, claim, claimId);
        }
    }
}
