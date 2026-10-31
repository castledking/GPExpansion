package codes.castled.gpexpansion.gui;
import codes.castled.gpexpansion.util.Mini;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import codes.castled.gpexpansion.gp.GPBridge;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Front-facing action hub for a single claim.
 */
public class ClaimOptionsGUI extends BaseGUI {

    private final Object claim;
    private final String claimId;
    private final GPBridge gp;
    private boolean confirmAbandon;

    private int summarySlot = 4;
    private int teleportSlot = 19;
    private int resizeSlot = 21;
    private int trustSlot = 23;
    private int globalSettingsSlot = 25;
    private int abandonSlot = 36;
    private int viewChildrenSlot = 38;
    private int claimFlagsSlot = 40;
    private int transferSlot = 42;
    private int backSlot = 44;

    public ClaimOptionsGUI(GUIManager manager, Player player, Object claim, String claimId) {
        this(manager, player, claim, claimId, false);
    }

    public ClaimOptionsGUI(GUIManager manager, Player player, Object claim, String claimId, boolean armAbandonConfirm) {
        super(manager, player, "claim-options");
        this.claim = claim;
        this.claimId = claimId;
        this.gp = new GPBridge();
        this.confirmAbandon = armAbandonConfirm;

        if (config != null) {
            summarySlot = config.getInt("items.summary.slot", summarySlot);
            teleportSlot = config.getInt("items.teleport.slot", teleportSlot);
            resizeSlot = config.getInt("items.resize.slot", resizeSlot);
            trustSlot = config.getInt("items.trust.slot", trustSlot);
            abandonSlot = config.getInt("items.abandon.slot", abandonSlot);
            viewChildrenSlot = config.getInt("items.view-children.slot", viewChildrenSlot);
            claimFlagsSlot = config.getInt("items.claim-flags.slot", claimFlagsSlot);
            globalSettingsSlot = config.getInt("items.global-settings.slot", globalSettingsSlot);
            transferSlot = config.getInt("items.transfer.slot", transferSlot);
            backSlot = config.getInt("items.back.slot", backSlot);
        }
    }

    @Override
    public Inventory createInventory() {
        String title = getString("title", "&6&lClaim Options - #{id}").replace("{id}", claimId);
        // Fixed 45-slot layout required for item positions; config size override is ignored
        inventory = Mini.createInventory(null, 45, colorize(title));
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        inventory.clear();
        fillEmpty(createFiller());

        inventory.setItem(summarySlot, createSummaryItem());
        inventory.setItem(teleportSlot, createTeleportItem());
        inventory.setItem(resizeSlot, createResizeItem());
        inventory.setItem(trustSlot, createTrustItem());
        inventory.setItem(abandonSlot, createAbandonItem());
        inventory.setItem(viewChildrenSlot, createViewChildrenItem());
        inventory.setItem(claimFlagsSlot, createClaimFlagsItem());
        inventory.setItem(globalSettingsSlot, createGlobalSettingsItem());
        inventory.setItem(transferSlot, createTransferItem());
        inventory.setItem(backSlot, createBackItem());
    }

    private ItemStack createSummaryItem() {
        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        String name = plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim #" + claimId);
        String ownerName = getOwnerName();
        int childCount = gp.getSubclaims(claim).size();
        String type = getClaimType();
        String location = getClaimLocation(corners);
        String dimensions = getClaimDimensions(corners);
        String area = getClaimArea(corners) + " blocks";

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{id}", claimId);
        placeholders.put("{name}", name);
        placeholders.put("{owner}", ownerName);
        placeholders.put("{type}", type);
        placeholders.put("{location}", location);
        placeholders.put("{dimensions}", dimensions);
        placeholders.put("{area}", area);
        placeholders.put("{children}", String.valueOf(childCount));

        if (config != null && config.contains("items.summary")) {
            return createItemFromConfig("items.summary", placeholders);
        }

        List<String> lore = new ArrayList<>();
        lore.add("&7Name: &f" + name);
        lore.add("&7Owner: &f" + ownerName);
        lore.add("&7Type: &f" + type);
        lore.add("&7Size: &f" + dimensions);
        lore.add("&7Area: &f" + area);
        lore.add("&7Coords: &f" + location);
        lore.add("&7Children: &6" + childCount);
        return createItem(Material.BOOK, "&6&lClaim Summary", lore);
    }

    private ItemStack createTeleportItem() {
        boolean canTeleport = player.hasPermission("griefprevention.claim.teleport");

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{teleport_status}", canTeleport ? "&aReady to teleport" : "&cMissing teleport permission");
        placeholders.put("{teleport_action}", canTeleport ? "&eClick to teleport to this claim" : "&8Missing: griefprevention.claim.teleport");

        if (config != null && config.contains("items.teleport")) {
            return createItemFromConfig("items.teleport", placeholders);
        }

        List<String> lore = new ArrayList<>();
        lore.add("&7Teleport to this claim.");
        lore.add("");
        lore.add(placeholders.get("{teleport_status}"));
        lore.add(placeholders.get("{teleport_action}"));
        return createItem(Material.ENDER_PEARL, "&b&lTeleport", lore);
    }

    private ItemStack createResizeItem() {
        boolean canManage = canManageClaim();
        boolean supported = gp.canResizeClaim(claim);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{resize_status}", !canManage
            ? "&cYou cannot resize this claim"
            : supported ? "&aResize backend available" : "&cResize unsupported on this server");
        placeholders.put("{resize_action}", !canManage
            ? "&8You must own this claim or be an admin"
            : supported ? "&eClick to open direct resize controls" : "&8This GP build does not expose resize support");

        if (config != null && config.contains("items.resize")) {
            return createItemFromConfig("items.resize", placeholders);
        }

        List<String> lore = new ArrayList<>();
        lore.add("&7Resize this claim.");
        lore.add("");
        lore.add(placeholders.get("{resize_status}"));
        lore.add(placeholders.get("{resize_action}"));
        return createItem(Material.GOLDEN_SHOVEL, "&6&lResize Claim", lore);
    }

    private ItemStack createTrustItem() {
        boolean canManage = canManageClaim();
        int childCount = gp.getSubclaims(claim).size();

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{trust_status}", canManage ? "&aOpen the trusted players editor" : "&cYou cannot manage trust here");
        placeholders.put("{trust_action}", canManage
            ? "&eClick to manage trusted and banned players"
            : "&8You must own this claim or be an admin");
        placeholders.put("{children}", String.valueOf(childCount));

        if (config != null && config.contains("items.trust")) {
            return createItemFromConfig("items.trust", placeholders);
        }

        String ownerName = getOwnerName();
        ItemStack skull = createPlayerSkull(ownerName);
        List<String> lore = new ArrayList<>();
        lore.add("&7Review trust on this claim.");
        lore.add("");
        lore.add(placeholders.get("{trust_status}"));
        lore.add(placeholders.get("{trust_action}"));
        org.bukkit.inventory.meta.ItemMeta meta = skull.getItemMeta();
        if (meta != null) {
            Mini.setDisplayName(meta, colorize("&a&lManage Trust"));
            List<net.kyori.adventure.text.Component> loreComponents = new ArrayList<>();
            for (String line : lore) {
                loreComponents.add(colorize(line));
            }
            Mini.setLore(meta, loreComponents);
            skull.setItemMeta(meta);
        }
        return skull;
    }

    private ItemStack createAbandonItem() {
        boolean canManage = canManageClaim();
        String status = confirmAbandon ? "&cClick again to abandon this claim" : "&7Abandon this claim permanently.";
        String action = !canManage
            ? "&8You must own this claim or be an admin"
            : confirmAbandon ? "&4This cannot be undone" : "&eClick once to arm, click again to confirm";

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{abandon_status}", status);
        placeholders.put("{abandon_action}", action);

        ItemStack item;
        if (config != null && config.contains("items.abandon")) {
            item = createItemFromConfig("items.abandon", placeholders);
        } else {
            List<String> lore = new ArrayList<>();
            lore.add(status);
            lore.add("");
            lore.add(action);
            item = createItem(Material.CAULDRON, confirmAbandon ? "&c&lConfirm Abandon" : "&4&lAbandon Claim", lore, confirmAbandon);
        }

        // Append a subdivision warning when confirming an abandon of a claim with subclaims.
        int subcount = confirmAbandon ? gp.getSubclaims(claim).size() : 0;
        if (subcount > 0) {
            Component warning = plugin.getMessages().get("claim.abandon-subdivision-warning",
                "{count}", String.valueOf(subcount),
                "{plural}", subcount == 1 ? "" : "s");
            org.bukkit.inventory.meta.ItemMeta abandonMeta = item.getItemMeta();
            if (abandonMeta != null) {
                Mini.appendLore(abandonMeta, List.of(Component.empty(), warning));
                item.setItemMeta(abandonMeta);
            }
        }

        return item;
    }

    private ItemStack createViewChildrenItem() {
        int childCount = gp.getSubclaims(claim).size();

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{children}", String.valueOf(childCount));
        placeholders.put("{count}", String.valueOf(childCount));

        if (config != null && config.contains("items.view-children")) {
            return createItemFromConfig("items.view-children", placeholders);
        }

        List<String> lore = new ArrayList<>();
        lore.add("&7Browse subdivisions inside this claim.");
        lore.add("");
        lore.add("&7Children: &6" + childCount);
        lore.add(childCount == 0 ? "&8No subdivisions found yet" : "&eClick to view children");
        return createItem(Material.CHEST, "&6&lView Children", lore);
    }

    private ItemStack createClaimFlagsItem() {
        boolean canAccess = FlagsGUI.canAccess(plugin, player, claim, gp);
        boolean builtin = plugin.getClaimFlagManager() != null && plugin.getClaimFlagManager().isEnabled();
        int setCount = builtin ? plugin.getClaimFlagStore().countSet(claimId) : 0;

        Map<String, String> placeholders = new HashMap<>();
        if (builtin) {
            placeholders.put("{flags_status}", setCount > 0 ? "&a" + setCount + " flag" + (setCount == 1 ? "" : "s") + " set" : "&7No flags set");
            placeholders.put("{flags_action}", canAccess ? "&eClick to manage claim flags" : "&8You must own or manage this claim");
        } else {
            placeholders.put("{flags_status}", canAccess ? "&aGPFlags available" : "&cGPFlags unavailable or no access");
            placeholders.put("{flags_action}", canAccess ? "&eClick to manage claim flags" : "&8Requires GPFlags and claim access");
        }
        placeholders.put("{flags_set}", String.valueOf(setCount));

        if (config != null && config.contains("items.claim-flags")) {
            return createItemFromConfig("items.claim-flags", placeholders);
        }

        List<String> lore = new ArrayList<>();
        lore.add("&7Toggle GPFlags for this claim.");
        lore.add("");
        lore.add(placeholders.get("{flags_status}"));
        lore.add(placeholders.get("{flags_action}"));
        return createItem(Material.REPEATER, "&d&lClaim Flags", lore);
    }

    private ItemStack createGlobalSettingsItem() {
        if (config != null && config.contains("items.global-settings")) {
            return createItemFromConfig("items.global-settings", Map.of());
        }

        List<String> lore = new ArrayList<>();
        lore.add("&7Manage claim name, icon, description,");
        lore.add("&7public listing, and spawn point.");
        lore.add("");
        lore.add("&eClick to open global settings");
        return createItem(Material.ENDER_CHEST, "&6&lGlobal Settings", lore);
    }

    /**
     * Transfer Ownership is always shown. It only works on GriefPrevention3D with
     * Claims.TransferClaim.Enabled; elsewhere it explains why it is unavailable.
     */
    private ItemStack createTransferItem() {
        codes.castled.gpexpansion.transfer.ClaimTransferService transfers = plugin.getClaimTransferService();
        codes.castled.gpexpansion.transfer.ClaimTransferService.Availability availability = transfers.availability();
        String state;
        if (availability == codes.castled.gpexpansion.transfer.ClaimTransferService.Availability.UNSUPPORTED) {
            state = "unsupported";
        } else if (availability == codes.castled.gpexpansion.transfer.ClaimTransferService.Availability.DISABLED) {
            state = "disabled";
        } else if (gp.isSubdivision(claim)) {
            state = "subdivision";
        } else if (transfers.checkSender(player, claim).isPresent()) {
            state = "no-access";
        } else {
            state = "available";
        }
        boolean available = state.equals("available");
        String fee = transfers.formatFee(transfers.fee(player));

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{transfer_status}", getString("items.transfer.states." + state + ".status",
            defaultTransferStatus(state)).replace("{fee}", fee));
        placeholders.put("{transfer_action}", getString("items.transfer.states." + state + ".action",
            defaultTransferAction(state)).replace("{fee}", fee));
        placeholders.put("{fee}", fee);

        String materialName = getString("items.transfer.material", "TRIAL_KEY");
        if (!available) {
            String unavailableMaterial = getString("items.transfer.unavailable-material", "");
            if (!unavailableMaterial.isBlank()) {
                materialName = unavailableMaterial;
            }
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            // TRIAL_KEY only exists from 1.20.5; older servers get a tripwire hook.
            material = Material.matchMaterial("TRIAL_KEY") != null ? Material.matchMaterial("TRIAL_KEY") : Material.TRIPWIRE_HOOK;
        }

        String name = applyPlaceholders(getString("items.transfer.name", "&e&lTransfer Ownership"), placeholders);
        List<String> loreTemplate = getStringList("items.transfer.lore");
        if (loreTemplate.isEmpty()) {
            loreTemplate = List.of("&7Give this claim to another player.", "", "{transfer_status}", "{transfer_action}");
        }
        List<String> lore = new ArrayList<>();
        for (String line : loreTemplate) {
            lore.add(applyPlaceholders(line, placeholders));
        }
        return createItem(material, name, lore);
    }

    private static String defaultTransferStatus(String state) {
        return switch (state) {
            case "available" -> "&aCost: &f{fee}";
            case "subdivision" -> "&cOnly top-level claims can change owner";
            case "no-access" -> "&cOnly the owner can give this claim away";
            default -> "&cUnavailable";
        };
    }

    private static String defaultTransferAction(String state) {
        return switch (state) {
            case "available" -> "&eClick to choose the new owner";
            case "unsupported" -> "&8Requires GriefPrevention3D with TransferClaim enabled";
            case "disabled" -> "&8Claim transfers are turned off on this server";
            default -> "&8Transfer the parent claim instead";
        };
    }

    private void startTransfer() {
        codes.castled.gpexpansion.transfer.ClaimTransferService transfers = plugin.getClaimTransferService();
        java.util.Optional<codes.castled.gpexpansion.transfer.ClaimTransferService.Problem> problem = transfers.checkSender(player, claim);
        if (problem.isPresent()) {
            transfers.send(player, problem.get());
            return;
        }
        player.closeInventory();
        SignInputGUI.openPlayerName(plugin, player, "New owner",
            name -> {
                OfflinePlayer recipient = transfers.resolveRecipient(name);
                if (recipient == null) {
                    plugin.getMessages().send(player, "claim.transfer-player-not-found", "{player}", name);
                    manager.openClaimOptions(player, claim, claimId);
                    return;
                }
                java.util.Optional<codes.castled.gpexpansion.transfer.ClaimTransferService.Problem> recipientProblem =
                    transfers.checkTransfer(player, claim, recipient);
                if (recipientProblem.isPresent()) {
                    transfers.send(player, recipientProblem.get());
                    manager.openClaimOptions(player, claim, claimId);
                    return;
                }
                manager.openGUI(player, new ClaimTransferConfirmGUI(manager, player, claim, claimId, recipient));
            },
            () -> manager.openClaimOptions(player, claim, claimId));
    }

    private ItemStack createBackItem() {
        if (config != null && config.contains("items.back")) {
            return createItemFromConfig("items.back", Map.of());
        }
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to the previous menu"));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        if (confirmAbandon && slot != abandonSlot) {
            confirmAbandon = false;
            populateInventory();
        }

        if (slot == summarySlot) {
            return;
        }

        if (slot == teleportSlot) {
            if (player.hasPermission("griefprevention.claim.teleport")) {
                closeAndRunOnMainThread("claimtp " + claimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
            return;
        }

        if (slot == resizeSlot) {
            if (!canManageClaim()) {
                plugin.getMessages().send(player, "general.no-permission");
            } else if (!gp.canResizeClaim(claim)) {
                Mini.send(player, colorize("&cResize is not available on this GriefPrevention build."));
            } else {
                manager.openClaimResize(player, claim, claimId);
            }
            return;
        }

        if (slot == trustSlot) {
            if (!canManageClaim()) {
                plugin.getMessages().send(player, "general.no-permission");
            } else {
                manager.openClaimTrustedPlayers(player, claim, claimId);
            }
            return;
        }

        if (slot == abandonSlot) {
            if (!canManageClaim()) {
                plugin.getMessages().send(player, "general.no-permission");
            } else if (!confirmAbandon) {
                confirmAbandon = true;
                populateInventory();
            } else {
                // Top-level claims with subdivisions must be abandoned via
                // /abandontoplevelclaim so the native command deletes them all.
                String abandonBase = gp.getSubclaims(claim).isEmpty() ? "abandonclaim" : "abandontoplevelclaim";
                player.closeInventory();
                runStandaloneClaimCommand(abandonBase, claimId);
            }
            return;
        }

        if (slot == viewChildrenSlot) {
            manager.openChildrenClaims(player, claim, claimId);
            return;
        }

        if (slot == claimFlagsSlot) {
            if (FlagsGUI.canAccess(plugin, player, claim, gp)) {
                manager.openClaimFlags(player, claim, claimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
            return;
        }

        if (slot == globalSettingsSlot) {
            manager.openGlobalClaimSettings(player, claim, claimId);
            return;
        }

        if (slot == transferSlot) {
            startTransfer();
            return;
        }

        if (slot == backSlot) {
            openPreviousMenu();
        }
    }

    private void openPreviousMenu() {
        GUIStateTracker.GUIState previous = GUIStateTracker.getLastState(player.getUniqueId());
        if (previous != null && isRestorableList(previous.type)) {
            if (GUIStateTracker.restoreLastGUI(manager, player)) {
                return;
            }
        }

        if (gp.isSubdivision(claim)) {
            final boolean[] openedParent = {false};
            gp.getParentClaim(claim).ifPresent(parent -> {
                String parentId = gp.getClaimId(parent).orElse(null);
                if (parentId != null) {
                    openedParent[0] = true;
                    manager.openChildrenClaims(player, parent, parentId);
                }
            });
            if (openedParent[0]) {
                return;
            }
        }

        if (gp.isAdminClaim(claim)) {
            manager.openAdminClaims(player);
        } else if (gp.isOwner(claim, player.getUniqueId())) {
            manager.openOwnedClaims(player);
        } else if (player.hasPermission("griefprevention.admin")) {
            manager.openAllPlayerClaims(player);
        } else {
            manager.openMainMenu(player);
        }
    }

    private boolean isRestorableList(GUIStateTracker.GUIType type) {
        return type == GUIStateTracker.GUIType.MAIN_MENU
            || type == GUIStateTracker.GUIType.OWNED_CLAIMS
            || type == GUIStateTracker.GUIType.TRUSTED_CLAIMS
            || type == GUIStateTracker.GUIType.ALL_PLAYER_CLAIMS
            || type == GUIStateTracker.GUIType.ADMIN_CLAIMS
            || type == GUIStateTracker.GUIType.GLOBAL_CLAIM_LIST
            || type == GUIStateTracker.GUIType.CLAIM_MAP_EDITOR;
    }

    private boolean canManageClaim() {
        return gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin");
    }

    private String getOwnerName() {
        if (gp.isAdminClaim(claim)) return "Admin";

        UUID ownerId = gp.getClaimOwner(claim);
        if (ownerId == null) return "Unknown";

        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
        String ownerName = owner.getName();
        if (ownerName != null && !ownerName.isEmpty()) {
            return ownerName;
        }
        return ownerId.toString().substring(0, 8);
    }

    private String getClaimType() {
        if (gp.isAdminClaim(claim)) return "Admin Claim";
        if (gp.isSubdivision(claim)) {
            return gp.is3DClaim(claim) ? "3D Subdivision" : "Subdivision";
        }
        return gp.is3DClaim(claim) ? "3D Claim" : "Claim";
    }

    private int getClaimArea(GPBridge.ClaimCorners corners) {
        if (corners == null) return 0;
        int width = corners.x2 - corners.x1 + 1;
        int depth = corners.z2 - corners.z1 + 1;
        return width * depth;
    }

    private String getClaimDimensions(GPBridge.ClaimCorners corners) {
        if (corners == null) return "Unknown";

        int width = corners.x2 - corners.x1 + 1;
        int depth = corners.z2 - corners.z1 + 1;

        if (gp.is3DClaim(claim)) {
            int height = corners.y2 - corners.y1 + 1;
            return width + " x " + depth + " x " + height;
        }

        return width + " x " + depth;
    }

    private String getClaimLocation(GPBridge.ClaimCorners corners) {
        if (corners == null) return "Unknown";

        String world = gp.getClaimWorld(claim).orElse("Unknown");
        if (gp.is3DClaim(claim)) {
            return world + " @ " + corners.x1 + ", " + corners.y1 + ", " + corners.z1;
        }
        return world + " @ " + corners.x1 + ", " + corners.z1;
    }
}
