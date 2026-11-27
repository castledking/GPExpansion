package codes.castled.gpexpansion.gui;

import codes.castled.gpexpansion.gp.GPFlagsBridge;
import codes.castled.gpexpansion.util.Mini;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reusable 27-slot single-choice sub-menu for GPFlags that require picking one discrete value
 * (for example {@code PlayerGamemode}, {@code PlayerTime}, and {@code PlayerWeather}).
 */
public class GPFlagChoiceGUI extends BaseGUI {

    static final int INFO_SLOT = 4;
    static final int BACK_SLOT = 18;
    static final int DISABLE_SLOT = 22;

    public record Choice(
            String parameter,
            String displayName,
            Material material,
            int slot,
            List<String> description
    ) {}

    private final Object claim;
    private final String claimId;
    private final String flagName;
    private final String menuTitle;
    private final String promptSummary;
    private final List<Choice> choices;
    private final int returnPage;

    private boolean flagEnabled;
    private boolean flagExplicit = true;
    private String activeValue = "";

    public GPFlagChoiceGUI(
            GUIManager manager,
            Player player,
            Object claim,
            String claimId,
            String flagName,
            String menuTitle,
            String promptSummary,
            List<Choice> choices,
            int returnPage
    ) {
        super(manager, player, "claim-flags-choice");
        this.claim = claim;
        this.claimId = claimId;
        this.flagName = flagName;
        this.menuTitle = menuTitle;
        this.promptSummary = promptSummary;
        this.choices = List.copyOf(choices);
        this.returnPage = Math.max(0, returnPage);
        loadState();
    }

    private void loadState() {
        Optional<GPFlagsBridge.ClaimFlagState> stateOpt =
                GPFlagsBridge.getEffectiveFlagState(claim, flagName);
        if (stateOpt.isPresent()) {
            GPFlagsBridge.ClaimFlagState state = stateOpt.get();
            this.flagEnabled = state.isEnabled();
            this.flagExplicit = state.isExplicit();
            this.activeValue = state.getParameters() != null ? state.getParameters().trim() : "";
        } else {
            this.flagEnabled = false;
            this.flagExplicit = true;
            this.activeValue = "";
        }
    }

    public String getFlagName() {
        return flagName;
    }

    @Override
    public Inventory createInventory() {
        inventory = Mini.createInventory(null, 27, colorize(menuTitle));
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        fillEmpty(createFiller());

        inventory.setItem(INFO_SLOT, createInfoItem());
        for (Choice choice : choices) {
            inventory.setItem(choice.slot(), createChoiceItem(choice));
        }
        inventory.setItem(BACK_SLOT, createItem(Material.ARROW, "&c&lBack",
                List.of("&7Return to Claim Flags")));
        inventory.setItem(DISABLE_SLOT, createDisableItem());
    }

    private ItemStack createInfoItem() {
        String claimName = plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim " + claimId);
        List<String> lore = new ArrayList<>();
        lore.add("&7Claim: &f" + claimName);
        if (flagEnabled && !activeValue.isEmpty()) {
            lore.add("&7Current: &a" + formatLabel(activeValue));
        } else if (flagEnabled) {
            lore.add("&7Current: &aEnabled");
        } else {
            lore.add("&7Current: &cDisabled");
        }
        if (!flagExplicit) {
            lore.add("&7(inherited from world/server)");
        }
        lore.add("");
        lore.add("&7" + promptSummary);
        return createItem(Material.BOOK, "&b&l" + flagName + " Flag", lore);
    }

    private ItemStack createChoiceItem(Choice choice) {
        boolean selected = flagEnabled && choice.parameter().equalsIgnoreCase(activeValue);
        String title = selected ? "&a&l✓ " + choice.displayName() : "&e&l" + choice.displayName();

        List<String> lore = new ArrayList<>();
        lore.add(selected ? "&aCurrently active" : "&7Not selected");
        if (!choice.description().isEmpty()) {
            lore.add("");
            lore.addAll(choice.description());
        }
        lore.add("");
        if (selected && flagExplicit) {
            lore.add("&7Already selected");
        } else if (selected) {
            lore.add("&eClick to set explicitly on this claim");
        } else {
            lore.add("&eClick to set " + choice.displayName());
        }

        ItemStack item = createItem(choice.material(), title, lore, selected);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createDisableItem() {
        List<String> lore = new ArrayList<>();
        if (flagEnabled) {
            lore.add("&7Disables the " + flagName + " flag");
            lore.add("&7for this claim.");
            lore.add("");
            lore.add("&cClick to disable");
            return createItem(Material.BARRIER, "&c&lDisable " + flagName, lore);
        }
        lore.add("&7" + flagName + " is currently disabled");
        lore.add("&7for this claim.");
        return createItem(Material.GRAY_DYE, "&7&l" + flagName + " Disabled", lore);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) return;

        playClickSound();

        if (slot == BACK_SLOT) {
            manager.openGUI(player, new ClaimFlagsGUI(manager, player, claim, claimId, returnPage));
            return;
        }

        if (slot == DISABLE_SLOT) {
            if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) {
                return;
            }
            if (!flagEnabled) {
                return;
            }
            if (GPFlagsBridge.setFlagState(claimId, flagName, false, player)) {
                plugin.getMessages().send(player, "flags.toggled",
                        "{flag}", flagName,
                        "{state}", "disabled");
                refresh();
            } else {
                plugin.getMessages().send(player, "flags.toggle-failed",
                        "{flag}", flagName);
            }
            return;
        }

        for (Choice choice : choices) {
            if (choice.slot() == slot) {
                if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) {
                    return;
                }
                if (GPFlagsBridge.setFlagState(claimId, flagName, true, player, choice.parameter())) {
                    plugin.getMessages().send(player, "flags.toggled",
                            "{flag}", flagName,
                            "{state}", "enabled (" + choice.displayName() + ")");
                    refresh();
                } else {
                    plugin.getMessages().send(player, "flags.toggle-failed",
                            "{flag}", flagName);
                }
                return;
            }
        }
    }

    private void refresh() {
        loadState();
        inventory.clear();
        populateInventory();
    }

    static String formatLabel(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
