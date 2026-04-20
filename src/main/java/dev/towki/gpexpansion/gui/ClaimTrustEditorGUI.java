package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Toggle-based editor for a single player's trust on one claim.
 */
public class ClaimTrustEditorGUI extends BaseGUI {

    private final Object claim;
    private final String claimId;
    private final GPBridge gp;
    private final dev.towki.gpexpansion.storage.ClaimDataStore claimDataStore;
    private final String targetName;
    private final UUID targetId;
    private final EnumSet<GPBridge.TrustLevel> selectedLevels;

    public ClaimTrustEditorGUI(GUIManager manager, Player player, Object claim, String claimId, String targetName) {
        this(manager, player, claim, claimId, targetName, resolveUuid(targetName));
    }

    public ClaimTrustEditorGUI(GUIManager manager, Player player, Object claim, String claimId, String targetName, UUID targetId) {
        super(manager, player, "claim-trust-editor");
        this.claim = claim;
        this.claimId = claimId;
        this.gp = new GPBridge();
        this.claimDataStore = plugin.getClaimDataStore();
        this.targetName = targetName;
        this.targetId = targetId;
        this.selectedLevels = targetId != null ? gp.getTrustLevels(claim, targetId) : EnumSet.noneOf(GPBridge.TrustLevel.class);
    }

    @Override
    public Inventory createInventory() {
        String title = getString("title", "&6&lTrust Editor - {player}").replace("{player}", targetName);
        inventory = createBaseInventoryWithTitle(title, 9);
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        inventory.clear();
        fillEmpty(createFiller());

        inventory.setItem(0, createItem(Material.BARRIER, "&c&lCancel", List.of("&7Discard changes and return")));
        inventory.setItem(1, createTrustItem(
            GPBridge.TrustLevel.MANAGE,
            Material.ORANGE_STAINED_GLASS_PANE,
            "&6&lManage",
            List.of("&7Lets this player manage trust,", "&7permissions, and claim setup tasks.")
        ));
        inventory.setItem(3, createTrustItem(
            GPBridge.TrustLevel.BUILD,
            Material.YELLOW_STAINED_GLASS_PANE,
            "&e&lBuild",
            List.of("&7Lets this player place and break blocks,", "&7use containers, and interact normally.")
        ));
        inventory.setItem(5, createTrustItem(
            GPBridge.TrustLevel.CONTAINERS,
            Material.LIME_STAINED_GLASS_PANE,
            "&a&lContainers",
            List.of("&7Lets this player use chests,", "&7doors, buttons, and other inventory access.")
        ));
        inventory.setItem(7, createTrustItem(
            GPBridge.TrustLevel.ACCESS,
            Material.BLUE_STAINED_GLASS_PANE,
            "&9&lAccess",
            List.of("&7Lets this player use switches,", "&7buttons, beds, and basic interactions.")
        ));
        inventory.setItem(8, createItem(Material.LIME_WOOL, "&a&lConfirm Changes", buildConfirmLore()));
    }

    private ItemStack createTrustItem(GPBridge.TrustLevel level, Material material, String name, List<String> description) {
        List<String> lore = new ArrayList<>(description);
        lore.add("");
        lore.add(selectedLevels.contains(level) ? "&a✅ Selected" : "&8Not selected");
        lore.add("&eClick to toggle");
        return createItem(material, name, lore, selectedLevels.contains(level));
    }

    private List<String> buildConfirmLore() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Player: &f" + targetName);
        lore.add("&7Claim: &f#" + claimId);
        lore.add("");
        if (selectedLevels.isEmpty()) {
            lore.add("&cNo trust selected");
            lore.add("&7Confirming will remove all trust.");
        } else {
            lore.add("&7Selected:");
            for (GPBridge.TrustLevel level : selectedLevels) {
                lore.add(displayLine(level));
            }
        }
        lore.add("");
        lore.add("&eClick to apply");
        return lore;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        if (slot == 0) {
            manager.openClaimTrustedPlayers(player, claim, claimId);
            return;
        }
        if (slot == 1) {
            toggle(GPBridge.TrustLevel.MANAGE);
            return;
        }
        if (slot == 3) {
            toggle(GPBridge.TrustLevel.BUILD);
            return;
        }
        if (slot == 5) {
            toggle(GPBridge.TrustLevel.CONTAINERS);
            return;
        }
        if (slot == 7) {
            toggle(GPBridge.TrustLevel.ACCESS);
            return;
        }
        if (slot == 8) {
            applyChanges();
        }
    }

    private void toggle(GPBridge.TrustLevel level) {
        if (selectedLevels.contains(level)) {
            selectedLevels.remove(level);
        } else {
            selectedLevels.add(level);
        }
        populateInventory();
    }

    private void applyChanges() {
        if (targetName == null || targetName.isBlank()) {
            player.sendMessage(colorize("&cNo player name was provided."));
            return;
        }

        closeAndRunOnMainThread(() -> {
            if (targetId == null) {
                GPBridge.TrustLevel selectedLevel = selectedLevels.stream().findFirst().orElse(null);
                if (selectedLevel != null) {
                    runClaimCommand("trust", targetName, trustType(selectedLevel), claimId);
                }
                runLater(() -> manager.openClaimTrustedPlayers(player, claim, claimId), 5L);
                return;
            }

            claimDataStore.addTrustedPlayer(claimId, targetId, targetName);
            claimDataStore.save();
            runClaimCommand("untrust", targetName, claimId);
            for (GPBridge.TrustLevel level : selectedLevels) {
                runClaimCommand("trust", targetName, trustType(level), claimId);
            }

            runLater(() -> manager.openClaimTrustedPlayers(player, claim, claimId), 5L);
        });
    }

    private String displayLine(GPBridge.TrustLevel level) {
        return switch (level) {
            case MANAGE -> "&6- Manage";
            case BUILD -> "&e- Build";
            case CONTAINERS -> "&a- Containers";
            case ACCESS -> "&9- Access";
        };
    }

    private static UUID resolveUuid(String targetName) {
        if (targetName == null || targetName.isBlank()) return null;
        try {
            return UUID.fromString(targetName);
        } catch (IllegalArgumentException ignored) {
        }

        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetName);
            if (offlinePlayer.isOnline() || offlinePlayer.hasPlayedBefore()) {
                return offlinePlayer.getUniqueId();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String trustType(GPBridge.TrustLevel level) {
        return switch (level) {
            case MANAGE -> "manage";
            case BUILD -> "build";
            case CONTAINERS -> "containers";
            case ACCESS -> "access";
        };
    }
}
