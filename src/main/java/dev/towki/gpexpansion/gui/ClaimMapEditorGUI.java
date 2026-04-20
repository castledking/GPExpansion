package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.ClaimMapEditorBridge;
import dev.towki.gpexpansion.gp.GPBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cell-based claim map editor.
 * Left-click unclaimed cells to claim, and right-click or Q selected-claim cells to unclaim.
 */
public final class ClaimMapEditorGUI extends BaseGUI {

    private static final int GRID_SIZE = 45;
    private static final int GRID_WIDTH = 9;
    private static final int GRID_CENTER_COLUMN = 4;
    private static final int GRID_CENTER_ROW = 2;
    private static final int PAN_LIMIT_BLOCKS = 200;
    private static final int[] ZOOM_LEVELS = {1, 5, 10, 20, 50, 100, 200};
    private static final Map<UUID, ClaimEditMode> MODE_PREFERENCES = new ConcurrentHashMap<>();
    private static final Map<UUID, ViewportPreference> VIEWPORT_PREFERENCES = new ConcurrentHashMap<>();

    private enum ClaimEditMode {
        BASIC,
        SHAPED
    }

    private record ViewportPreference(
            String claimId,
            int centerX,
            int centerZ,
            int zoomIndex
    ) {
    }

    private final GPBridge gp;
    private final ClaimMapEditorBridge mapBridge;

    private final Object initialClaim;
    private final String initialClaimId;

    private Object selectedClaim;
    private String selectedClaimId;

    private final World world;
    private int originX;
    private int originZ;
    private int centerX;
    private int centerZ;
    private int zoomIndex = 2; // 10x10 default
    private boolean paneView = true;

    private int backSlot = 45;
    private int summarySlot = 46;
    private int modeSlot = 47;
    private int zoomSlot = 49;
    private int iconSlot = 53;
    private ClaimEditMode editMode;

    public ClaimMapEditorGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "claim-map-editor");
        this.gp = new GPBridge();
        this.mapBridge = new ClaimMapEditorBridge(gp);
        this.initialClaim = claim;
        this.initialClaimId = claimId;
        this.selectedClaim = claim;
        this.selectedClaimId = claimId;

        if (config != null) {
            backSlot = config.getInt("items.back.slot", backSlot);
            summarySlot = config.getInt("items.summary.slot", summarySlot);
            modeSlot = config.getInt("items.mode.slot", modeSlot);
            zoomSlot = config.getInt("items.zoom.slot", zoomSlot);
            iconSlot = config.getInt("items.icon.slot", iconSlot);
        }

        editMode = resolveInitialEditMode();

        World resolvedWorld = player.getWorld();
        if (claim != null) {
            Optional<String> worldName = gp.getClaimWorld(claim);
            if (worldName.isPresent()) {
                World candidate = Bukkit.getWorld(worldName.get());
                if (candidate != null) {
                    resolvedWorld = candidate;
                }
            }
            GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
            if (corners != null) {
                int zoom = currentZoomLevel();
                centerX = alignCenterAxisToClaimGrid(corners.x1, corners.x2, zoom);
                centerZ = alignCenterAxisToClaimGrid(corners.z1, corners.z2, zoom);
            } else {
                centerX = player.getLocation().getBlockX();
                centerZ = player.getLocation().getBlockZ();
            }
        } else {
            centerX = player.getLocation().getBlockX();
            centerZ = player.getLocation().getBlockZ();
        }
        world = resolvedWorld;

        ViewportPreference preference = VIEWPORT_PREFERENCES.get(player.getUniqueId());
        if (preference != null
                && initialClaimId != null
                && initialClaimId.equals(preference.claimId())
                && preference.zoomIndex() >= 0
                && preference.zoomIndex() < ZOOM_LEVELS.length) {
            zoomIndex = preference.zoomIndex();
            centerX = preference.centerX();
            centerZ = preference.centerZ();
        }

        originX = centerX;
        originZ = centerZ;
    }

    @Override
    public Inventory createInventory() {
        String title = getString("title", "&6&lClaim Map Editor");
        if (selectedClaimId != null) {
            title = title.replace("{id}", selectedClaimId);
        }
        inventory = createBaseInventoryWithTitle(title, 54);
        GUIStateTracker.saveState(player, GUIStateTracker.GUIType.CLAIM_MAP_EDITOR, null, null, zoomIndex, selectedClaimId);
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        enforceModeConstraint();
        inventory.clear();

        for (int slot = 0; slot < GRID_SIZE; slot++) {
            inventory.setItem(slot, createCellItem(slot));
        }

        for (int slot = GRID_SIZE; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, createFiller());
        }

        inventory.setItem(backSlot, createBackItem());
        inventory.setItem(summarySlot, createSummaryItem());
        inventory.setItem(modeSlot, createModeItem());
        inventory.setItem(zoomSlot, createZoomItem());
        inventory.setItem(iconSlot, createIconItem());

        saveViewportPreference();
    }

    private ItemStack createCellItem(int slot) {
        ClaimMapEditorBridge.CellSelection selection = selectionForSlot(slot);
        int cellArea = selection.blockArea();
        int selectedCoverage = selectedClaim == null
                ? 0
                : gp.getClaimCoverageInCell(
                        selectedClaim,
                        selection.world(),
                        selection.minX(),
                        selection.maxX(),
                        selection.minZ(),
                        selection.maxZ()
                );
        boolean fullSelectedTile = selectedClaim != null && selectedCoverage >= cellArea;
        Object claimAtCell = fullSelectedTile
                ? selectedClaim
                : gp.getDominantClaimInCell(
                        selection.world(),
                        selection.minX(),
                        selection.maxX(),
                        selection.minZ(),
                        selection.maxZ(),
                        player
                ).orElse(null);
        boolean selectedDominatesCell = fullSelectedTile || isSameClaim(claimAtCell, selectedClaim);
        boolean partialSelectedTile = selectedClaim != null
                && selectedCoverage > 0
                && selectedCoverage < cellArea
                && (claimAtCell == null || selectedDominatesCell);
        boolean selected = selectedDominatesCell
                || (claimAtCell == null && selectedCoverage > 0);
        Object displayClaim = claimAtCell != null ? claimAtCell : (selected ? selectedClaim : null);
        boolean owned = displayClaim != null && gp.isOwner(displayClaim, player.getUniqueId());

        Material material;
        if (partialSelectedTile && paneView) {
            material = Material.ORANGE_STAINED_GLASS_PANE;
        } else if (claimAtCell == null && !selected) {
            material = Material.GRAY_STAINED_GLASS_PANE;
        } else if (paneView) {
            if (selected) {
                material = Material.LIME_STAINED_GLASS_PANE;
            } else {
                material = owned ? Material.GREEN_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            }
        } else {
            material = resolveClaimMaterial(displayClaim, owned);
        }

        String name;
        List<String> lore = new ArrayList<>();
        lore.add("&7Cell center: &f" + selection.centerX() + ", " + selection.centerZ());
        lore.add("&7Cell size: &f" + currentZoomLevel() + "x" + currentZoomLevel());
        lore.add("");

        if (partialSelectedTile) {
            name = "&6Partial Claim Tile";
            lore.add("&7Claim ID: &f" + (selectedClaimId != null ? selectedClaimId : "?"));
            lore.add("&7Covered: &f" + selectedCoverage + " / " + cellArea + " blocks");
            lore.add("&7This zoom tile is only partially filled");
            lore.add("&7by your selected claim.");
            lore.add("");
            lore.add("&eLeft click to fill the remaining area.");
            lore.add("&eRight click or Q to unclaim the covered portion.");
        } else if (claimAtCell == null && !selected) {
            name = "&7Unclaimed Cell";
            lore.add("&eLeft click to claim this cell.");
            if (editMode == ClaimEditMode.BASIC) {
                lore.add("&8Basic mode: creates detached square claims.");
                lore.add("&8GP minimum claim size/area still applies.");
            } else {
                if (selectedClaim == null) {
                    lore.add("&8No claim selected: this creates a");
                    lore.add("&8detached square claim.");
                } else {
                    lore.add("&8If adjacent: creates a shaped nib");
                    lore.add("&8on the touched boundary segment.");
                    lore.add("&8If not adjacent: creates a detached square claim.");
                }
            }
        } else {
            String claimId = gp.getClaimId(displayClaim).orElse("?");
            name = selected ? "&6Selected Claim Cell" : "&cClaimed Cell";
            lore.add("&7Claim ID: &f" + claimId);
            if (owned) {
                lore.add("&aOwned by you.");
                lore.add("&eLeft click to select this claim.");
            } else {
                lore.add("&cOwned by another player.");
            }
            if (selected) {
                lore.add("&eRight click or Q to unclaim from selected claim.");
            }
        }

        return createItem(material, name, lore, selected);
    }

    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to resize controls"));
    }

    private ItemStack createSummaryItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7World: &f" + world.getName());
        lore.add("&7Center: &f" + centerX + ", " + centerZ);
        lore.add("&7Zoom: &f" + currentZoomLevel() + "x" + currentZoomLevel());
        lore.add("&7Mode: " + (editMode == ClaimEditMode.BASIC ? "&eBasic" : "&dShaped"));
        lore.add("&7Pan limit: &f" + PAN_LIMIT_BLOCKS + " blocks");
        lore.add("");
        if (selectedClaimId != null) {
            lore.add("&7Selected claim: &f#" + selectedClaimId);
        } else {
            lore.add("&7Selected claim: &8None");
        }
        lore.add("&7Icon mode: " + (paneView ? "&fGlass panes" : "&fClaim icons"));
        return createItem(Material.MAP, "&6&lClaim Map Editor", lore);
    }

    private ItemStack createModeItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Mode: &eBasic &7| &dShaped");
        lore.add("&7Current: " + (editMode == ClaimEditMode.BASIC ? "&eBasic" : "&dShaped"));
        lore.add("");
        if (!gp.isShapedClaimsAllowed()) {
            lore.add("&8AllowShapedClaims is disabled.");
            lore.add("&8Basic mode is forced.");
        } else {
            lore.add("&eClick to toggle between them");
        }
        return createItem(Material.COMPARATOR, "&6Claim Mode", lore);
    }

    private ItemStack createZoomItem() {
        int zoom = currentZoomLevel();
        List<String> lore = new ArrayList<>();
        lore.add("&7Current zoom: &f" + zoom + "x" + zoom);
        lore.add("");
        lore.add("&eLeft click: &7next zoom");
        lore.add("&eRight click: &7previous zoom");
        lore.add("");
        lore.add("&7Pan (works on this icon or any map tile):");
        lore.add("&e1-key: &7pan up   &e2-key: &7pan down");
        lore.add("&e3-key: &7pan left &e4-key: &7pan right");
        return createItem(Material.SPYGLASS, "&b&lZoom / Pan", lore);
    }

    private ItemStack createIconItem() {
        Material iconMaterial = Material.GREEN_STAINED_GLASS_PANE;
        List<Material> recentIcons = List.of();
        if (selectedClaimId != null) {
            recentIcons = plugin.getClaimDataStore().getIconHistory(selectedClaimId);
            iconMaterial = plugin.getClaimDataStore()
                    .getIcon(selectedClaimId)
                    .orElse(Material.GREEN_STAINED_GLASS_PANE);
        }

        List<String> lore = new ArrayList<>();
        lore.add("&7Drop an item to set|reset the claim icon");
        lore.add("&7this the same as /claim icon and is used to");
        lore.add("&7distinguish between your claims and others.");
        lore.add("");
        if (selectedClaimId != null) {
            lore.add("&7Selected claim: &f#" + selectedClaimId);
        } else {
            lore.add("&7Selected claim: &8None");
        }
        lore.add("&7Recent icons: &f" + recentIcons.size() + "&7/5");
        if (!recentIcons.isEmpty()) {
            lore.add("&7Cycle order: &f" + recentIcons.stream()
                    .map(Material::name)
                    .limit(5)
                    .reduce((left, right) -> left + "&7, &f" + right)
                    .orElse("None"));
        }
        lore.add("&7View mode: " + (paneView ? "&fGlass panes" : "&fClaim icons"));
        lore.add("");
        lore.add("&eLeft click: &7cycle stored icons");
        lore.add("&eRight click: &7toggle pane/icon view");
        lore.add("&eDrop/Q: &7remove current icon");
        lore.add("&eDrop item / hotbar key: &7add icon to recent list");
        return createItem(iconMaterial, "&e&lClaim Icon", lore);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        // Universal pan: hotbar keys 1/2/3/4 pan the map on the spyglass OR any grid
        // tile. Other bottom-row controls (back/mode/summary/icon) are intentionally
        // excluded so they don't hijack the key press.
        boolean panZone = slot == zoomSlot || (slot >= 0 && slot < GRID_SIZE);
        if (panZone && tryHandlePanHotkey(event)) {
            populateInventory();
            return;
        }

        if (slot == backSlot) {
            openBack();
            return;
        }
        if (slot == zoomSlot) {
            handleZoomClick(event);
            populateInventory();
            return;
        }
        if (slot == modeSlot) {
            handleModeClick();
            populateInventory();
            return;
        }
        if (slot == iconSlot) {
            handleIconClick(event);
            populateInventory();
            return;
        }
        if (slot >= 0 && slot < GRID_SIZE) {
            handleGridClick(slot, event);
            if (manager.getOpenGUI(player) == this) {
                populateInventory();
            }
        }
    }

    private void openBack() {
        String backClaimId = selectedClaimId != null ? selectedClaimId : initialClaimId;
        if (backClaimId != null) {
            Optional<Object> backClaim = gp.findClaimById(backClaimId);
            if (backClaim.isPresent()) {
                manager.openClaimResize(player, backClaim.get(), backClaimId);
                return;
            }
        }
        if (selectedClaim != null && selectedClaimId != null) {
            manager.openClaimResize(player, selectedClaim, selectedClaimId);
            return;
        }
        player.closeInventory();
    }

    private void handleZoomClick(InventoryClickEvent event) {
        // Pan hotkeys (1/2/3/4) are handled globally in handleClick; this method is
        // only reached for spyglass clicks that are NOT a pan key, so it only deals
        // with zoom-in / zoom-out.
        if (isLeftClick(event)) {
            zoomIndex = (zoomIndex + 1) % ZOOM_LEVELS.length;
            snapViewportToCurrentZoom();
            return;
        }
        if (isRightClick(event)) {
            zoomIndex = (zoomIndex - 1 + ZOOM_LEVELS.length) % ZOOM_LEVELS.length;
            snapViewportToCurrentZoom();
        }
    }

    /**
     * If the click is hotbar key 1/2/3/4, pan the map and return true.
     * 1 = up, 2 = down, 3 = left, 4 = right. Works regardless of which slot the
     * player is hovering; callers decide where the hotkey is active.
     */
    private boolean tryHandlePanHotkey(InventoryClickEvent event) {
        if (event.getClick() != ClickType.NUMBER_KEY) {
            return false;
        }
        int zoom = currentZoomLevel();
        switch (event.getHotbarButton()) {
            case 0 -> { pan(0, -zoom); return true; }   // key 1: up
            case 1 -> { pan(0, zoom); return true; }    // key 2: down
            case 2 -> { pan(-zoom, 0); return true; }   // key 3: left
            case 3 -> { pan(zoom, 0); return true; }    // key 4: right
            default -> { return false; }
        }
    }

    private void handleIconClick(InventoryClickEvent event) {
        if (selectedClaim == null || selectedClaimId == null) {
            return;
        }
        if (!canManageSelectedClaim()) {
            plugin.getMessages().send(player, "general.no-permission");
            return;
        }

        ClickType click = event.getClick();
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP || isShiftRightClick(event)) {
            Material removed = plugin.getClaimDataStore().getIcon(selectedClaimId).orElse(null);
            plugin.getClaimDataStore().removeCurrentIcon(selectedClaimId);
            plugin.getClaimDataStore().save();
            if (removed == null) {
                player.sendMessage(colorize("&eThis claim has no custom icon to remove."));
            } else {
                player.sendMessage(colorize("&aRemoved claim icon &f" + removed.name() + "&a."));
            }
            return;
        }

        Material offered = offeredMaterial(event);
        if (offered != null && offered != Material.AIR) {
            plugin.getClaimDataStore().setIcon(selectedClaimId, offered);
            plugin.getClaimDataStore().save();
            player.sendMessage(colorize("&aClaim icon set to &f" + offered.name() + "&a."));
            return;
        }

        if (isLeftClick(event)) {
            List<Material> recentIcons = plugin.getClaimDataStore().getIconHistory(selectedClaimId);
            if (recentIcons.size() <= 1) {
                if (recentIcons.isEmpty()) {
                    player.sendMessage(colorize("&eThis claim has no stored icons yet."));
                } else {
                    player.sendMessage(colorize("&eOnly one claim icon is stored right now."));
                }
                return;
            }

            Material icon = plugin.getClaimDataStore().cycleIcon(selectedClaimId).orElse(null);
            plugin.getClaimDataStore().save();
            if (icon != null) {
                player.sendMessage(colorize("&aClaim icon switched to &f" + icon.name() + "&a."));
            }
            return;
        }

        if (isRightClick(event)) {
            paneView = !paneView;
            player.sendMessage(colorize(paneView
                    ? "&eMap view switched to glass panes."
                    : "&eMap view switched to claim icons."));
        }
    }

    private void handleModeClick() {
        if (!gp.isShapedClaimsAllowed()) {
            editMode = ClaimEditMode.BASIC;
            player.sendMessage(colorize("&7Claim Map Editor is forced to &eBasic &7mode while AllowShapedClaims is disabled."));
            return;
        }

        editMode = editMode == ClaimEditMode.BASIC ? ClaimEditMode.SHAPED : ClaimEditMode.BASIC;
        MODE_PREFERENCES.put(player.getUniqueId(), editMode);
        player.sendMessage(colorize(editMode == ClaimEditMode.BASIC
                ? "&eClaim mode set to Basic."
                : "&dClaim mode set to Shaped."));
    }

    private void handleGridClick(int slot, InventoryClickEvent event) {
        ClaimMapEditorBridge.CellSelection selection = selectionForSlot(slot);
        Object claimAtCell = gp.getDominantClaimInCell(
                selection.world(),
                selection.minX(),
                    selection.maxX(),
                    selection.minZ(),
                    selection.maxZ(),
                    player
            ).orElse(null);
        int selectedCoverage = selectedClaim == null
                ? 0
                : gp.getClaimCoverageInCell(
                        selectedClaim,
                        selection.world(),
                        selection.minX(),
                        selection.maxX(),
                        selection.minZ(),
                        selection.maxZ()
                );
        boolean partialSelectedTile = selectedClaim != null
                && selectedCoverage > 0
                && selectedCoverage < selection.blockArea()
                && (claimAtCell == null || isSameClaim(claimAtCell, selectedClaim));
        String selectedBeforeId = selectedClaim == null
                ? null
                : gp.getClaimId(selectedClaim).orElse(null);

        // Select claim focus quickly by clicking claimed cells.
        ClaimMapEditorBridge.MapEditResult result = null;
        if (partialSelectedTile && isLeftClick(event)) {
            result = mapBridge.commitClaimCells(player, selection, currentZoomLevel(), selectedClaim, bridgeEditMode());
        } else if (selectedClaim != null
                && selectedCoverage > 0
                && (claimAtCell == null || isSameClaim(claimAtCell, selectedClaim))
                && isUnclaimClick(event)) {
            result = mapBridge.commitUnclaimCells(player, selection, currentZoomLevel(), selectedClaim, bridgeEditMode());
        } else if (claimAtCell != null
                && canSelectClaim(claimAtCell)
                && (isShiftLeftClick(event) || isShiftRightClick(event))) {
            setSelectedClaim(claimAtCell);
            player.sendMessage(colorize("&eSelected claim &f#" + selectedClaimId + "&e."));
            return;
        } else if (claimAtCell == null && isLeftClick(event)) {
            result = mapBridge.commitClaimCells(player, selection, currentZoomLevel(), selectedClaim, bridgeEditMode());
        }

        if (result == null) {
            return;
        }

        player.sendMessage(colorize(result.message));
        if (!result.success
                && result.failureReason == ClaimMapEditorBridge.FailureReason.SUBDIVISIONS_REQUIRE_CONFIRM
                && selectedClaim != null
                && selectedClaimId != null) {
            manager.openClaimOptions(player, selectedClaim, selectedClaimId, true);
            return;
        }
        if (result.success && result.claim != null) {
            String resultId = gp.getClaimId(result.claim).orElse(null);
            boolean detachedCreatedWhileShaped = isLeftClick(event)
                    && claimAtCell == null
                    && editMode == ClaimEditMode.SHAPED
                    && selectedBeforeId != null
                    && resultId != null
                    && !selectedBeforeId.equals(resultId);
            if (!detachedCreatedWhileShaped) {
                setSelectedClaim(result.claim);
            }
        } else if (result.success) {
            gp.getDominantClaimInCell(
                    selection.world(),
                    selection.minX(),
                    selection.maxX(),
                    selection.minZ(),
                    selection.maxZ(),
                    player
            ).ifPresent(this::setSelectedClaim);
        }
        if (result.success) {
            refreshSelectedClaimById();
        }

        if (result.success) {
            Object visualizeClaim = result.claim != null ? result.claim : selectedClaim;
            if (visualizeClaim != null) {
                gp.refreshClaimVisualization(player, visualizeClaim);
            }
        }
    }

    private void setSelectedClaim(Object claim) {
        selectedClaim = claim;
        selectedClaimId = gp.getClaimId(claim).orElse(selectedClaimId);
    }

    private void refreshSelectedClaimById() {
        if (selectedClaimId == null || selectedClaimId.isBlank()) {
            selectedClaim = null;
            return;
        }
        Optional<Object> refreshed = gp.findClaimById(selectedClaimId);
        if (refreshed.isPresent()) {
            setSelectedClaim(refreshed.get());
            return;
        }

        selectedClaim = null;
        selectedClaimId = null;
    }

    private ClaimEditMode resolveInitialEditMode() {
        ClaimEditMode preferred = MODE_PREFERENCES.get(player.getUniqueId());
        if (!gp.isShapedClaimsAllowed()) {
            return ClaimEditMode.BASIC;
        }
        return preferred != null ? preferred : ClaimEditMode.SHAPED;
    }

    private void enforceModeConstraint() {
        if (!gp.isShapedClaimsAllowed()) {
            editMode = ClaimEditMode.BASIC;
        }
    }

    private ClaimMapEditorBridge.EditMode bridgeEditMode() {
        return editMode == ClaimEditMode.BASIC
                ? ClaimMapEditorBridge.EditMode.BASIC
                : ClaimMapEditorBridge.EditMode.SHAPED;
    }

    private boolean canSelectClaim(Object claim) {
        return gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin");
    }

    private boolean canManageSelectedClaim() {
        return selectedClaim != null
                && (gp.isOwner(selectedClaim, player.getUniqueId()) || player.hasPermission("griefprevention.admin"));
    }

    private boolean isUnclaimClick(InventoryClickEvent event) {
        ClickType click = event.getClick();
        return isRightClick(event) || click == ClickType.DROP || click == ClickType.CONTROL_DROP;
    }

    private void pan(int deltaX, int deltaZ) {
        int nextX = centerX + deltaX;
        int nextZ = centerZ + deltaZ;

        long dx = nextX - originX;
        long dz = nextZ - originZ;
        if (dx * dx + dz * dz > (long) PAN_LIMIT_BLOCKS * PAN_LIMIT_BLOCKS) {
            return;
        }

        centerX = nextX;
        centerZ = nextZ;
    }

    private ClaimMapEditorBridge.CellSelection selectionForSlot(int slot) {
        int row = slot / GRID_WIDTH;
        int col = slot % GRID_WIDTH;

        int zoom = currentZoomLevel();
        int offsetCellsX = col - GRID_CENTER_COLUMN;
        int offsetCellsZ = row - GRID_CENTER_ROW;

        int cellCenterX = centerX + (offsetCellsX * zoom);
        int cellCenterZ = centerZ + (offsetCellsZ * zoom);

        int halfLower = (zoom - 1) / 2;
        int minX = cellCenterX - halfLower;
        int minZ = cellCenterZ - halfLower;
        int maxX = minX + zoom - 1;
        int maxZ = minZ + zoom - 1;

        return new ClaimMapEditorBridge.CellSelection(world, minX, maxX, minZ, maxZ, cellCenterX, cellCenterZ);
    }

    private int currentZoomLevel() {
        return ZOOM_LEVELS[zoomIndex];
    }

    private void snapViewportToCurrentZoom() {
        int zoom = currentZoomLevel();

        if (selectedClaim != null) {
            GPBridge.ClaimCorners corners = gp.getClaimCorners(selectedClaim).orElse(null);
            if (corners != null) {
                centerX = alignCenterAxisToClaimGrid(corners.x1, corners.x2, zoom);
                centerZ = alignCenterAxisToClaimGrid(corners.z1, corners.z2, zoom);
                return;
            }
        }

        // Keep free-pan movement stable by snapping to the nearest center that
        // still aligns this zoom level's cell grid.
        centerX = quantizeCenterForZoom(centerX, zoom);
        centerZ = quantizeCenterForZoom(centerZ, zoom);
    }

    private int quantizeCenterForZoom(int value, int zoom) {
        int halfLower = (zoom - 1) / 2;
        long bucket = Math.round((value - halfLower) / (double) zoom);
        return (int) (bucket * zoom + halfLower);
    }

    private int alignCenterAxisToClaimGrid(int min, int max, int zoom) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        int midpoint = (low + high) / 2;
        int halfLower = (zoom - 1) / 2;
        int targetResidue = Math.floorMod(low + halfLower, zoom);
        long bucket = Math.round((midpoint - targetResidue) / (double) zoom);
        int snapped = (int) (bucket * zoom + targetResidue);
        return snapped;
    }

    private void saveViewportPreference() {
        String keyClaimId = selectedClaimId != null ? selectedClaimId : initialClaimId;
        if (keyClaimId == null) {
            return;
        }
        VIEWPORT_PREFERENCES.put(
                player.getUniqueId(),
                new ViewportPreference(keyClaimId, centerX, centerZ, zoomIndex)
        );
    }

    private Material offeredMaterial(InventoryClickEvent event) {
        return switch (event.getClick()) {
            case NUMBER_KEY -> {
                int hotbar = event.getHotbarButton();
                if (hotbar >= 0) {
                    yield player.getInventory().getItem(hotbar) != null
                            ? player.getInventory().getItem(hotbar).getType()
                            : null;
                }
                yield null;
            }
            case SWAP_OFFHAND -> player.getInventory().getItemInOffHand().getType();
            default -> event.getCursor() != null ? event.getCursor().getType() : null;
        };
    }

    private boolean isSameClaim(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        String idA = gp.getClaimId(a).orElse(null);
        String idB = gp.getClaimId(b).orElse(null);
        if (idA != null && idB != null) {
            return idA.equals(idB);
        }
        return a == b;
    }

    private Material resolveClaimMaterial(Object claim, boolean ownedByPlayer) {
        String claimId = gp.getClaimId(claim).orElse(null);
        if (claimId != null) {
            Material icon = plugin.getClaimDataStore().getIcon(claimId).orElse(null);
            if (icon != null && icon != Material.AIR) {
                return icon;
            }
        }
        return ownedByPlayer ? Material.GREEN_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
    }
}
