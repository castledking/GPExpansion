package codes.castled.gpexpansion.integration.map.dynmap;

import codes.castled.gpexpansion.integration.map.ClaimShape;
import codes.castled.gpexpansion.integration.map.MapColors;
import codes.castled.gpexpansion.integration.map.MapRenderer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws claim polygons on Dynmap as {@link AreaMarker}s inside a dedicated marker
 * set. Line and fill colours are resolved per claim (the claim's {@code /claim
 * color}, falling back to the configured defaults); shaped (GP3D) claims get a true
 * Y range while heightless (2D) claims stay on the surface. The info window shows
 * the owner and, when {@code show-trust-list} is enabled, the trust list.
 * Only loaded (via reflection in {@code ClaimMapIntegrationsService}) when Dynmap is
 * present and enabled, so this class may reference {@code org.dynmap.*} freely.
 */
public final class DynmapMapRenderer implements MapRenderer {

    public static final String MARKER_SET_ID = "gpexpansion.claims";

    private final DynmapAPI api;
    private final MarkerSet set;
    private final String layerName;
    private final int strokeWeight;
    private final double strokeOpacity;
    private final double fillOpacityOverride;
    private final int mainClaimColor;
    private final int adminClaimColor;
    private final boolean showTrustList;
    private final Map<String, AreaMarker> byId = new HashMap<>();

    public DynmapMapRenderer(
            String layerName,
            int strokeWeight,
            double strokeOpacity,
            double fillOpacityOverride,
            int mainClaimColor,
            int adminClaimColor,
            boolean showTrustList
    ) {
        Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dynmap == null || !dynmap.isEnabled()) {
            throw new IllegalStateException("Dynmap is not present or is not enabled");
        }
        this.api = (DynmapAPI) dynmap;
        this.layerName = layerName;
        this.strokeWeight = strokeWeight;
        this.strokeOpacity = strokeOpacity;
        this.fillOpacityOverride = fillOpacityOverride;
        this.mainClaimColor = mainClaimColor;
        this.adminClaimColor = adminClaimColor;
        this.showTrustList = showTrustList;

        MarkerAPI markerApi = api.getMarkerAPI();
        if (markerApi == null) {
            throw new IllegalStateException("Dynmap marker API unavailable");
        }
        MarkerSet existing = markerApi.getMarkerSet(MARKER_SET_ID);
        if (existing != null) {
            existing.deleteMarkerSet();
        }
        this.set = markerApi.createMarkerSet(MARKER_SET_ID, layerName, null, false);
        if (this.set == null) {
            throw new IllegalStateException("Unable to create Dynmap marker set " + MARKER_SET_ID);
        }
        this.set.setMarkerSetLabel(layerName);
    }

    @Override
    public void renderAll(Collection<ClaimShape> claims) {
        final Map<String, AreaMarker> next = new HashMap<>();
        for (ClaimShape claim : claims) {
            String markerId = markerId(claim.claimId());
            AreaMarker marker = byId.remove(markerId);
            if (marker == null) {
                marker = set.createAreaMarker(
                        markerId,
                        claim.ownerName(),
                        false,
                        claim.worldName(),
                        claim.xs(),
                        claim.zs(),
                        false);
                if (marker == null) {
                    continue;
                }
            } else {
                marker.setCornerLocations(claim.xs(), claim.zs());
                marker.setLabel(claim.ownerName());
            }
            if (claim.isShaped3D()) {
                marker.setRangeY((double) claim.maxY() + 1.0, (double) claim.minY());
            }
            int lineRgb = MapColors.resolveLineColor(claim, mainClaimColor, adminClaimColor);
            double fillAlpha = fillOpacityOverride > 0
                    ? fillOpacityOverride
                    : MapColors.deriveFillAlpha(lineRgb);
            marker.setLineStyle(strokeWeight, strokeOpacity, lineRgb);
            marker.setFillStyle(fillAlpha, MapColors.deriveFillRgb(lineRgb));
            marker.setDescription(infoWindow(claim));
            next.put(markerId, marker);
        }

        // Anything left in byId no longer exists: remove it.
        for (AreaMarker stale : byId.values()) {
            try {
                stale.deleteMarker();
            } catch (RuntimeException ignored) {
            }
        }
        byId.clear();
        byId.putAll(next);
    }

    @Override
    public void clear() {
        try {
            set.deleteMarkerSet();
        } catch (RuntimeException ignored) {
        }
        byId.clear();
    }

    private static String markerId(String claimId) {
        return "gpexpansion_" + claimId.replaceAll("[^A-Za-z0-9_\\-.]", "_");
    }

    private String infoWindow(ClaimShape claim) {
        StringBuilder html = new StringBuilder(
                "<div class=\"infowindow\"><span style=\"font-weight:bold;\">")
                .append(MapColors.esc(claim.ownerName())).append("</span><br/>");
        if (showTrustList) {
            appendTrustRow(html, "Managers", claim.managers());
            appendTrustRow(html, "Builders", claim.builders());
            appendTrustRow(html, "Containers", claim.containers());
            appendTrustRow(html, "Accessors", claim.accessors());
        }
        html.append("<span style=\"color:#888;\">GriefPrevention</span></div>");
        return html.toString();
    }

    private static void appendTrustRow(StringBuilder html, String role, java.util.List<String> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        html.append("<b>").append(MapColors.esc(role)).append(":</b> ");
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) {
                html.append(", ");
            }
            html.append(MapColors.esc(players.get(i)));
        }
        html.append("<br/>");
    }
}