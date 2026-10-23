package codes.castled.gpexpansion.integration.map.bluemap;

import codes.castled.gpexpansion.integration.map.ClaimShape;
import codes.castled.gpexpansion.integration.map.MapColors;
import codes.castled.gpexpansion.integration.map.MapRenderer;
import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws claim polygons on BlueMap. Shaped (GP3D) claims are rendered as
 * {@link ExtrudeMarker}s with their true height; the heightless (2D) claims are
 * drawn as flat {@link ShapeMarker}s at a single configurable Y
 * ({@code integrations.bluemap.render-height}) when {@code flat-view-only} is
 * enabled, instead of the default whole-world-height extrude that shows up as
 * jarring columns in the 3D view. Each world sees its own {@link MarkerSet}
 * carrying that world's claims; the set is fully replaced on every refresh and
 * removed on {@link #clear()}. Only loaded (via reflection in
 * {@code ClaimMapIntegrationsService}) when BlueMap is present and enabled.
 *
 * <p>{@code integrations.bluemap.depth-test} controls terrain occlusion. Left off
 * (the default) outlines and fills draw through terrain, so claims stay visible
 * inside caves, behind hills and under cover — the same xray behaviour BlueBridge
 * exposes via its {@code defaultDepthCheck} setting.
 */
public final class BlueMapMapRenderer implements MapRenderer {

    /** BlueMap injects the label as HTML, so trust-list breaks are tags, not newlines. */
    private static final String BR = "<br>";

    private final String markerSetId;
    private final String layerName;
    private final int lineWidth;
    private final double fillOpacityOverride;
    private final int mainClaimColor;
    private final int adminClaimColor;
    private final boolean flatViewOnly;
    private final boolean showTrustList;
    private final int renderHeight;
    private final boolean depthTestEnabled;

    public BlueMapMapRenderer(
            String markerSetId,
            String layerName,
            int lineWidth,
            double fillOpacityOverride,
            int mainClaimColor,
            int adminClaimColor,
            boolean flatViewOnly,
            boolean showTrustList,
            int renderHeight,
            boolean depthTestEnabled
    ) {
        this.markerSetId = markerSetId;
        this.layerName = layerName;
        this.lineWidth = lineWidth;
        this.fillOpacityOverride = fillOpacityOverride;
        this.mainClaimColor = mainClaimColor;
        this.adminClaimColor = adminClaimColor;
        this.flatViewOnly = flatViewOnly;
        this.showTrustList = showTrustList;
        this.renderHeight = renderHeight;
        this.depthTestEnabled = depthTestEnabled;
    }

    @Override
    public void renderAll(Collection<ClaimShape> claims) {
        BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
        if (api == null) {
            return;
        }
        Map<String, Set<String>> bukkitNamesById = bukkitWorldNamesById(api);
        for (BlueMapMap map : api.getMaps()) {
            Set<String> worldNames = bukkitNamesById.get(map.getWorld().getId());
            MarkerSet set = MarkerSet.builder()
                    .label(layerName)
                    .toggleable(true)
                    .defaultHidden(false)
                    .sorting(map.getMarkerSets().size())
                    .build();
            for (ClaimShape claim : claims) {
                if (worldNames == null || !worldNames.contains(claim.worldName())) {
                    continue;
                }
                int lineRgb = MapColors.resolveLineColor(claim, mainClaimColor, adminClaimColor);
                Color lineColor = color(lineRgb, 1.0f);
                float fillAlpha = fillOpacityOverride > 0
                        ? (float) fillOpacityOverride
                        : (float) MapColors.deriveFillAlpha(lineRgb);
                Color fillColor = color(MapColors.deriveFillRgb(lineRgb), fillAlpha);
                Marker marker;
                if (claim.isShaped3D()) {
                    marker = ExtrudeMarker.builder()
                            .label(label(claim))
                            .shape(shape(claim), (float) claim.minY(), (float) claim.maxY())
                            .lineColor(lineColor)
                            .lineWidth(lineWidth)
                            .fillColor(fillColor)
                            .depthTestEnabled(depthTestEnabled)
                            .build();
                } else if (flatViewOnly) {
                    marker = ShapeMarker.builder()
                            .label(label(claim))
                            .shape(shape(claim), (float) renderHeight)
                            .lineColor(lineColor)
                            .lineWidth(lineWidth)
                            .fillColor(fillColor)
                            .depthTestEnabled(depthTestEnabled)
                            .build();
                } else {
                    marker = ExtrudeMarker.builder()
                            .label(label(claim))
                            .shape(shape(claim), (float) claim.minY(), (float) claim.maxY())
                            .lineColor(lineColor)
                            .lineWidth(lineWidth)
                            .fillColor(fillColor)
                            .depthTestEnabled(depthTestEnabled)
                            .build();
                }
                set.put(claim.claimId(), marker);
            }
            map.getMarkerSets().put(markerSetId, set);
        }
    }

    @Override
    public void clear() {
        BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
        if (api == null) {
            return;
        }
        for (BlueMapMap map : api.getMaps()) {
            map.getMarkerSets().remove(markerSetId);
        }
    }

    /**
     * Maps each BlueMap world id to <em>every</em> Bukkit world name that resolves to
     * it. Claim shapes carry Bukkit world names, while BlueMap's world id is an
     * internal format ({@code path#namespace:dimension}), so the two are bridged
     * through the API instead of parsed.
     *
     * <p>The mapping is deliberately many-to-one. BlueMap derives a Bukkit world's
     * dimension from {@code World#getEnvironment()} and treats everything that is
     * not nether or end as {@code minecraft:overworld}, while its id is built from the
     * world <em>folder</em>. On the modern single-world-folder layout every dimension
     * of a world shares one folder, so {@code world}, {@code world_the_nether} and
     * every custom dimension resolve as:
     *
     * <pre>
     *   world            -&gt; world#minecraft:overworld
     *   world_the_nether -&gt; world#minecraft:the_nether
     *   world_resource   -&gt; world#minecraft:overworld   (custom dimension)
     * </pre>
     *
     * <p>Collapsing them to a single {@code String} lets the last world win and
     * silently starves the overworld map of its claims, so all names are kept and a
     * claim is drawn when its world is among them. Worlds BlueMap cannot tell apart
     * therefore share a map rather than losing their claims.
     */
    private static Map<String, Set<String>> bukkitWorldNamesById(BlueMapAPI api) {
        Map<String, Set<String>> names = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            api.getWorld(world).ifPresent(blueMapWorld -> names
                    .computeIfAbsent(blueMapWorld.getId(), id -> new LinkedHashSet<>())
                    .add(world.getName()));
        }
        return names;
    }

    private static Shape shape(ClaimShape claim) {
        double[] xs = claim.xs();
        double[] zs = claim.zs();
        List<Vector2d> pts = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            pts.add(new Vector2d(xs[i], zs[i]));
        }
        return new Shape(pts);
    }

    /**
     * The marker's popup label: {@code "owner's claim"} plus the trust list when
     * {@code show-trust-list} is enabled. BlueMap assigns the label with
     * {@code innerHTML}, so the trust lines are separated with {@code <br>} — a bare
     * newline would be collapsed into a single line by the browser.
     */
    private String label(ClaimShape claim) {
        StringBuilder label = new StringBuilder(MapColors.esc(claim.ownerName())).append("'s claim");
        if (showTrustList) {
            label.append(BR).append(trustLine("Managers", claim.managers()));
            label.append(BR).append(trustLine("Builders", claim.builders()));
            label.append(BR).append(trustLine("Containers", claim.containers()));
            label.append(BR).append(trustLine("Accessors", claim.accessors()));
        }
        return label.toString();
    }

    private static String trustLine(String role, List<String> players) {
        if (players == null || players.isEmpty()) {
            return MapColors.esc(role) + ": -";
        }
        StringBuilder line = new StringBuilder(MapColors.esc(role)).append(": ");
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) {
                line.append(", ");
            }
            line.append(MapColors.esc(players.get(i)));
        }
        return line.toString();
    }

    private static Color color(int rgb, float alpha) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
    }
}