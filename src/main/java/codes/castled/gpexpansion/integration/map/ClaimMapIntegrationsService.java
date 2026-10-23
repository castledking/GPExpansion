package codes.castled.gpexpansion.integration.map;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronises claim boundaries on Dynmap and/or BlueMap.
 * <p>
 * The map-plugin renderer classes live in dedicated sub-packages and are loaded via
 * {@link Class#forName} only when the backing plugin is present and enabled; they are
 * never referenced at the top level here, so a server without either map plugin never
 * links against their classes. Claim geometry is read via {@link GPBridge} on the global
 * thread and handed to each active renderer as plain {@link ClaimShape} records.
 */
public class ClaimMapIntegrationsService {

    private static final String DYNAMAP_RENDERER = "codes.castled.gpexpansion.integration.map.dynmap.DynmapMapRenderer";
    private static final String BLUEMAP_RENDERER = "codes.castled.gpexpansion.integration.map.bluemap.BlueMapMapRenderer";
    private static final String DYNAMAP_STATUS_PLUGIN = "dynmap";
    private static final String BLUEMAP_STATUS_PLUGIN = "BlueMap";

    private final GPExpansionPlugin plugin;
    private final GPBridge bridge;

    private MapRenderer dynmapRenderer;
    private MapRenderer bluemapRenderer;
    private TaskHandle taskHandle;
    private final LazyPluginHook hook;

    public ClaimMapIntegrationsService(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.bridge = new GPBridge();
        this.hook = new LazyPluginHook();
    }

    /**
     * Create any renderers whose map plugin is already present and schedule the sync loop.
     */
    public void start() {
        boolean wantDynmap = plugin.getConfigManager().isDynmapIntegrationEnabled();
        boolean wantBlueMap = plugin.getConfigManager().isBlueMapIntegrationEnabled();

        hookRenderers();

        // If nothing was hooked yet, wait for a map plugin to enable later (we may load first).
        if (dynmapRenderer == null && bluemapRenderer == null) {
            if (wantDynmap || wantBlueMap) {
                Bukkit.getPluginManager().registerEvents(hook, plugin);
                plugin.debug("Map integrations: no map plugin enabled; waiting for Dynmap/BlueMap");
            } else {
                return;
            }
        }

        long intervalSeconds = wantDynmap
                ? plugin.getConfigManager().getDynmapUpdateIntervalSeconds()
                : plugin.getConfigManager().getBlueMapUpdateIntervalSeconds();
        long ticks = Math.max(20L, intervalSeconds * 20L);
        // Delay the first build so GP3D finishes loading claim data before the initial draw.
        taskHandle = SchedulerAdapter.runRepeatingGlobal(plugin, this::syncOnce, 120L, ticks);
    }

    private void syncOnce() {
        try {
            hookRenderers();
            if (dynmapRenderer == null && bluemapRenderer == null) {
                return;
            }
            FileConfiguration cfg = plugin.getConfig();
            int mainClaimColor = colorOr(cfg.getString("integrations.colors.main-claim", "#FFD700"), 0xFFD700);
            int adminClaimColor = colorOr(cfg.getString("integrations.colors.admin-claim", "#FF8C00"), 0xFF8C00);
            boolean showTrustList = cfg.getBoolean("integrations.dynmap.show-trust-list", true)
                    || cfg.getBoolean("integrations.bluemap.show-trust-list", true);
            List<ClaimShape> shapes = new ArrayList<>();
            for (Object claim : bridge.getAllClaimsIncludingChildren()) {
                ClaimShape shape = toShape(claim, mainClaimColor, adminClaimColor, showTrustList);
                if (shape != null) {
                    shapes.add(shape);
                }
            }
            if (dynmapRenderer != null) {
                try {
                    dynmapRenderer.renderAll(shapes);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Dynmap render failed: " + t.getMessage());
                }
            }
            if (bluemapRenderer != null) {
                try {
                    bluemapRenderer.renderAll(shapes);
                } catch (Throwable t) {
                    plugin.getLogger().warning("BlueMap render failed: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Map integration sync failed: " + t.getMessage());
        }
    }

    private ClaimShape toShape(Object claim, int mainClaimColor, int adminClaimColor, boolean showTrustList) {
        try {
            double[][] outline = bridge.getClaimOutline(claim);
            if (outline == null) {
                return null;
            }
            String id = bridge.getClaimId(claim).orElse(null);
            if (id == null || id.isBlank()) {
                return null;
            }
            String world = bridge.getClaimWorld(claim).orElse(null);
            if (world == null || world.isBlank()) {
                return null;
            }
            boolean isAdmin = bridge.isAdminClaim(claim);
            String ownerName = isAdmin
                    ? bridge.getGPMessageOr("OwnerNameForAdminClaims", "an administrator")
                    : bridge.getClaimOwnerName(claim);
            int markerColor = -1;
            String storedColor = plugin.getClaimDataStore().getWaypointColor(id).orElse(null);
            if (storedColor != null) {
                Integer rgb = MapColors.parseRgb(storedColor);
                if (rgb != null) {
                    markerColor = rgb;
                }
            }
            if (showTrustList) {
                List<String> managers = new ArrayList<>();
                List<String> builders = new ArrayList<>();
                List<String> containers = new ArrayList<>();
                List<String> accessors = new ArrayList<>();
                for (Map.Entry<UUID, EnumSet<GPBridge.TrustLevel>> entry : bridge.getTrustedPlayers(claim).entrySet()) {
                    String name = trustedPlayerName(entry.getKey());
                    EnumSet<GPBridge.TrustLevel> levels = entry.getValue();
                    if (levels.contains(GPBridge.TrustLevel.MANAGE)) managers.add(name);
                    if (levels.contains(GPBridge.TrustLevel.BUILD)) builders.add(name);
                    if (levels.contains(GPBridge.TrustLevel.CONTAINERS)) containers.add(name);
                    if (levels.contains(GPBridge.TrustLevel.ACCESS)) accessors.add(name);
                }
                return new ClaimShape(id, world, ownerName, isAdmin, outline[0], outline[1],
                        bridge.getClaimMinY(claim), bridge.getClaimMaxY(claim), markerColor,
                        managers, builders, containers, accessors);
            }
            return new ClaimShape(id, world, ownerName, isAdmin, outline[0], outline[1],
                    bridge.getClaimMinY(claim), bridge.getClaimMaxY(claim), markerColor,
                    List.of(), List.of(), List.of(), List.of());
        } catch (Throwable t) {
            if (codes.castled.gpexpansion.gp.GPBridge.isDebug()) {
                plugin.getLogger().warning("Skipping claim for map render: " + t.getMessage());
            }
            return null;
        }
    }

    /**
     * Best-effort display name for a trusted player: the cached Bukkit name, or GP's
     * translated "UnknownPlayerName" message with the UUID appended ("someone (uuid)").
     */
    private String trustedPlayerName(UUID uuid) {
        String cached = Bukkit.getOfflinePlayer(uuid).getName();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return bridge.getGPMessageOr("UnknownPlayerName", "someone") + " (" + uuid + ")";
    }

    private static int colorOr(String value, int fallback) {
        Integer parsed = MapColors.parseRgb(value);
        return parsed != null ? parsed : fallback;
    }

    private void hookRenderers() {
        if (dynmapRenderer == null
                && plugin.getConfigManager().isDynmapIntegrationEnabled()
                && isEnabled(DYNAMAP_STATUS_PLUGIN)) {
            try {
                dynmapRenderer = createDynmapRenderer();
                plugin.getLogger().info("Hooked Dynmap claim boundaries");
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to enable Dynmap integration: " + t.getMessage());
            }
        }
        if (bluemapRenderer == null
                && plugin.getConfigManager().isBlueMapIntegrationEnabled()
                && isEnabled(BLUEMAP_STATUS_PLUGIN)) {
            try {
                bluemapRenderer = createBlueMapRenderer();
                plugin.getLogger().info("Hooked BlueMap claim boundaries");
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to enable BlueMap integration: " + t.getMessage());
            }
        }
    }

    private MapRenderer createDynmapRenderer() throws Exception {
        Class<?> clazz = Class.forName(DYNAMAP_RENDERER);
        String layerName = plugin.getConfig().getString("integrations.dynmap.layer-name", "GriefPrevention");
        int strokeWeight = plugin.getConfig().getInt("integrations.dynmap.colors.stroke-weight", 3);
        double strokeOpacity = plugin.getConfig().getDouble("integrations.dynmap.colors.stroke-opacity", 1.0);
        double fillOpacity = plugin.getConfig().getDouble("integrations.dynmap.colors.fill-opacity", 0.0);
        boolean showTrustList = plugin.getConfig().getBoolean("integrations.dynmap.show-trust-list", true);
        int mainClaimColor = colorOr(plugin.getConfig().getString("integrations.colors.main-claim", "#FFD700"), 0xFFD700);
        int adminClaimColor = colorOr(plugin.getConfig().getString("integrations.colors.admin-claim", "#FF8C00"), 0xFF8C00);
        return (MapRenderer) clazz.getConstructor(
                        String.class, int.class, double.class, double.class, int.class, int.class, boolean.class)
                .newInstance(layerName, strokeWeight, strokeOpacity, fillOpacity, mainClaimColor, adminClaimColor, showTrustList);
    }

    private MapRenderer createBlueMapRenderer() throws Exception {
        Class<?> clazz = Class.forName(BLUEMAP_RENDERER);
        String markerSetId = plugin.getConfig().getString("integrations.bluemap.marker-set-id", "gpexpansion.claims");
        String layerName = plugin.getConfig().getString("integrations.bluemap.layer-name", "GriefPrevention");
        int lineWidth = plugin.getConfig().getInt("integrations.bluemap.colors.line-width", 2);
        double fillOpacity = plugin.getConfig().getDouble("integrations.bluemap.colors.fill-opacity", 0.0);
        boolean flatViewOnly = plugin.getConfig().getBoolean("integrations.bluemap.flat-view-only", true);
        boolean showTrustList = plugin.getConfig().getBoolean("integrations.bluemap.show-trust-list", true);
        int renderHeight = plugin.getConfig().getInt("integrations.bluemap.render-height", 63);
        boolean depthTest = plugin.getConfig().getBoolean("integrations.bluemap.depth-test", false);
        int mainClaimColor = colorOr(plugin.getConfig().getString("integrations.colors.main-claim", "#FFD700"), 0xFFD700);
        int adminClaimColor = colorOr(plugin.getConfig().getString("integrations.colors.admin-claim", "#FF8C00"), 0xFF8C00);
        return (MapRenderer) clazz.getConstructor(
                        String.class, String.class, int.class, double.class, int.class, int.class, boolean.class, boolean.class, int.class, boolean.class)
                .newInstance(markerSetId, layerName, lineWidth, fillOpacity, mainClaimColor, adminClaimColor, flatViewOnly, showTrustList, renderHeight, depthTest);
    }

    private static boolean isEnabled(String pluginName) {
        Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
        return p != null && p.isEnabled();
    }

    /**
     * Re-attempts to hook a map plugin that enables after GPExpansion
     * (GPExpansion may load before Dynmap/BlueMap).
     */
    private final class LazyPluginHook implements Listener {
        @EventHandler
        public void onPluginEnable(PluginEnableEvent event) {
            String name = event.getPlugin().getName();
            if ((DYNAMAP_STATUS_PLUGIN.equals(name) && plugin.getConfigManager().isDynmapIntegrationEnabled())
                    || (BLUEMAP_STATUS_PLUGIN.equals(name) && plugin.getConfigManager().isBlueMapIntegrationEnabled())) {
                hookRenderers();
            }
        }
    }

    /**
     * Cancel the task, clear all markers, and release renderers. Safe to call twice.
     */
    public void stop() {
        HandlerList.unregisterAll(hook);
        if (taskHandle != null) {
            taskHandle.cancel();
            taskHandle = null;
        }
        if (dynmapRenderer != null) {
            try {
                dynmapRenderer.clear();
            } catch (Throwable ignored) {
            }
            dynmapRenderer = null;
        }
        if (bluemapRenderer != null) {
            try {
                bluemapRenderer.clear();
            } catch (Throwable ignored) {
            }
            bluemapRenderer = null;
        }
    }
}