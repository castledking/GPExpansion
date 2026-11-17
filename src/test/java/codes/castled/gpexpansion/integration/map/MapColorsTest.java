package codes.castled.gpexpansion.integration.map;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MapColorsTest {

    private static final int MAIN_COLOR = 0xFFD700;
    private static final int ADMIN_COLOR = 0xFF8C00;

    private static ClaimShape claim(String ownerName, boolean isAdmin, int markerColor) {
        double[] xs = {0, 0, 10, 10};
        double[] zs = {0, 10, 10, 0};
        return new ClaimShape("1", "world", ownerName, isAdmin, xs, zs, 0, 255,
                markerColor, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void fixedModeUsesConfiguredMainAndAdminColorsAndRespectsOverrides() {
        assertEquals(MAIN_COLOR, MapColors.resolveLineColor(claim("Alice", false, -1), MAIN_COLOR, ADMIN_COLOR, false));
        assertEquals(ADMIN_COLOR, MapColors.resolveLineColor(claim("administrator", true, -1), MAIN_COLOR, ADMIN_COLOR, false));
        assertEquals(0x123456, MapColors.resolveLineColor(claim("Alice", false, 0x123456), MAIN_COLOR, ADMIN_COLOR, false));
        // Black (#000000) must be treated as an explicit override, not unset.
        assertEquals(0x000000, MapColors.resolveLineColor(claim("Alice", false, 0x000000), MAIN_COLOR, ADMIN_COLOR, false));
    }

    @Test
    void perPlayerModeHashesOwnerNameDeterministicallyAndIgnoresCase() {
        int alice = MapColors.resolveLineColor(claim("Alice", false, -1), MAIN_COLOR, ADMIN_COLOR, true);
        int aliceLower = MapColors.resolveLineColor(claim("alice", false, -1), MAIN_COLOR, ADMIN_COLOR, true);
        assertEquals(alice, aliceLower);

        // Explicit /claim color still wins, and admin claims still use adminClaimColor.
        assertEquals(0x00FFAA, MapColors.resolveLineColor(claim("Alice", false, 0x00FFAA), MAIN_COLOR, ADMIN_COLOR, true));
        assertEquals(ADMIN_COLOR, MapColors.resolveLineColor(claim("Alice", true, -1), MAIN_COLOR, ADMIN_COLOR, true));

        // Unknown / blank owners fall back to mainClaimColor.
        assertEquals(MAIN_COLOR, MapColors.resolveLineColor(claim("unknown", false, -1), MAIN_COLOR, ADMIN_COLOR, true));
        assertEquals(MAIN_COLOR, MapColors.resolveLineColor(claim("  ", false, -1), MAIN_COLOR, ADMIN_COLOR, true));

        // Similar names spread out rather than collapsing to the same colour.
        List<String> players = List.of("Player1", "Player2", "Player3", "Alice", "Bob", "Charlie", "Castled", "Steve");
        Set<Integer> colors = new HashSet<>();
        for (String name : players) {
            int c = MapColors.ownerColor(name, MAIN_COLOR);
            assertTrue(c >= 0 && c <= 0xFFFFFF, "Color out of RGB bounds for " + name);
            colors.add(c);
        }
        assertEquals(players.size(), colors.size(), "Expected distinct colours for distinct player names");
    }
}
