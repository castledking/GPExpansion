package dev.towki.gpexpansion.gp;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge operations for claim map editing.
 * <p>
 * The GUI sends high-level intents (claim/unclaim selected cells) and this bridge translates
 * those intents into GP resize operations. The GUI never edits claim geometry directly.
 */
public final class ClaimMapEditorBridge {

    private static final int CELL_ADJACENCY_TOLERANCE = 2;

    private final GPBridge gp;

    public ClaimMapEditorBridge() {
        this(new GPBridge());
    }

    public ClaimMapEditorBridge(GPBridge gp) {
        this.gp = gp;
    }

    public enum OperationType {
        CLAIM,
        UNCLAIM
    }

    public enum EditMode {
        BASIC,
        SHAPED
    }

    public enum FailureReason {
        NONE,
        NOT_AVAILABLE,
        INVALID_SELECTION,
        NO_SELECTED_CLAIM,
        NOT_OWNER,
        SUBDIVISIONS_REQUIRE_CONFIRM,
        UNSUPPORTED_GEOMETRY,
        TARGET_ALREADY_CLAIMED,
        TARGET_BLOCKED,
        TARGET_NOT_ADJACENT,
        TARGET_NOT_ON_BOUNDARY,
        TARGET_AMBIGUOUS,
        PREVIEW_FAILED,
        APPLY_FAILED
    }

    /**
     * One map cell represented as a world-space rectangle.
     */
    public record CellSelection(
            World world,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int centerX,
            int centerZ
    ) {
        public Location centerLocation() {
            // GP claim lookups in this bridge use ignore-height semantics, so any safe Y is valid.
            return new Location(world, centerX + 0.5, world.getMinHeight() + 1, centerZ + 0.5);
        }

        public int blockArea() {
            int width = Math.abs(maxX - minX) + 1;
            int depth = Math.abs(maxZ - minZ) + 1;
            return width * depth;
        }
    }

    public static final class MapEditResult {
        public final boolean success;
        public final boolean applied;
        public final OperationType operation;
        public final FailureReason failureReason;
        public final String message;
        public final Object claim;
        public final GPBridge.ResizePreview preview;

        private MapEditResult(
                boolean success,
                boolean applied,
                OperationType operation,
                FailureReason failureReason,
                String message,
                Object claim,
                GPBridge.ResizePreview preview
        ) {
            this.success = success;
            this.applied = applied;
            this.operation = operation;
            this.failureReason = failureReason;
            this.message = message;
            this.claim = claim;
            this.preview = preview;
        }
    }

    private record OperationPlan(
            GPBridge.ResizeDirection direction,
            int offset,
            GPBridge.SegmentEdgeInfo segmentEdge,
            Integer segmentMinAlong,
            Integer segmentMaxAlong,
            Integer overlapLength,
            Integer bandDistance,
            Integer touchCount
    ) {
        private OperationPlan(GPBridge.ResizeDirection direction, int offset) {
            this(direction, offset, null, null, null, null, null, null);
        }
    }

    public MapEditResult previewClaimCells(Player player, CellSelection cellSelection, int zoomLevel, Object selectedClaim) {
        return previewClaimCells(player, cellSelection, zoomLevel, selectedClaim, EditMode.SHAPED);
    }

    public MapEditResult previewClaimCells(Player player, CellSelection cellSelection, int zoomLevel, Object selectedClaim, EditMode editMode) {
        return run(OperationType.CLAIM, player, cellSelection, zoomLevel, selectedClaim, editMode, false);
    }

    public MapEditResult commitClaimCells(Player player, CellSelection cellSelection, int zoomLevel, Object selectedClaim) {
        return commitClaimCells(player, cellSelection, zoomLevel, selectedClaim, EditMode.SHAPED);
    }

    public MapEditResult commitClaimCells(Player player, CellSelection cellSelection, int zoomLevel, Object selectedClaim, EditMode editMode) {
        return run(OperationType.CLAIM, player, cellSelection, zoomLevel, selectedClaim, editMode, true);
    }

    public MapEditResult previewUnclaimCells(Player player, CellSelection cellSelection, int zoomLevel, Object selectedClaim) {
        return previewUnclaimCells(player, cellSelection, zoomLevel, selectedClaim, EditMode.SHAPED);
    }

    public MapEditResult previewUnclaimCells(Player player, CellSelection cellSelection, int zoomLevel, Object selectedClaim, EditMode editMode) {
        return run(OperationType.UNCLAIM, player, cellSelection, zoomLevel, selectedClaim, editMode, false);
    }

    public MapEditResult commitUnclaimCells(Player player, CellSelection cellSelection, int zoomLevel, Object selectedClaim) {
        return commitUnclaimCells(player, cellSelection, zoomLevel, selectedClaim, EditMode.SHAPED);
    }

    public MapEditResult commitUnclaimCells(Player player, CellSelection cellSelection, int zoomLevel, Object selectedClaim, EditMode editMode) {
        return run(OperationType.UNCLAIM, player, cellSelection, zoomLevel, selectedClaim, editMode, true);
    }

    private MapEditResult run(
            OperationType operation,
            Player player,
            CellSelection selection,
            int zoomLevel,
            Object selectedClaim,
            EditMode editMode,
            boolean apply
    ) {
        EditMode resolvedMode = editMode == null ? EditMode.SHAPED : editMode;
        if (!gp.isAvailable()) {
            return fail(operation, FailureReason.NOT_AVAILABLE, "&cGriefPrevention is not available.", selectedClaim, null);
        }
        if (player == null || selection == null || selection.world() == null || zoomLevel <= 0) {
            return fail(operation, FailureReason.INVALID_SELECTION, "&cInvalid map selection.", selectedClaim, null);
        }

        Object targetAtCenter = gp.getDominantClaimInCell(
                selection.world(),
                selection.minX(),
                selection.maxX(),
                selection.minZ(),
                selection.maxZ(),
                player
        ).orElse(null);
        if (operation == OperationType.UNCLAIM) {
            if (selectedClaim == null) {
                return fail(operation, FailureReason.NO_SELECTED_CLAIM, "&cSelect one of your claims first.", null, null);
            }
            if (!gp.isOwner(selectedClaim, player.getUniqueId()) && !player.hasPermission("griefprevention.admin")) {
                return fail(operation, FailureReason.NOT_OWNER, "&cYou can only edit claims you own.", selectedClaim, null);
            }
            String selectedId = gp.getClaimId(selectedClaim).orElse(null);
            String targetId = gp.getClaimId(targetAtCenter).orElse(null);
            int selectedCoverage = gp.getClaimCoverageInCell(
                    selectedClaim,
                    selection.world(),
                    selection.minX(),
                    selection.maxX(),
                    selection.minZ(),
                    selection.maxZ()
            );
            if (selectedCoverage <= 0) {
                if (targetAtCenter == null) {
                    return fail(operation, FailureReason.TARGET_NOT_ON_BOUNDARY, "&cThat map cell is not part of the selected claim.", selectedClaim, null);
                }
                if (selectedId != null && targetId != null && !selectedId.equals(targetId)) {
                    return fail(operation, FailureReason.TARGET_NOT_ON_BOUNDARY, "&cThat map cell belongs to another claim.", selectedClaim, null);
                }
                return fail(operation, FailureReason.TARGET_NOT_ON_BOUNDARY, "&cThat map cell is not part of the selected claim.", selectedClaim, null);
            }
            if (!gp.getSubclaims(selectedClaim).isEmpty()) {
                return fail(
                        operation,
                        FailureReason.SUBDIVISIONS_REQUIRE_CONFIRM,
                        "&cThis claim contains subdivisions. Confirm abandon to remove the parent claim instead.",
                        selectedClaim,
                        null
                );
            }
            if (targetAtCenter != null && selectedId != null && targetId != null && !selectedId.equals(targetId)) {
                return fail(operation, FailureReason.TARGET_NOT_ON_BOUNDARY, "&cThat map cell belongs to another claim.", selectedClaim, null);
            }
            GPBridge.CreateClaimResult unclaimResult = gp.subtractMapCellFromClaim(
                    player,
                    selectedClaim,
                    selection.world(),
                    selection.minX(),
                    selection.maxX(),
                    selection.minZ(),
                    selection.maxZ(),
                    !apply
            );
            if (!unclaimResult.supported) {
                return fail(operation, FailureReason.UNSUPPORTED_GEOMETRY, "&c" + unclaimResult.message, selectedClaim, null);
            }
            if (!unclaimResult.success) {
                return fail(
                        operation,
                        apply ? FailureReason.APPLY_FAILED : FailureReason.PREVIEW_FAILED,
                        "&c" + unclaimResult.message,
                        selectedClaim,
                        null
                );
            }

            return new MapEditResult(
                    true,
                    apply,
                    operation,
                    FailureReason.NONE,
                    "&a" + unclaimResult.message,
                    unclaimResult.claim,
                    null
            );
        }

        // CLAIM flow
        String selectedId = selectedClaim == null ? null : gp.getClaimId(selectedClaim).orElse(null);
        String targetId = targetAtCenter == null ? null : gp.getClaimId(targetAtCenter).orElse(null);
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
        boolean selectedClaimPartiallyCoversCell = selectedId != null
                && selectedId.equals(targetId)
                && selectedCoverage > 0
                && selectedCoverage < selection.blockArea();

        if (selectedClaimPartiallyCoversCell) {
            if (!gp.isOwner(selectedClaim, player.getUniqueId()) && !player.hasPermission("griefprevention.admin")) {
                return fail(operation, FailureReason.NOT_OWNER, "&cYou can only edit claims you own.", selectedClaim, null);
            }

            GPBridge.CreateClaimResult mergeResult = gp.mergeMapCellIntoClaim(
                    player,
                    selectedClaim,
                    selection.world(),
                    selection.minX(),
                    selection.maxX(),
                    selection.minZ(),
                    selection.maxZ(),
                    !apply
            );
            if (mergeResult.supported) {
                if (mergeResult.success) {
                    String message = apply
                            ? "&aFilled the remaining area in that map tile."
                            : "&aReady to fill the remaining area in that map tile.";
                    Object mergedClaim = mergeResult.claim != null ? mergeResult.claim : selectedClaim;
                    return new MapEditResult(
                            true,
                            apply,
                            operation,
                            FailureReason.NONE,
                            message,
                            mergedClaim,
                            null
                    );
                }

                return fail(
                        operation,
                        apply ? FailureReason.APPLY_FAILED : FailureReason.PREVIEW_FAILED,
                        mergeResult.message == null || mergeResult.message.isBlank()
                                ? "&cThat partial tile could not be filled into the selected claim."
                                : mergeResult.message,
                        selectedClaim,
                        null
                );
            }
        }

        if (targetAtCenter != null) {
            if (selectedId != null && selectedId.equals(targetId)) {
                return fail(operation, FailureReason.TARGET_ALREADY_CLAIMED, "&cThat map cell is already inside the selected claim.", selectedClaim, null);
            }
            return fail(operation, FailureReason.TARGET_BLOCKED, "&cThat map cell is already claimed.", selectedClaim, null);
        }

        // In shaped mode, attempt connected merge first. If no side matches, fall back
        // to detached square creation for that cell.
        if (resolvedMode == EditMode.SHAPED) {
            if (selectedClaim == null) {
                return applyDetachedSquareClaim(operation, player, selection, null, apply, true);
            }
            if (!gp.isOwner(selectedClaim, player.getUniqueId()) && !player.hasPermission("griefprevention.admin")) {
                return fail(operation, FailureReason.NOT_OWNER, "&cYou can only edit claims you own.", selectedClaim, null);
            }

            GPBridge.CreateClaimResult mergeResult = gp.mergeMapCellIntoClaim(
                    player,
                    selectedClaim,
                    selection.world(),
                    selection.minX(),
                    selection.maxX(),
                    selection.minZ(),
                    selection.maxZ(),
                    !apply
            );
            if (mergeResult.supported) {
                if (mergeResult.success) {
                    String message = apply
                            ? "&aClaimed that map cell into the selected claim."
                            : "&aReady to merge that map cell into the selected claim.";
                    Object mergedClaim = mergeResult.claim != null ? mergeResult.claim : selectedClaim;
                    return new MapEditResult(
                            true,
                            apply,
                            operation,
                            FailureReason.NONE,
                            message,
                            mergedClaim,
                            null
                    );
                }

                if (isDetachedFallbackMergeFailure(mergeResult.message)) {
                    return applyDetachedSquareClaim(operation, player, selection, selectedClaim, apply, true);
                }

                return fail(
                        operation,
                        apply ? FailureReason.APPLY_FAILED : FailureReason.PREVIEW_FAILED,
                        mergeResult.message == null || mergeResult.message.isBlank()
                                ? "&cThat map cell could not be merged into the selected claim."
                                : mergeResult.message,
                        selectedClaim,
                        null
                );
            }

            GPBridge.ClaimCorners corners = gp.getClaimCorners(selectedClaim).orElse(null);
            if (corners == null) {
                return fail(
                        operation,
                        FailureReason.INVALID_SELECTION,
                        "&cCould not resolve the selected claim geometry.",
                        selectedClaim,
                        null
                );
            }

            List<OperationPlan> claimPlanCandidates = buildShapedClaimPlanCandidates(selectedClaim, selection);
            if (claimPlanCandidates.isEmpty() && !gp.isShapedClaim(selectedClaim)) {
                // Safety fallback for rectangular claims: if shaped edge detection misses an
                // adjacent side due map-grid alignment, still keep the action merged with the
                // selected claim rather than creating a detached claim.
                claimPlanCandidates = buildClaimPlanCandidates(corners, selection);
            }
            if (!claimPlanCandidates.isEmpty()) {
                OperationPlan claimPlan = claimPlanCandidates.size() == 1
                        ? claimPlanCandidates.get(0)
                        : pickBestShapedPlan(player, selectedClaim, claimPlanCandidates, selection.centerLocation());
                if (claimPlan == null) {
                    return fail(
                            operation,
                            FailureReason.TARGET_AMBIGUOUS,
                            "&cThat cell could not be resolved to a valid shaped nib edit from this location.",
                            selectedClaim,
                            null
                    );
                }
                boolean segmentAware = gp.usesSegmentAwareResize(selectedClaim, claimPlan.direction());
                if (!segmentAware && !gp.canResizeClaim(selectedClaim)) {
                    return fail(operation, FailureReason.NOT_AVAILABLE, "&cResize API is unavailable on this GP build.", selectedClaim, null);
                }
                return applyResizePlan(operation, player, selectedClaim, claimPlan, selection.centerLocation(), apply);
            }

            return applyDetachedSquareClaim(operation, player, selection, selectedClaim, apply, true);
        }

        // Basic mode always creates detached square claims from map cells.
        return applyDetachedSquareClaim(operation, player, selection, selectedClaim, apply, false);
    }

    private MapEditResult applyDetachedSquareClaim(
            OperationType operation,
            Player player,
            CellSelection selection,
            Object selectedClaim,
            boolean apply,
            boolean shapedFallback
    ) {
        GPBridge.CreateClaimResult create = gp.createSquareClaim(
                player,
                selection.world(),
                selection.minX(),
                selection.maxX(),
                selection.minZ(),
                selection.maxZ(),
                !apply
        );
        if (!create.supported) {
            return fail(
                    operation,
                    FailureReason.NOT_AVAILABLE,
                    create.message == null || create.message.isBlank()
                            ? "&cThis GP build does not expose claim creation from map editor."
                            : create.message,
                    selectedClaim,
                    null
            );
        }
        if (!create.success) {
            return fail(
                    operation,
                    apply ? FailureReason.APPLY_FAILED : FailureReason.PREVIEW_FAILED,
                    create.message == null || create.message.isBlank()
                            ? "&cClaim creation failed for that map cell."
                            : create.message,
                    selectedClaim,
                    null
            );
        }

        String previewMessage = shapedFallback
                ? "&aNo adjacent boundary matched. Ready to create a detached square claim for that map cell."
                : "&aReady to create a detached square claim for that map cell.";
        if (!apply) {
            return new MapEditResult(
                    true,
                    false,
                    operation,
                    FailureReason.NONE,
                    previewMessage,
                    create.claim,
                    null
            );
        }

        String appliedMessage = shapedFallback
                ? "&aNo adjacent boundary matched, so this map cell was claimed as a detached square claim."
                : "&aClaimed that map cell as a detached square claim.";
        return new MapEditResult(
                true,
                true,
                operation,
                FailureReason.NONE,
                appliedMessage,
                create.claim,
                null
        );
    }

    private MapEditResult applyResizePlan(
            OperationType operation,
            Player player,
            Object selectedClaim,
            OperationPlan plan,
            Location referenceLocation,
            boolean apply
    ) {
        Object workingClaim = selectedClaim;
        Location effectiveReference = resolvePlanReferenceLocation(plan, referenceLocation);
        if (apply
                && plan.segmentEdge() != null
                && plan.segmentMinAlong() != null
                && plan.segmentMaxAlong() != null) {
            GPBridge.CreateClaimResult prepared = gp.ensureSegmentBoundariesForMapNib(
                    player,
                    workingClaim,
                    plan.segmentEdge(),
                    plan.segmentMinAlong(),
                    plan.segmentMaxAlong()
            );
            if (!prepared.supported) {
                return fail(
                        operation,
                        FailureReason.NOT_AVAILABLE,
                        prepared.message == null || prepared.message.isBlank()
                                ? "&cThis GP build cannot segmentize shaped boundary edits from map cells."
                                : prepared.message,
                        selectedClaim,
                        null
                );
            }
            if (!prepared.success) {
                return fail(
                        operation,
                        FailureReason.APPLY_FAILED,
                        prepared.message == null || prepared.message.isBlank()
                                ? "&cCould not create segment boundaries for that map nib resize."
                                : prepared.message,
                        selectedClaim,
                        null
                );
            }
            if (prepared.claim != null) {
                workingClaim = prepared.claim;
            }
        }

        GPBridge.ResizePreview preview = gp.previewResizeClaim(
                player,
                workingClaim,
                plan.direction(),
                plan.offset(),
                effectiveReference
        );
        if (!preview.supported) {
            return fail(operation, FailureReason.PREVIEW_FAILED, "&cResize preview isn't available on this GP build.", selectedClaim, preview);
        }
        if (!preview.valid) {
            return fail(
                    operation,
                    FailureReason.PREVIEW_FAILED,
                    mapPreviewFailure(operation, preview),
                    workingClaim,
                    preview
            );
        }

        if (!apply) {
            String sign = preview.clampedOffset > 0 ? "+" : "";
            String msg = operation == OperationType.CLAIM
                    ? "&aReady to claim toward &e" + friendlyDirection(plan.direction()) + " &aby &f" + sign + preview.clampedOffset + "&a."
                    : "&aReady to unclaim from &e" + friendlyDirection(plan.direction()) + " &aby &f" + preview.clampedOffset + "&a.";
            return new MapEditResult(true, false, operation, FailureReason.NONE, msg, workingClaim, preview);
        }

        GPBridge.ResizeResult result = gp.resizeClaim(
                player,
                workingClaim,
                plan.direction(),
                plan.offset(),
                effectiveReference
        );
        if (!result.success) {
            return fail(operation, FailureReason.APPLY_FAILED, mapApplyFailure(operation, result), workingClaim, result.preview);
        }

        String sign = result.preview.clampedOffset > 0 ? "+" : "";
        String msg = operation == OperationType.CLAIM
                ? "&aClaimed map cells toward &e" + friendlyDirection(plan.direction()) + " &aby &f" + sign + result.preview.clampedOffset + "&a."
                : "&aUnclaimed map cells from &e" + friendlyDirection(plan.direction()) + " &aby &f" + result.preview.clampedOffset + "&a.";
        return new MapEditResult(true, true, operation, FailureReason.NONE, msg, result.claim, result.preview);
    }

    private MapEditResult fail(
            OperationType operation,
            FailureReason reason,
            String message,
            Object claim,
            GPBridge.ResizePreview preview
    ) {
        return new MapEditResult(false, false, operation, reason, message, claim, preview);
    }

    private boolean isDetachedFallbackMergeFailure(String message) {
        if (message == null) {
            return false;
        }
        return message.startsWith("No adjacent boundary matched");
    }

    private List<OperationPlan> buildClaimPlanCandidates(GPBridge.ClaimCorners corners, CellSelection cell) {
        List<OperationPlan> strict = buildClaimPlanCandidates(corners, cell, 0);
        if (!strict.isEmpty()) {
            return strict;
        }

        return buildClaimPlanCandidates(corners, cell, CELL_ADJACENCY_TOLERANCE);
    }

    private List<OperationPlan> buildClaimPlanCandidates(GPBridge.ClaimCorners corners, CellSelection cell, int tolerance) {
        List<OperationPlan> candidates = new ArrayList<>(4);
        int cellWidth = cell.maxX() - cell.minX() + 1;
        int cellDepth = cell.maxZ() - cell.minZ() + 1;

        // Accept both perfectly aligned and partially overlapping edge cells so map
        // grids that are not boundary-aligned still resolve to side expansion.

        // North expansion
        boolean northTouch = touchesCoordinateBand(cell.minZ(), cell.maxZ(), corners.z1 - 1, tolerance)
                && rangesOverlap(cell.minX(), cell.maxX(), corners.x1, corners.x2);
        if (northTouch) {
            int amount = cellDepth;
            if (amount > 0) candidates.add(new OperationPlan(GPBridge.ResizeDirection.NORTH, amount));
        }
        // South expansion
        boolean southTouch = touchesCoordinateBand(cell.minZ(), cell.maxZ(), corners.z2 + 1, tolerance)
                && rangesOverlap(cell.minX(), cell.maxX(), corners.x1, corners.x2);
        if (southTouch) {
            int amount = cellDepth;
            if (amount > 0) candidates.add(new OperationPlan(GPBridge.ResizeDirection.SOUTH, amount));
        }
        // West expansion
        boolean westTouch = touchesCoordinateBand(cell.minX(), cell.maxX(), corners.x1 - 1, tolerance)
                && rangesOverlap(cell.minZ(), cell.maxZ(), corners.z1, corners.z2);
        if (westTouch) {
            int amount = cellWidth;
            if (amount > 0) candidates.add(new OperationPlan(GPBridge.ResizeDirection.WEST, amount));
        }
        // East expansion
        boolean eastTouch = touchesCoordinateBand(cell.minX(), cell.maxX(), corners.x2 + 1, tolerance)
                && rangesOverlap(cell.minZ(), cell.maxZ(), corners.z1, corners.z2);
        if (eastTouch) {
            int amount = cellWidth;
            if (amount > 0) candidates.add(new OperationPlan(GPBridge.ResizeDirection.EAST, amount));
        }

        return candidates;
    }

    private List<OperationPlan> buildShapedClaimPlanCandidates(Object claim, CellSelection cell) {
        List<OperationPlan> strict = buildShapedClaimPlanCandidates(claim, cell, 0);
        if (!strict.isEmpty()) {
            return strict;
        }

        return buildShapedClaimPlanCandidates(claim, cell, CELL_ADJACENCY_TOLERANCE);
    }

    private List<OperationPlan> buildShapedClaimPlanCandidates(Object claim, CellSelection cell, int tolerance) {
        List<OperationPlan> candidates = new ArrayList<>(4);
        addShapedCandidate(claim, cell, GPBridge.ResizeDirection.NORTH, tolerance, candidates);
        addShapedCandidate(claim, cell, GPBridge.ResizeDirection.SOUTH, tolerance, candidates);
        addShapedCandidate(claim, cell, GPBridge.ResizeDirection.WEST, tolerance, candidates);
        addShapedCandidate(claim, cell, GPBridge.ResizeDirection.EAST, tolerance, candidates);
        return candidates;
    }

    private void addShapedCandidate(
            Object claim,
            CellSelection cell,
            GPBridge.ResizeDirection direction,
            int tolerance,
            List<OperationPlan> candidates
    ) {
        int directionalSupport = countDirectionalTouchColumns(claim, cell, direction, tolerance);
        if (directionalSupport <= 0) {
            return;
        }

        List<GPBridge.SegmentEdgeInfo> edges = gp.resolveSegmentEdges(claim, direction);
        if (edges.isEmpty()) {
            return;
        }
        int cellWidth = cell.maxX() - cell.minX() + 1;
        int cellDepth = cell.maxZ() - cell.minZ() + 1;

        OperationPlan best = null;
        int bestEdgeSupport = Integer.MIN_VALUE;
        int bestAmount = Integer.MAX_VALUE;
        int bestBandDistance = Integer.MAX_VALUE;
        int bestOverlapLength = -1;

        for (GPBridge.SegmentEdgeInfo edge : edges) {
            int overlapMin;
            int overlapMax;
            int amount;
            int bandDistance;
            switch (direction) {
                case NORTH -> {
                    int northOutside = edge.axisCoordinate - 1;
                    if (!touchesCoordinateBand(cell.minZ(), cell.maxZ(), northOutside, tolerance)) continue;
                    overlapMin = Math.max(cell.minX(), edge.minAlongAxis);
                    overlapMax = Math.min(cell.maxX(), edge.maxAlongAxis);
                    if (overlapMin > overlapMax) continue;
                    amount = cellDepth;
                    bandDistance = coordinateBandDistance(cell.minZ(), cell.maxZ(), northOutside);
                }
                case SOUTH -> {
                    int southOutside = edge.axisCoordinate + 1;
                    if (!touchesCoordinateBand(cell.minZ(), cell.maxZ(), southOutside, tolerance)) continue;
                    overlapMin = Math.max(cell.minX(), edge.minAlongAxis);
                    overlapMax = Math.min(cell.maxX(), edge.maxAlongAxis);
                    if (overlapMin > overlapMax) continue;
                    amount = cellDepth;
                    bandDistance = coordinateBandDistance(cell.minZ(), cell.maxZ(), southOutside);
                }
                case WEST -> {
                    int westOutside = edge.axisCoordinate - 1;
                    if (!touchesCoordinateBand(cell.minX(), cell.maxX(), westOutside, tolerance)) continue;
                    overlapMin = Math.max(cell.minZ(), edge.minAlongAxis);
                    overlapMax = Math.min(cell.maxZ(), edge.maxAlongAxis);
                    if (overlapMin > overlapMax) continue;
                    amount = cellWidth;
                    bandDistance = coordinateBandDistance(cell.minX(), cell.maxX(), westOutside);
                }
                case EAST -> {
                    int eastOutside = edge.axisCoordinate + 1;
                    if (!touchesCoordinateBand(cell.minX(), cell.maxX(), eastOutside, tolerance)) continue;
                    overlapMin = Math.max(cell.minZ(), edge.minAlongAxis);
                    overlapMax = Math.min(cell.maxZ(), edge.maxAlongAxis);
                    if (overlapMin > overlapMax) continue;
                    amount = cellWidth;
                    bandDistance = coordinateBandDistance(cell.minX(), cell.maxX(), eastOutside);
                }
                default -> {
                    continue;
                }
            }

            if (amount <= 0) {
                continue;
            }
            int overlapLength = overlapMax - overlapMin + 1;
            int edgeSupport = scoreEdgeInteriorSupport(
                    claim,
                    cell.world(),
                    direction,
                    edge,
                    overlapMin,
                    overlapMax
            );
            if (edgeSupport <= 0) {
                continue;
            }
            if (best == null
                    || edgeSupport > bestEdgeSupport
                    || (edgeSupport == bestEdgeSupport && bandDistance < bestBandDistance)
                    || (bandDistance == bestBandDistance && overlapLength > bestOverlapLength)
                    || (bandDistance == bestBandDistance && overlapLength == bestOverlapLength && amount < bestAmount)) {
                bestEdgeSupport = edgeSupport;
                bestBandDistance = bandDistance;
                bestOverlapLength = overlapLength;
                bestAmount = amount;
                best = new OperationPlan(direction, amount, edge, overlapMin, overlapMax, overlapLength, bandDistance, edgeSupport);
            }
        }

        if (best != null) {
            candidates.add(best);
        }
    }

    private OperationPlan pickBestShapedPlan(
            Player player,
            Object claim,
            List<OperationPlan> candidates,
            Location referenceLocation
    ) {
        OperationPlan bestPlan = null;
        GPBridge.ResizePreview bestPreview = null;

        for (OperationPlan candidate : candidates) {
            Location candidateReference = resolvePlanReferenceLocation(candidate, referenceLocation);
            GPBridge.ResizePreview preview = gp.previewResizeClaim(
                    player,
                    claim,
                    candidate.direction(),
                    candidate.offset(),
                    candidateReference
            );
            if (!preview.supported || !preview.valid) {
                continue;
            }
            if (preview.blockDelta <= 0) {
                continue;
            }

            if (bestPlan == null || isBetterCandidate(candidate, preview, bestPlan, bestPreview)) {
                bestPlan = candidate;
                bestPreview = preview;
            }
        }

        return bestPlan;
    }

    private boolean isBetterCandidate(
            OperationPlan candidate,
            GPBridge.ResizePreview preview,
            OperationPlan incumbent,
            GPBridge.ResizePreview incumbentPreview
    ) {
        if (incumbent == null || incumbentPreview == null) {
            return true;
        }

        int candidateDelta = Math.max(0, preview.blockDelta);
        int incumbentDelta = Math.max(0, incumbentPreview.blockDelta);
        int candidateTouchCount = candidate.touchCount() == null ? 0 : candidate.touchCount();
        int incumbentTouchCount = incumbent.touchCount() == null ? 0 : incumbent.touchCount();
        if (candidateTouchCount != incumbentTouchCount) {
            return candidateTouchCount > incumbentTouchCount;
        }

        int candidateBandDistance = candidate.bandDistance() == null ? Integer.MAX_VALUE : candidate.bandDistance();
        int incumbentBandDistance = incumbent.bandDistance() == null ? Integer.MAX_VALUE : incumbent.bandDistance();
        if (candidateBandDistance != incumbentBandDistance) {
            return candidateBandDistance < incumbentBandDistance;
        }

        int candidateOverlap = candidate.overlapLength() == null ? -1 : candidate.overlapLength();
        int incumbentOverlap = incumbent.overlapLength() == null ? -1 : incumbent.overlapLength();
        if (candidateOverlap != incumbentOverlap) {
            return candidateOverlap > incumbentOverlap;
        }

        if (candidateDelta != incumbentDelta) {
            return candidateDelta < incumbentDelta;
        }

        int candidateOffset = Math.abs(preview.clampedOffset);
        int incumbentOffset = Math.abs(incumbentPreview.clampedOffset);
        if (candidateOffset != incumbentOffset) {
            return candidateOffset < incumbentOffset;
        }

        int candidateSpan = segmentSpan(candidate);
        int incumbentSpan = segmentSpan(incumbent);
        if (candidateSpan != incumbentSpan) {
            return candidateSpan < incumbentSpan;
        }

        return directionRank(candidate.direction()) < directionRank(incumbent.direction());
    }

    private int segmentSpan(OperationPlan plan) {
        if (plan.segmentMinAlong() == null || plan.segmentMaxAlong() == null) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, plan.segmentMaxAlong() - plan.segmentMinAlong());
    }

    private Location resolvePlanReferenceLocation(OperationPlan plan, Location fallback) {
        if (fallback == null || fallback.getWorld() == null) {
            return fallback;
        }

        if (plan.segmentEdge() == null || plan.segmentMinAlong() == null || plan.segmentMaxAlong() == null) {
            return fallback;
        }

        int fallbackAlong = switch (plan.direction()) {
            case NORTH, SOUTH -> fallback.getBlockX();
            case WEST, EAST -> fallback.getBlockZ();
            default -> (plan.segmentMinAlong() + plan.segmentMaxAlong()) / 2;
        };
        int along = Math.max(plan.segmentMinAlong(), Math.min(plan.segmentMaxAlong(), fallbackAlong));
        double y = Math.max(
                fallback.getWorld().getMinHeight() + 1,
                Math.min(fallback.getY(), fallback.getWorld().getMaxHeight() - 1)
        );

        return switch (plan.direction()) {
            case NORTH -> new Location(fallback.getWorld(), along + 0.5D, y, plan.segmentEdge().axisCoordinate - 0.5D);
            // South/east expansions must reference the first block outside the claim,
            // not the boundary block itself, or map nibs resolve one block inward.
            case SOUTH -> new Location(fallback.getWorld(), along + 0.5D, y, plan.segmentEdge().axisCoordinate + 1.5D);
            case WEST -> new Location(fallback.getWorld(), plan.segmentEdge().axisCoordinate - 0.5D, y, along + 0.5D);
            case EAST -> new Location(fallback.getWorld(), plan.segmentEdge().axisCoordinate + 1.5D, y, along + 0.5D);
            default -> fallback;
        };
    }

    private int directionRank(GPBridge.ResizeDirection direction) {
        return switch (direction) {
            case NORTH -> 0;
            case SOUTH -> 1;
            case WEST -> 2;
            case EAST -> 3;
            case UP -> 4;
            case DOWN -> 5;
        };
    }

    private OperationPlan buildUnclaimPlan(GPBridge.ClaimCorners corners, CellSelection cell) {
        List<OperationPlan> candidates = new ArrayList<>(4);

        // Must touch a claim boundary on exactly one side.
        if (cell.minZ() <= corners.z1 && cell.maxZ() >= corners.z1
                && rangesOverlap(cell.minX(), cell.maxX(), corners.x1, corners.x2)) {
            int amount = cell.maxZ() - corners.z1 + 1;
            if (amount > 0) candidates.add(new OperationPlan(GPBridge.ResizeDirection.NORTH, -amount));
        }
        if (cell.minZ() <= corners.z2 && cell.maxZ() >= corners.z2
                && rangesOverlap(cell.minX(), cell.maxX(), corners.x1, corners.x2)) {
            int amount = corners.z2 - cell.minZ() + 1;
            if (amount > 0) candidates.add(new OperationPlan(GPBridge.ResizeDirection.SOUTH, -amount));
        }
        if (cell.minX() <= corners.x1 && cell.maxX() >= corners.x1
                && rangesOverlap(cell.minZ(), cell.maxZ(), corners.z1, corners.z2)) {
            int amount = cell.maxX() - corners.x1 + 1;
            if (amount > 0) candidates.add(new OperationPlan(GPBridge.ResizeDirection.WEST, -amount));
        }
        if (cell.minX() <= corners.x2 && cell.maxX() >= corners.x2
                && rangesOverlap(cell.minZ(), cell.maxZ(), corners.z1, corners.z2)) {
            int amount = corners.x2 - cell.minX() + 1;
            if (amount > 0) candidates.add(new OperationPlan(GPBridge.ResizeDirection.EAST, -amount));
        }

        if (candidates.size() != 1) return null;
        return candidates.get(0);
    }

    private boolean rangesOverlap(int minA, int maxA, int minB, int maxB) {
        return maxA >= minB && minA <= maxB;
    }

    private boolean touchesCoordinateBand(int min, int max, int coordinate) {
        return touchesCoordinateBand(min, max, coordinate, CELL_ADJACENCY_TOLERANCE);
    }

    private boolean touchesCoordinateBand(int min, int max, int coordinate, int tolerance) {
        return min <= coordinate + Math.max(0, tolerance)
                && max >= coordinate - Math.max(0, tolerance);
    }

    private int coordinateBandDistance(int min, int max, int coordinate) {
        if (coordinate < min) {
            return min - coordinate;
        }
        if (coordinate > max) {
            return coordinate - max;
        }
        return 0;
    }

    private int countDirectionalTouchColumns(
            Object claim,
            CellSelection cell,
            GPBridge.ResizeDirection direction,
            int tolerance
    ) {
        World world = cell.world();
        if (world == null || claim == null) {
            return 0;
        }

        int sampleY = world.getMinHeight() + 1;
        int maxExtra = Math.max(0, tolerance);
        int best = 0;

        for (int extra = 0; extra <= maxExtra; extra++) {
            int distance = 1 + extra;
            int supportScore = 0;
            switch (direction) {
                case NORTH -> {
                    int adjacentZ = cell.maxZ() + distance;
                    int interiorZ1 = adjacentZ + 1;
                    int interiorZ2 = adjacentZ + 2;
                    for (int x = cell.minX(); x <= cell.maxX(); x++) {
                        if (gp.claimContains(claim, world, x, sampleY, adjacentZ)) {
                            supportScore += 100;
                            if (gp.claimContains(claim, world, x, sampleY, interiorZ1)) {
                                supportScore += 10;
                            }
                            if (gp.claimContains(claim, world, x, sampleY, interiorZ2)) {
                                supportScore += 1;
                            }
                        }
                    }
                }
                case SOUTH -> {
                    int adjacentZ = cell.minZ() - distance;
                    int interiorZ1 = adjacentZ - 1;
                    int interiorZ2 = adjacentZ - 2;
                    for (int x = cell.minX(); x <= cell.maxX(); x++) {
                        if (gp.claimContains(claim, world, x, sampleY, adjacentZ)) {
                            supportScore += 100;
                            if (gp.claimContains(claim, world, x, sampleY, interiorZ1)) {
                                supportScore += 10;
                            }
                            if (gp.claimContains(claim, world, x, sampleY, interiorZ2)) {
                                supportScore += 1;
                            }
                        }
                    }
                }
                case WEST -> {
                    int adjacentX = cell.maxX() + distance;
                    int interiorX1 = adjacentX + 1;
                    int interiorX2 = adjacentX + 2;
                    for (int z = cell.minZ(); z <= cell.maxZ(); z++) {
                        if (gp.claimContains(claim, world, adjacentX, sampleY, z)) {
                            supportScore += 100;
                            if (gp.claimContains(claim, world, interiorX1, sampleY, z)) {
                                supportScore += 10;
                            }
                            if (gp.claimContains(claim, world, interiorX2, sampleY, z)) {
                                supportScore += 1;
                            }
                        }
                    }
                }
                case EAST -> {
                    int adjacentX = cell.minX() - distance;
                    int interiorX1 = adjacentX - 1;
                    int interiorX2 = adjacentX - 2;
                    for (int z = cell.minZ(); z <= cell.maxZ(); z++) {
                        if (gp.claimContains(claim, world, adjacentX, sampleY, z)) {
                            supportScore += 100;
                            if (gp.claimContains(claim, world, interiorX1, sampleY, z)) {
                                supportScore += 10;
                            }
                            if (gp.claimContains(claim, world, interiorX2, sampleY, z)) {
                                supportScore += 1;
                            }
                        }
                    }
                }
                default -> {
                    return 0;
                }
            }

            if (supportScore > best) {
                best = supportScore;
            }
        }

        return best;
    }

    private int scoreEdgeInteriorSupport(
            Object claim,
            World world,
            GPBridge.ResizeDirection direction,
            GPBridge.SegmentEdgeInfo edge,
            int minAlong,
            int maxAlong
    ) {
        if (claim == null || world == null || edge == null) {
            return 0;
        }
        if (minAlong > maxAlong) {
            return 0;
        }

        int sampleY = world.getMinHeight() + 1;
        int score = 0;
        switch (direction) {
            case NORTH -> {
                int boundaryZ = edge.axisCoordinate;
                int interiorZ1 = boundaryZ + 1;
                int interiorZ2 = boundaryZ + 2;
                for (int x = minAlong; x <= maxAlong; x++) {
                    if (gp.claimContains(claim, world, x, sampleY, boundaryZ)) {
                        score += 100;
                        if (gp.claimContains(claim, world, x, sampleY, interiorZ1)) {
                            score += 10;
                        }
                        if (gp.claimContains(claim, world, x, sampleY, interiorZ2)) {
                            score += 1;
                        }
                    }
                }
            }
            case SOUTH -> {
                int boundaryZ = edge.axisCoordinate;
                int interiorZ1 = boundaryZ - 1;
                int interiorZ2 = boundaryZ - 2;
                for (int x = minAlong; x <= maxAlong; x++) {
                    if (gp.claimContains(claim, world, x, sampleY, boundaryZ)) {
                        score += 100;
                        if (gp.claimContains(claim, world, x, sampleY, interiorZ1)) {
                            score += 10;
                        }
                        if (gp.claimContains(claim, world, x, sampleY, interiorZ2)) {
                            score += 1;
                        }
                    }
                }
            }
            case WEST -> {
                int boundaryX = edge.axisCoordinate;
                int interiorX1 = boundaryX + 1;
                int interiorX2 = boundaryX + 2;
                for (int z = minAlong; z <= maxAlong; z++) {
                    if (gp.claimContains(claim, world, boundaryX, sampleY, z)) {
                        score += 100;
                        if (gp.claimContains(claim, world, interiorX1, sampleY, z)) {
                            score += 10;
                        }
                        if (gp.claimContains(claim, world, interiorX2, sampleY, z)) {
                            score += 1;
                        }
                    }
                }
            }
            case EAST -> {
                int boundaryX = edge.axisCoordinate;
                int interiorX1 = boundaryX - 1;
                int interiorX2 = boundaryX - 2;
                for (int z = minAlong; z <= maxAlong; z++) {
                    if (gp.claimContains(claim, world, boundaryX, sampleY, z)) {
                        score += 100;
                        if (gp.claimContains(claim, world, interiorX1, sampleY, z)) {
                            score += 10;
                        }
                        if (gp.claimContains(claim, world, interiorX2, sampleY, z)) {
                            score += 1;
                        }
                    }
                }
            }
            default -> {
                return 0;
            }
        }
        return score;
    }

    private String friendlyDirection(GPBridge.ResizeDirection direction) {
        return switch (direction) {
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST -> "east";
            case WEST -> "west";
            case UP -> "up";
            case DOWN -> "down";
        };
    }

    private String mapPreviewFailure(OperationType operation, GPBridge.ResizePreview preview) {
        return switch (preview.failureReason) {
            case INVALID_OFFSET_RANGE -> operation == OperationType.CLAIM
                    ? "&cThat cell selection extends beyond what can be claimed from this side."
                    : "&cThat cell selection extends beyond what can be unclaimed from this side.";
            case TOO_SMALL -> "&cThat would make the claim smaller than GP minimums.";
            case NOT_ENOUGH_BLOCKS -> {
                int needed = Math.max(0, preview.blockDelta - preview.remainingClaimBlocks);
                yield "&cYou need &f" + needed + " &cmore claim blocks for that edit.";
            }
            case OUTSIDE_PARENT -> "&cThat edit would extend outside the parent claim.";
            case INNER_SUBDIVISION_TOO_CLOSE -> "&cThat edit would violate subdivision spacing rules.";
            case SIBLING_OVERLAP -> "&cThat edit would overlap another subdivision.";
            case WOULD_CLIP_CHILD -> "&cThat edit would exclude an existing child subdivision.";
            case UNSUPPORTED, NOT_AVAILABLE -> "&cResize preview is unavailable on this GP build.";
            case INVALID_INPUT -> "&cThe map selection could not be translated to a valid edit.";
            default -> "&cThat map edit is not valid.";
        };
    }

    private String mapApplyFailure(OperationType operation, GPBridge.ResizeResult result) {
        return switch (result.failureReason) {
            case APPLY_FAILED -> operation == OperationType.CLAIM
                    ? "&cGriefPrevention rejected that claim edit during apply."
                    : "&cGriefPrevention rejected that unclaim edit during apply.";
            case OUTSIDE_PARENT -> "&cThat edit would extend outside the parent claim.";
            case INNER_SUBDIVISION_TOO_CLOSE -> "&cThat edit would violate subdivision spacing rules.";
            case SIBLING_OVERLAP -> "&cThat edit would overlap another subdivision.";
            case WOULD_CLIP_CHILD -> "&cThat edit would exclude an existing child subdivision.";
            default -> "&cThe map edit failed to apply.";
        };
    }
}
