package codes.castled.gpexpansion.gp;

import com.griefprevention.geometry.OrthogonalPoint2i;
import com.griefprevention.geometry.OrthogonalPolygon;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaimOutlineTest {
    public record PolygonClaim(OrthogonalPolygon boundary) {
        public OrthogonalPolygon getBoundaryPolygon() { return boundary; }
    }

    public record RectangularClaim(Location lesserBoundaryCorner, Location greaterBoundaryCorner) {
        public Location getLesserBoundaryCorner() { return lesserBoundaryCorner; }
        public Location getGreaterBoundaryCorner() { return greaterBoundaryCorner; }
    }

    @Test
    void mapOutlineCoversExactlyTheProtectedBlocksForEitherWinding() {
        // Simple rectangle
        verifyPolygon(List.of(point(-8, -6), point(2, -6), point(2, 4), point(-8, 4)), -10, 4, -8, 6);
        // Concave L-shape including a redundant collinear node along the top edge
        verifyPolygon(List.of(point(-8, -6), point(-2, -6), point(2, -6), point(2, -2),
                point(-3, -2), point(-3, 4), point(-8, 4)), -10, 4, -8, 6);
        // U-shape with a 1-block-wide interior notch at x=1, z in [0..2]
        verifyPolygon(List.of(
                point(-2, 0), point(0, 0), point(0, 3), point(2, 3),
                point(2, 0), point(4, 0), point(4, 5), point(-2, 5)
        ), -4, 6, -2, 7);
    }

    @Test
    void rectangularFallbackCoversFullBlockFootprintIncludingSingleBlockClaim() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        GPBridge bridge = mock(GPBridge.class, CALLS_REAL_METHODS);
        doReturn(true).when(bridge).isAvailable();

        double[][] outline = bridge.getClaimOutline(new RectangularClaim(
                new Location(world, 10, 64, 20),
                new Location(world, 10, 64, 20)
        ));
        assertNotNull(outline);
        Path2D.Double rendered = toPath(outline);

        for (int x = 8; x <= 12; x++) {
            for (int z = 18; z <= 22; z++) {
                boolean expected = (x == 10 && z == 20);
                assertAllBlockProbes(rendered, expected, x, z, "single-block fallback");
            }
        }
    }

    private void verifyPolygon(List<OrthogonalPoint2i> corners, int minX, int maxX, int minZ, int maxZ) {
        for (boolean reverse : new boolean[]{false, true}) {
            List<OrthogonalPoint2i> path = new ArrayList<>(corners);
            if (reverse) Collections.reverse(path);
            path.add(path.getFirst());
            OrthogonalPolygon polygon = OrthogonalPolygon.fromClosedPath(path);

            GPBridge bridge = mock(GPBridge.class, CALLS_REAL_METHODS);
            doReturn(true).when(bridge).isAvailable();
            double[][] outline = bridge.getClaimOutline(new PolygonClaim(polygon));
            assertNotNull(outline);

            Path2D.Double rendered = toPath(outline);
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean expected = polygon.containsCell(x, z);
                    assertAllBlockProbes(rendered, expected, x, z, "reverse=" + reverse);
                }
            }
        }
    }

    private static Path2D.Double toPath(double[][] outline) {
        Path2D.Double rendered = new Path2D.Double();
        rendered.moveTo(outline[0][0], outline[1][0]);
        for (int i = 1; i < outline[0].length; i++) {
            rendered.lineTo(outline[0][i], outline[1][i]);
        }
        rendered.closePath();
        return rendered;
    }

    private static void assertAllBlockProbes(Path2D.Double rendered, boolean expected, int x, int z, String label) {
        double[] offsets = {0.01, 0.5, 0.99};
        for (double ox : offsets) {
            for (double oz : offsets) {
                assertEquals(expected, rendered.contains(x + ox, z + oz),
                        "Block (" + x + "," + z + ") probe (" + (x + ox) + "," + (z + oz) + ") [" + label + "]");
            }
        }
    }

    private static OrthogonalPoint2i point(int x, int z) {
        return new OrthogonalPoint2i(x, z);
    }
}
