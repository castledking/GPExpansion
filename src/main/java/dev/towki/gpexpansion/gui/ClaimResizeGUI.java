package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Direct resize controls for a claim.
 * Left/right clicks apply small adjustments, shift clicks apply larger ones.
 */
public class ClaimResizeGUI extends BaseGUI {

    private final Object claim;
    private final String claimId;
    private final GPBridge gp;
    private final Location openingLocation;
    private final GPBridge.ResizeDirection openingFacing;

    private int summarySlot = 22;
    private int infoSlot = 40;
    private int northSlot = 13;
    private int southSlot = 31;
    private int westSlot = 21;
    private int eastSlot = 23;
    private int upSlot = 24;
    private int downSlot = 33;
    private int backSlot = 44;

    public ClaimResizeGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "claim-resize");
        this.claim = claim;
        this.claimId = claimId;
        this.gp = new GPBridge();
        this.openingLocation = player.getLocation().clone();
        this.openingFacing = facingFromYaw(openingLocation.getYaw());

        if (config != null) {
            summarySlot = config.getInt("items.summary.slot", summarySlot);
            infoSlot = config.getInt("items.info.slot", infoSlot);
            northSlot = config.getInt("items.north.slot", northSlot);
            southSlot = config.getInt("items.south.slot", southSlot);
            westSlot = config.getInt("items.west.slot", westSlot);
            eastSlot = config.getInt("items.east.slot", eastSlot);
            upSlot = config.getInt("items.up.slot", upSlot);
            downSlot = config.getInt("items.down.slot", downSlot);
            backSlot = config.getInt("items.back.slot", backSlot);
        }
    }

    @Override
    public Inventory createInventory() {
        String title = getString("title", "&6&lResize Claim - #{id}").replace("{id}", claimId);
        inventory = createBaseInventoryWithTitle(title, 45);
        GUIStateTracker.saveState(player, GUIStateTracker.GUIType.CLAIM_RESIZE, null, null, 0, claimId);
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        inventory.clear();
        fillEmpty(createFiller());

        inventory.setItem(summarySlot, createSummaryItem());
        inventory.setItem(infoSlot, createInfoItem());
        inventory.setItem(northSlot, createDirectionItem(GPBridge.ResizeDirection.NORTH));
        inventory.setItem(southSlot, createDirectionItem(GPBridge.ResizeDirection.SOUTH));
        inventory.setItem(westSlot, createDirectionItem(GPBridge.ResizeDirection.WEST));
        inventory.setItem(eastSlot, createDirectionItem(GPBridge.ResizeDirection.EAST));
        inventory.setItem(backSlot, createBackItem());

        if (canUseVerticalResize()) {
            inventory.setItem(upSlot, createDirectionItem(GPBridge.ResizeDirection.UP));
            inventory.setItem(downSlot, createDirectionItem(GPBridge.ResizeDirection.DOWN));
        }
    }

    private ItemStack createSummaryItem() {
        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        int remaining = gp.getPlayerClaimStats(player).map(stats -> stats.remaining).orElse(0);
        boolean is3D = gp.is3DClaim(claim);
        boolean vertical = canUseVerticalResize();

        List<String> lore = new ArrayList<>();
        lore.add("&7Claim: &f#" + claimId);
        lore.add("&7Size: &f" + formatDimensions(corners));
        lore.add("&7Area: &f" + getArea(corners) + " blocks");
        if (is3D && corners != null) {
            lore.add("&7Height: &f" + (corners.y2 - corners.y1 + 1));
        }
        lore.add("&7Remaining claim blocks: &6" + remaining);
        lore.add("");
        lore.add(vertical
            ? "&aVertical resize enabled for this 3D subdivision"
            : "&8Vertical resize only appears for 3D subdivisions");
        return createItem(Material.BOOK, "&6&lResize Summary", lore);
    }

    private ItemStack createInfoItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Opened while facing: &f" + friendlyDirection(openingFacing));
        lore.add("");
        lore.add("&7This compass locks to the direction");
        lore.add("&7you were looking when the menu opened.");
        lore.add("");
        lore.add("&7Use it to map the resize buttons");
        lore.add("&7to the land around you instantly.");
        lore.add("");
        lore.add("&eClick to open Claim Map Editor.");

        ItemStack compass = createItem(Material.COMPASS, "&e&lFacing Compass", lore);
        if (compass.getItemMeta() instanceof CompassMeta meta) {
            meta.setLodestone(getCompassTarget());
            meta.setLodestoneTracked(false);
            compass.setItemMeta(meta);
        }
        return compass;
    }

    private ItemStack createDirectionItem(GPBridge.ResizeDirection direction) {
        List<String> lore = new ArrayList<>();
        boolean segmentAware = gp.usesSegmentAwareResize(claim, direction);
        if (segmentAware) {
            lore.add("&7Segment-aware shaped resize");
            lore.add("&7for this direction.");
            lore.add("&8(Uses GP shaped edge logic)");
        } else {
            GPBridge.ResizePreview preview = gp.previewResizeClaim(player, claim, direction, 0, openingLocation);
            lore.add("&7Expand limit: &a+" + preview.maxExpand);
            lore.add("&7Shrink limit: &c-" + preview.maxShrink);
        }
        lore.add("");
        lore.add("&eLeft click: &a+" + 1);
        lore.add("&eRight click: &c-" + 1);
        lore.add("&eShift left: &a+" + 10);
        lore.add("&eShift right: &c-" + 10);

        if (direction == GPBridge.ResizeDirection.UP || direction == GPBridge.ResizeDirection.DOWN) {
            lore.add("");
            lore.add("&7Only available for 3D subdivisions.");
        }

        return createItem(materialFor(direction), titleFor(direction), lore);
    }

    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to claim options"));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        if (slot == backSlot) {
            manager.openClaimOptions(player, claim, claimId);
            return;
        }

        if (slot == infoSlot) {
            manager.openClaimMapEditor(player, claim, claimId);
            return;
        }

        GPBridge.ResizeDirection direction = directionForSlot(slot);
        if (direction == null) {
            return;
        }

        int offset = offsetForClick(event);
        if (offset == 0) {
            return;
        }

        attemptResize(direction, offset);
    }

    private void attemptResize(GPBridge.ResizeDirection direction, int offset) {
        if (!canManageClaim()) {
            plugin.getMessages().send(player, "general.no-permission");
            return;
        }
        boolean segmentAware = gp.usesSegmentAwareResize(claim, direction);
        if (!segmentAware && !gp.canResizeClaim(claim)) {
            player.sendMessage(colorize("&cResize is not available on this GriefPrevention build."));
            return;
        }
        if ((direction == GPBridge.ResizeDirection.UP || direction == GPBridge.ResizeDirection.DOWN) && !canUseVerticalResize()) {
            player.sendMessage(colorize("&cVertical resize is only available for 3D subdivisions."));
            return;
        }

        if (segmentAware) {
            GPBridge.ResizePreview preview = gp.previewResizeClaim(player, claim, direction, offset, openingLocation);
            if (!preview.supported) {
                player.sendMessage(colorize("&cResize preview is not available on this GriefPrevention build."));
                return;
            }
            if (!preview.valid) {
                player.sendMessage(colorize(getResizeFailureMessage(direction, offset, preview)));
                return;
            }

            GPBridge.ResizeResult result = gp.resizeClaim(player, claim, direction, offset, openingLocation);
            if (!result.success) {
                player.sendMessage(colorize(getResizeApplyFailureMessage(result)));
                return;
            }

            String amount = (preview.clampedOffset > 0 ? "+" : "") + preview.clampedOffset;
            player.sendMessage(colorize("&aResized &e" + friendlyDirection(direction) + " &aby &f" + amount + "&a. New size: &f" + formatDimensions(result.preview.newCorners)));
            gp.refreshClaimVisualization(player, result.claim != null ? result.claim : claim);
            manager.openClaimResize(player, result.claim, claimId);
            return;
        }

        GPBridge.ResizePreview preview = gp.previewResizeClaim(player, claim, direction, offset, openingLocation);
        if (!preview.supported) {
            player.sendMessage(colorize("&cResize preview is not available on this GriefPrevention build."));
            return;
        }
        if (!preview.valid) {
            player.sendMessage(colorize(getResizeFailureMessage(direction, offset, preview)));
            return;
        }

        GPBridge.ResizeResult result = gp.resizeClaim(player, claim, direction, offset, openingLocation);
        if (!result.success) {
            player.sendMessage(colorize(getResizeApplyFailureMessage(result)));
            return;
        }

        String amount = (preview.clampedOffset > 0 ? "+" : "") + preview.clampedOffset;
        player.sendMessage(colorize("&aResized &e" + friendlyDirection(direction) + " &aby &f" + amount + "&a. New size: &f" + formatDimensions(result.preview.newCorners)));
        gp.refreshClaimVisualization(player, result.claim != null ? result.claim : claim);
        manager.openClaimResize(player, result.claim, claimId);
    }

    private String getResizeFailureMessage(GPBridge.ResizeDirection direction, int offset, GPBridge.ResizePreview preview) {
        return switch (preview.failureReason) {
            case INVALID_OFFSET_RANGE -> {
                int limit = offset > 0 ? preview.maxExpand : preview.maxShrink;
                String sign = offset > 0 ? "+" : "-";
                yield "&cOnly &f" + sign + limit + " &cis available toward &e" + friendlyDirection(direction) + "&c.";
            }
            case TOO_SMALL -> direction == GPBridge.ResizeDirection.UP || direction == GPBridge.ResizeDirection.DOWN
                ? "&cThat would make the claim too short."
                : preview.newWidth < preview.minWidth || preview.newDepth < preview.minWidth
                    ? messageString("claim.resize-too-narrow", "{min}", String.valueOf(preview.minWidth))
                    : messageString("claim.resize-too-small-area", "{min}", String.valueOf(preview.minArea));
            case NOT_ENOUGH_BLOCKS -> {
                int needed = Math.max(0, preview.blockDelta - preview.remainingClaimBlocks);
                yield "&cYou need &f" + needed + " &cmore claim blocks for that resize.";
            }
            case OUTSIDE_PARENT -> "&cThat resize would extend outside the parent claim.";
            case INNER_SUBDIVISION_TOO_CLOSE -> messageString("claim.inner-subdivision-too-close");
            case SIBLING_OVERLAP -> messageString("claim.resize-fail-overlap-subdivision");
            case WOULD_CLIP_CHILD -> messageString("claim.resize-fail-subdivision");
            case UNSUPPORTED, NOT_AVAILABLE -> "&cResize is not available on this GriefPrevention build.";
            case INVALID_INPUT -> "&cResize input was invalid.";
            default -> "&cThat resize could not be previewed.";
        };
    }

    private String getResizeApplyFailureMessage(GPBridge.ResizeResult result) {
        return switch (result.failureReason) {
            case APPLY_FAILED -> "&cGriefPrevention rejected that resize when applying it.";
            case OUTSIDE_PARENT -> "&cThat resize would extend outside the parent claim.";
            case INNER_SUBDIVISION_TOO_CLOSE -> messageString("claim.inner-subdivision-too-close");
            case SIBLING_OVERLAP -> messageString("claim.resize-fail-overlap-subdivision");
            case WOULD_CLIP_CHILD -> messageString("claim.resize-fail-subdivision");
            case NOT_ENOUGH_BLOCKS -> "&cYou don't have enough claim blocks for that resize.";
            case TOO_SMALL, INVALID_OFFSET_RANGE -> "&cThat segment can't be moved that far.";
            default -> "&cThe resize failed.";
        };
    }

    private String messageString(String path, String... replacements) {
        return LegacyComponentSerializer.legacySection().serialize(plugin.getMessages().get(path, replacements));
    }

    private GPBridge.ResizeDirection directionForSlot(int slot) {
        if (slot == northSlot) return GPBridge.ResizeDirection.NORTH;
        if (slot == southSlot) return GPBridge.ResizeDirection.SOUTH;
        if (slot == westSlot) return GPBridge.ResizeDirection.WEST;
        if (slot == eastSlot) return GPBridge.ResizeDirection.EAST;
        if (slot == upSlot) return GPBridge.ResizeDirection.UP;
        if (slot == downSlot) return GPBridge.ResizeDirection.DOWN;
        return null;
    }

    private int offsetForClick(InventoryClickEvent event) {
        if (isShiftLeftClick(event)) return 10;
        if (isShiftRightClick(event)) return -10;
        if (isLeftClick(event)) return 1;
        if (isRightClick(event)) return -1;
        return 0;
    }

    private boolean canManageClaim() {
        return gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin");
    }

    private boolean canUseVerticalResize() {
        return gp.isSubdivision(claim) && gp.is3DClaim(claim);
    }

    private Material materialFor(GPBridge.ResizeDirection direction) {
        return switch (direction) {
            case NORTH -> Material.LIGHT_BLUE_WOOL;
            case SOUTH -> Material.ORANGE_WOOL;
            case WEST -> Material.YELLOW_WOOL;
            case EAST -> Material.LIME_WOOL;
            case UP -> Material.SCAFFOLDING;
            case DOWN -> Material.IRON_CHAIN;
        };
    }

    private String titleFor(GPBridge.ResizeDirection direction) {
        return switch (direction) {
            case NORTH -> "&b&lNorth";
            case SOUTH -> "&6&lSouth";
            case WEST -> "&e&lWest";
            case EAST -> "&a&lEast";
            case UP -> "&b&lUp";
            case DOWN -> "&7&lDown";
        };
    }

    private String friendlyDirection(GPBridge.ResizeDirection direction) {
        return switch (direction) {
            case NORTH -> "north";
            case SOUTH -> "south";
            case WEST -> "west";
            case EAST -> "east";
            case UP -> "up";
            case DOWN -> "down";
        };
    }

    private GPBridge.ResizeDirection facingFromYaw(float yaw) {
        float normalized = yaw % 360f;
        if (normalized < 0f) normalized += 360f;

        int quadrant = Math.round(normalized / 90f) & 3;
        return switch (quadrant) {
            case 0 -> GPBridge.ResizeDirection.SOUTH;
            case 1 -> GPBridge.ResizeDirection.WEST;
            case 2 -> GPBridge.ResizeDirection.NORTH;
            default -> GPBridge.ResizeDirection.EAST;
        };
    }

    private Location getCompassTarget() {
        Location target = openingLocation.clone();
        switch (openingFacing) {
            case NORTH -> target.add(0, 0, -32);
            case SOUTH -> target.add(0, 0, 32);
            case WEST -> target.add(-32, 0, 0);
            case EAST -> target.add(32, 0, 0);
            default -> { }
        }
        return target;
    }

    private String formatDimensions(GPBridge.ClaimCorners corners) {
        if (corners == null) return "Unknown";

        int width = corners.x2 - corners.x1 + 1;
        int depth = corners.z2 - corners.z1 + 1;
        if (gp.is3DClaim(claim)) {
            int height = corners.y2 - corners.y1 + 1;
            return width + " x " + depth + " x " + height;
        }
        return width + " x " + depth;
    }

    private int getArea(GPBridge.ClaimCorners corners) {
        if (corners == null) return 0;
        int width = corners.x2 - corners.x1 + 1;
        int depth = corners.z2 - corners.z1 + 1;
        return width * depth;
    }
}
