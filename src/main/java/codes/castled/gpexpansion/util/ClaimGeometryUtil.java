package codes.castled.gpexpansion.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import codes.castled.gpexpansion.gp.GPBridge;

import java.util.Optional;
import java.util.UUID;

/**
 * Shared geometry and metadata helpers for claim objects.
 * Consolidates methods previously duplicated across GUI classes.
 */
public final class ClaimGeometryUtil {

    private ClaimGeometryUtil() {}

    /**
     * Calculates the 2D area (width * length) of a claim via reflection.
     */
    public static int getClaimArea(Object claim) {
        try {
            Object lesserCorner = claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
            Object greaterCorner = claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);

            int width = Math.abs(((Location) greaterCorner).getBlockX() - ((Location) lesserCorner).getBlockX()) + 1;
            int length = Math.abs(((Location) greaterCorner).getBlockZ() - ((Location) lesserCorner).getBlockZ()) + 1;

            return width * length;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Returns a human-readable location string ("world @ x, z") for a claim.
     */
    public static String getClaimLocation(Object claim) {
        try {
            Object lesserCorner = claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
            Location loc = (Location) lesserCorner;
            return loc.getWorld().getName() + " @ " + loc.getBlockX() + ", " + loc.getBlockZ();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * Tests whether a location falls inside the claim's bounding box,
     * including the Y axis for 3D claims.
     */
    public static boolean containsLocation(GPBridge gp, Object claim, Location location) {
        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        if (corners == null || location.getWorld() == null) return false;

        String claimWorld = gp.getClaimWorld(claim).orElse(null);
        if (claimWorld == null || !claimWorld.equals(location.getWorld().getName())) {
            return false;
        }

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        if (x < corners.x1 || x > corners.x2 || z < corners.z1 || z > corners.z2) {
            return false;
        }

        if (gp.is3DClaim(claim)) {
            return y >= corners.y1 && y <= corners.y2;
        }

        return true;
    }

    /**
     * Returns a size metric for the claim — volume for 3D claims, area for 2D.
     */
    public static long getClaimSizeMetric(GPBridge gp, Object claim) {
        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        if (corners == null) return Long.MAX_VALUE;

        long width = (long) corners.x2 - corners.x1 + 1L;
        long length = (long) corners.z2 - corners.z1 + 1L;

        if (gp.is3DClaim(claim)) {
            long height = (long) corners.y2 - corners.y1 + 1L;
            return width * length * height;
        }

        return width * length;
    }

    /**
     * Resolves the owner name from a claim via reflection.
     */
    public static String getOwnerName(Object claim) {
        try {
            Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
            if (ownerId instanceof UUID) {
                String name = Bukkit.getOfflinePlayer((UUID) ownerId).getName();
                return name != null ? name : "Unknown";
            }
        } catch (Exception e) {
            // Ignore
        }
        return "Unknown";
    }
}
