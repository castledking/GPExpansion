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
        return resolveLineColor(claim, mainClaimColor, adminClaimColor, false);
    }

    /**
     * The line colour for a claim: its stored {@code /claim color} if set, otherwise
     * {@code adminClaimColor} for admin claims, or—when {@code perPlayerColors} is enabled—a
     * deterministic colour hashed from {@link ClaimShape#ownerName()}, falling back to
     * {@code mainClaimColor}.
     */
    public static int resolveLineColor(
            ClaimShape claim,
            int mainClaimColor,
            int adminClaimColor,
            boolean perPlayerColors
    ) {
        if (claim.markerColor() >= 0) return claim.markerColor();
        if (claim.isAdmin()) return adminClaimColor;
        if (perPlayerColors) return ownerColor(claim.ownerName(), mainClaimColor);
        return mainClaimColor;
    }

    /**
     * Derive a deterministic, vivid {@code 0xRRGGBB} colour from a claim owner's name.
     * Uses FNV-1a + SplitMix64 mixing so similar usernames spread cleanly around the hue
     * wheel instead of clustering, and clamps saturation/brightness to a readable band.
     */
    public static int ownerColor(String ownerName, int fallbackColor) {
        if (ownerName == null) return fallbackColor;
        String normalized = ownerName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "unknown".equals(normalized)) {
            return fallbackColor;
        }
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < normalized.length(); i++) {
            h ^= normalized.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= (h >>> 30);
        h *= 0xbf58476d1ce4e5b9L;
        h ^= (h >>> 27);
        h *= 0x94d049bb133111ebL;
        h ^= (h >>> 31);

        float hue = ((h >>> 40) & 0xFFFFFFL) / (float) (1 << 24);
        float sat = 0.65f + (((h >>> 16) & 0xFFL) / 255.0f) * 0.25f; // [0.65, 0.90]
        float val = 0.75f + ((h & 0xFFL) / 255.0f) * 0.20f;         // [0.75, 0.95]
        return hsvToRgb(hue, sat, val);
    }

    private static int hsvToRgb(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6.0f;
        int sector = (int) Math.floor(hh);
        float f = hh - sector;
        float p = v * (1.0f - s);
        float q = v * (1.0f - s * f);
        float t = v * (1.0f - s * (1.0f - f));
        float r;
        float g;
        float b;
        switch (sector % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        int ri = Math.min(255, Math.max(0, Math.round(r * 255.0f)));
        int gi = Math.min(255, Math.max(0, Math.round(g * 255.0f)));
        int bi = Math.min(255, Math.max(0, Math.round(b * 255.0f)));
        return (ri << 16) | (gi << 8) | bi;
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