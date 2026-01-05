package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Hopper-style options menu for a specific claim.
 * Shows 3 options: View Children, Edit Settings, Setup Wizards
 */
public class ClaimOptionsGUI extends BaseGUI {
    
    private final Object claim;
    private final String claimId;
    private final GPBridge gp;
    
    // 9-slot row menu
    private static final int VIEW_CHILDREN_SLOT = 2;
    private static final int EDIT_SETTINGS_SLOT = 4;
    private static final int SETUP_WIZARDS_SLOT = 6;
    private static final int BACK_SLOT = 8;
    
    public ClaimOptionsGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "claim-options");
        this.claim = claim;
        this.claimId = claimId;
        this.gp = new GPBridge();
    }
    
    @Override
    public Inventory createInventory() {
        String title = getString("title", "&6&lClaim Options - #{id}").replace("{id}", claimId);
        inventory = org.bukkit.Bukkit.createInventory(null, 9, colorize(title));
        
        // Fill with glass
        ItemStack filler = createFiller();
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, filler);
        }
        
        // View Children button
        inventory.setItem(VIEW_CHILDREN_SLOT, createViewChildrenItem());
        
        // Edit Settings button
        inventory.setItem(EDIT_SETTINGS_SLOT, createEditSettingsItem());
        
        // Setup Wizards button
        inventory.setItem(SETUP_WIZARDS_SLOT, createSetupWizardsItem());
        
        // Back button
        inventory.setItem(BACK_SLOT, createBackItem());
        
        return inventory;
    }
    
    private ItemStack createViewChildrenItem() {
        int childCount = gp.getSubclaims(claim).size();
        
        List<String> lore = new ArrayList<>();
        lore.add("&7View subdivisions of this claim");
        lore.add("");
        lore.add("&7Children: &6" + childCount);
        lore.add("");
        lore.add("&eClick to view children");
        
        return createItem(Material.CHEST, "&b&lView Children", lore);
    }
    
    private ItemStack createEditSettingsItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Edit claim settings");
        lore.add("");
        
        // Dynamic lore based on permissions
        if (player.hasPermission("griefprevention.claim.ban")) {
            lore.add("&a✓ Manage banned players");
        } else {
            lore.add("&c✗ Manage banned players &8(no perm)");
        }
        
        if (player.hasPermission("griefprevention.claim.setspawn")) {
            lore.add("&a✓ Set teleport spawn");
        } else {
            lore.add("&c✗ Set teleport spawn &8(no perm)");
        }
        
        lore.add("");
        lore.add("&eClick to edit settings");
        
        return createItem(Material.COMPARATOR, "&e&lEdit Settings", lore);
    }
    
    private ItemStack createSetupWizardsItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Create rent or sell signs");
        lore.add("");
        lore.add("&7Available wizards:");
        lore.add("  &a• Rent Sign Setup");
        lore.add("  &a• Sell Sign Setup");
        lore.add("");
        lore.add("&eClick to open wizards");
        
        return createItem(Material.WRITABLE_BOOK, "&d&lSetup Wizards", lore);
    }
    
    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to claims list"));
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == VIEW_CHILDREN_SLOT) {
            manager.openChildrenClaims(player, claim, claimId);
        } else if (slot == EDIT_SETTINGS_SLOT) {
            manager.openClaimSettings(player, claim, claimId);
        } else if (slot == SETUP_WIZARDS_SLOT) {
            manager.openSetupWizards(player, claim, claimId);
        } else if (slot == BACK_SLOT) {
            manager.openOwnedClaims(player);
        }
    }
}
