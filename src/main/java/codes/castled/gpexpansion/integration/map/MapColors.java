package codes.castled.gpexpansion.integration.map;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;

/**
 * Colour helpers shared by the Dynmap/BlueMap renderers and the sync service.
 *
 * <p>Everything works on 0xRRGGBB ints so the renderers stay free of shared state.
 * Line colours come from the claim's {@code /claim color} (a named Adventure colour
 * or {@code #RRGGBB[A]}) or fall back to the per-integration defaults; the fill is
 * then derived by lightening the line and giving it an alpha that depends on how
 * bright the colour is, so dark colours stay readable over map tiles.
 */
public final class MapColors {

    private MapColors() {}

    /**
     * Parse a named Adventure colour or a {@code #RGB}/{@code #RRGGBB}/{@code #RRGGBBAA}
     * hex value into 0xRRGGBB. Returns {@code null} when the value is not a colour.
     */
    public static Integer parseRgb(String value) {
        if (value == null || value.isBlank()) return null;
        String s = value.trim();
        if (s.startsWith("#")) {
            String hex = s.substring(1);
            if (hex.length() == 3 || hex.length() == 4) {
                StringBuilder expanded = new StringBuilder(6);
                for (int i = 0; i < 3; i++) {
                    expanded.append(hex.charAt(i)).append(hex.charAt(i));
                }
                hex = expanded.toString();
            }
            if (hex.length() != 6 && hex.length() != 8) return null;
            try {
                return (int) (Long.parseLong(hex, 16) & 0xFFFFFFL);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        NamedTextColor named = NamedTextColor.NAMES.value(s.toLowerCase(Locale.ROOT));
        return named == null ? null : named.value();
    }

    /**
     * The line colour for a claim: its stored {@code /claim color} if set, otherwise the
     * admin or main claim default.
     */
    public static int resolveLineColor(ClaimShape claim, int mainClaimColor, int adminClaimColor) {
        if (claim.markerColor() > 0) return claim.markerColor();
        return claim.isAdmin() ? adminClaimColor : mainClaimColor;
    }

    /**
     * A fill colour derived from the line colour by lightening it 25% toward white.
     */
    public static int deriveFillRgb(int lineRgb) {
        int r = (lineRgb >> 16) & 0xFF;
        int g = (lineRgb >> 8) & 0xFF;
        int b = lineRgb & 0xFF;
        r = r + (255 - r) * 25 / 100;
        g = g + (255 - g) * 25 / 100;
        b = b + (255 - b) * 25 / 100;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * A fill opacity derived from the line colour's luminance: brighter colours get a
     * lighter touch and dark colours need more so they stay visible over map tiles.
     * Range is clamped to {@code [0.15, 0.5]}.
     */
    public static double deriveFillAlpha(int lineRgb) {
        int r = (lineRgb >> 16) & 0xFF;
        int g = (lineRgb >> 8) & 0xFF;
        int b = lineRgb & 0xFF;
        double luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return Math.min(0.5, Math.max(0.15, 0.15 + 0.35 * (1.0 - luma)));
    }

    /** Escape text for use inside HTML (info windows / marker labels). */
    public static String esc(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}