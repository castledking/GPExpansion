package codes.castled.gpexpansion.integration.map;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A single claim's geometry and metadata, shaped for consumption by external map
 * renderers. Deliberately free of any Bukkit, GriefPrevention, Dynmap or BlueMap
 * types so both renderers can share it without compile-time coupling.
 *
 * @param markerColor the claim's {@code /claim color} as 0xRRGGBB, or -1 when unset
 *                    (renderers fall back to their per-integration default)
 * @param managers    resolved trusted-player names with MANAGE trust
 * @param builders    resolved trusted-player names with BUILD trust
 * @param containers  resolved trusted-player names with CONTAINERS trust
 * @param accessors   resolved trusted-player names with ACCESS trust
 */
public record ClaimShape(
        String claimId,
        String worldName,
        String ownerName,
        boolean isAdmin,
        double[] xs,
        double[] zs,
        int minY,
        int maxY,
        int markerColor,
        List<String> managers,
        List<String> builders,
        List<String> containers,
        List<String> accessors
) {

    public ClaimShape {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(xs, "xs");
        Objects.requireNonNull(zs, "zs");
        if (xs.length != zs.length || xs.length < 3) {
            throw new IllegalArgumentException("outline must have at least 3 points with matching x/z sizes");
        }
        xs = Arrays.copyOf(xs, xs.length);
        zs = Arrays.copyOf(zs, zs.length);
        managers = managers == null ? List.of() : List.copyOf(managers);
        builders = builders == null ? List.of() : List.copyOf(builders);
        containers = containers == null ? List.of() : List.copyOf(containers);
        accessors = accessors == null ? List.of() : List.copyOf(accessors);
    }

    /**
     * Whether the claim has real vertical bounds (a GP3D shaped claim) rather than the
     * whole-world fallback GP reports for ordinary heightless claims. Mirrors the
     * {@code (max - min) < 200} heuristic used elsewhere in this plugin.
     */
    public boolean isShaped3D() {
        return (maxY - minY) < 200;
    }

    @Override
    public double[] xs() {
        return Arrays.copyOf(xs, xs.length);
    }

    @Override
    public double[] zs() {
        return Arrays.copyOf(zs, zs.length);
    }

    public double minX() {
        double min = xs[0];
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] < min) min = xs[i];
        }
        return min;
    }

    public double maxX() {
        double max = xs[0];
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] > max) max = xs[i];
        }
        return max;
    }

    public double minZ() {
        double min = zs[0];
        for (int i = 1; i < zs.length; i++) {
            if (zs[i] < min) min = zs[i];
        }
        return min;
    }

    public double maxZ() {
        double max = zs[0];
        for (int i = 1; i < zs.length; i++) {
            if (zs[i] > max) max = zs[i];
        }
        return max;
    }
}