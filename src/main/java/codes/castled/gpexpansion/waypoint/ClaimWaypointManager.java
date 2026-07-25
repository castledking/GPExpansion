package codes.castled.gpexpansion.waypoint;

import codes.castled.gpexpansion.GPExpansionPlugin;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes claims to locator bars, without any marker entities.
 *
 * <h2>How this works</h2>
 * Claim positions come from GP3D's in-memory claim data and reach clients two ways:
 *
 * <ul>
 *   <li><b>CrowBar clients</b> receive the full claim set on the {@code crowbar:claim_data} plugin
 *       channel — position, name, colour, ownership — and render it themselves.
 *   <li><b>Unmodified clients</b> (when {@code claim-waypoints.crowbar-only} is false) are sent
 *       vanilla {@code ClientboundTrackedWaypointPacket}s directly via {@link WaypointPacketBridge}.
 *       The packet is self-contained, so no armor stand, attribute, or loaded chunk needs to
 *       exist: claims render on the vanilla locator bar even when their terrain is unloaded.
 * </ul>
 *
 * <p>Earlier iterations spawned invisible armor stands carrying a {@code waypoint_transmit_range}
 * attribute and hid them per player. That worked, but inherited every constraint of the entity
 * pipeline — Paper's {@code canSee} patch for visibility, chunk tickets to survive unloads, and
 * attribute bounces to force connection refreshes. Sending the packets directly removes that whole
 * class of problems, at the cost of owning the lifecycle: every path that revokes visibility must
 * send a remove, which the per-player sent-state diff below guarantees.
 *
 * <h2>Refresh model</h2>
 * All triggers are discrete events — join, quit, world change, claim create/delete/resize/transfer,
 * trust change, rename, recolour, publish — with no polling. Each {@link #rebuildAll()} computes the
 * desired per-viewer set and diffs it against what that viewer was already sent, so a rebuild is
 * cheap and idempotent. Distance limits ({@code claim-waypoints.transmit-range}) are evaluated at
 * those refresh points, not continuously as players walk.
 */
public final class ClaimWaypointManager {

    /** Plugin channel CrowBar listens on for claim data. */
    public static final String CLAIM_DATA_CHANNEL = "crowbar:claim_data";

    private final GPExpansionPlugin plugin;
    private final NamespacedKey markerKey;

    /** What each online player currently has on their vanilla locator bar, keyed by claim ID. */
    private final Map<UUID, Map<String, SentWaypoint>> sentByPlayer = new ConcurrentHashMap<>();

    private boolean loggedBridgeFailure;

    /** One waypoint as last sent to a viewer. Equality is the resend check. */
    public record SentWaypoint(UUID waypointId, int x, int y, int z, @Nullable Integer color) {}

    public ClaimWaypointManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "claim_waypoint");
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        if (!enabled()) return;
        sweepOrphanedMarkers();
        rebuildAll();
    }

    /** Withdraws every sent waypoint so clients are not left with stale markers after a reload. */
    public void shutdown() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            withdrawAll(viewer);
        }
        sentByPlayer.clear();
    }

    /** Forgets a leaving player's sent state; their client discards waypoints on disconnect. */
    public void handleQuit(Player player) {
        sentByPlayer.remove(player.getUniqueId());
    }

    private boolean enabled() {
        return plugin.getConfigManager().areClaimWaypointsEnabled();
    }

    /**
     * Whether vanilla clients get waypoint packets. Requires the mode to be enabled in config and
     * the reflection bridge to have resolved against this server.
     */
    public boolean vanillaPacketsActive() {
        if (plugin.getConfigManager().areClaimWaypointsCrowbarOnly()) return false;
        if (WaypointPacketBridge.isAvailable()) return true;
        if (!loggedBridgeFailure) {
            loggedBridgeFailure = true;
            plugin.getLogger().warning(
                "claim-waypoints.crowbar-only is false but the waypoint packet bridge could not "
                    + "resolve against this server; falling back to CrowBar-only delivery. Cause: "
                    + WaypointPacketBridge.initError());
        }
        return false;
    }

    /**
     * Removes marker entities left behind by builds that used armor stands. Those were spawned
     * non-persistent, but an unclean shutdown can still write them into a region file.
     */
    private void sweepOrphanedMarkers() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClasses(ArmorStand.class)) {
                if (entity.getPersistentDataContainer().has(markerKey, PersistentDataType.STRING)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " claim waypoint marker entities from older builds.");
        }
    }

    // ------------------------------------------------------------------ recomputation

    /**
     * Recomputes every viewer's claim set, resends the CrowBar payload, and diffs the vanilla
     * waypoint packets against what each viewer already has.
     *
     * <p>Cost is proportional to (online players x claims each player may see) rather than to the
     * total claim count, so it stays cheap enough to run on every trigger event.
     */
    public void rebuildAll() {
        if (!enabled()) return;

        List<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());
        Map<String, Claim> wanted = new HashMap<>();
        Map<String, Set<UUID>> viewersByClaimId = new HashMap<>();

        for (Player viewer : viewers) {
            for (Claim claim : visibleClaimsFor(viewer)) {
                String claimId = String.valueOf(claim.getID());
                wanted.put(claimId, claim);
                viewersByClaimId.computeIfAbsent(claimId, k -> new HashSet<>()).add(viewer.getUniqueId());
            }
        }

        boolean sendPackets = vanillaPacketsActive();
        broadcastClaimData(wanted, viewersByClaimId, viewers, sendPackets);

        if (!sendPackets) {
            // Mode can flip at runtime via /gpx reload: withdraw anything previously sent.
            for (Player viewer : viewers) {
                withdrawAll(viewer);
            }
            return;
        }

        Set<UUID> online = new HashSet<>();
        for (Player viewer : viewers) {
            online.add(viewer.getUniqueId());
            syncViewer(viewer, wanted, viewersByClaimId);
        }
        sentByPlayer.keySet().retainAll(online);
    }

    /** Diffs one viewer's desired waypoint set against what they were already sent. */
    private void syncViewer(Player viewer, Map<String, Claim> wanted, Map<String, Set<UUID>> viewersByClaimId) {
        Map<String, SentWaypoint> desired = new HashMap<>();

        // The locatorBar game rule gates the vanilla pipeline server-side; sending packets past it
        // would override an explicit server choice. Absent means enabled, matching vanilla.
        boolean barEnabled = !Boolean.FALSE.equals(viewer.getWorld().getGameRuleValue(GameRule.LOCATOR_BAR));
        long range = plugin.getConfigManager().getClaimWaypointTransmitRange();

        if (barEnabled) {
            for (Map.Entry<String, Claim> entry : wanted.entrySet()) {
                String claimId = entry.getKey();
                if (!viewersByClaimId.getOrDefault(claimId, Set.of()).contains(viewer.getUniqueId())) continue;

                Location anchor = anchorFor(claimId, entry.getValue());
                if (anchor == null || anchor.getWorld() == null) continue;
                if (!anchor.getWorld().equals(viewer.getWorld())) continue;
                if (anchor.distanceSquared(viewer.getLocation()) > (double) range * range) continue;

                Color color = resolveColor(plugin.getClaimDataStore().getWaypointColor(claimId).orElse(null));
                desired.put(claimId, new SentWaypoint(
                    waypointUuid(claimId),
                    anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ(),
                    color == null ? null : color.asRGB()));
            }
        }

        Map<String, SentWaypoint> sent =
            sentByPlayer.computeIfAbsent(viewer.getUniqueId(), k -> new ConcurrentHashMap<>());

        for (Map.Entry<String, SentWaypoint> entry : new ArrayList<>(sent.entrySet())) {
            if (!desired.containsKey(entry.getKey())) {
                WaypointPacketBridge.sendRemove(viewer, entry.getValue().waypointId());
                sent.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, SentWaypoint> entry : desired.entrySet()) {
            if (!entry.getValue().equals(sent.get(entry.getKey()))) {
                // The client keys waypoints by identifier, so an add for a known identifier
                // replaces it in place — position and colour changes need no separate update.
                SentWaypoint waypoint = entry.getValue();
                WaypointPacketBridge.sendAdd(viewer, waypoint.waypointId(),
                    waypoint.x(), waypoint.y(), waypoint.z(), waypoint.color());
                sent.put(entry.getKey(), waypoint);
            }
        }
    }

    private void withdrawAll(Player viewer) {
        Map<String, SentWaypoint> sent = sentByPlayer.remove(viewer.getUniqueId());
        if (sent == null) return;
        for (SentWaypoint waypoint : sent.values()) {
            WaypointPacketBridge.sendRemove(viewer, waypoint.waypointId());
        }
    }

    /** Drops a claim's waypoint from every viewer, for deletion or abandonment. */
    public void removeClaim(long claimId) {
        String key = String.valueOf(claimId);
        for (Map.Entry<UUID, Map<String, SentWaypoint>> entry : sentByPlayer.entrySet()) {
            SentWaypoint sent = entry.getValue().remove(key);
            if (sent == null) continue;
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
                WaypointPacketBridge.sendRemove(viewer, sent.waypointId());
            }
        }
    }

    /**
     * Deterministic waypoint identifier per claim.
     *
     * <p>Name-based (version 3) UUIDs cannot collide with the random (version 4) UUIDs real
     * entities use, and determinism means CrowBar can be told the same identifier in its payload
     * so it suppresses the vanilla-rendered duplicate of a claim it draws itself.
     */
    private static UUID waypointUuid(String claimId) {
        return UUID.nameUUIDFromBytes(("gpexpansion:claim-waypoint:" + claimId).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ visibility

    /**
     * The claims a player may see a waypoint for: their own, any they hold trust on, published
     * claims, and (for permitted staff) admin claims.
     *
     * <p>{@link ClaimPermission#Access} is the weakest trust level and is implied by every higher
     * one, so a single check covers access, container, build and manage trust.
     */
    public List<Claim> visibleClaimsFor(Player viewer) {
        List<Claim> result = new ArrayList<>();
        if (GriefPrevention.instance == null || GriefPrevention.instance.dataStore == null) {
            return result;
        }

        boolean includeSubdivisions = plugin.getConfigManager().areClaimWaypointsShownForSubdivisions();
        boolean includeAdminClaims = plugin.getConfigManager().areClaimWaypointsShownForAdminClaims();
        UUID viewerId = viewer.getUniqueId();

        for (Claim claim : GriefPrevention.instance.dataStore.getClaims()) {
            if (claim == null || !claim.inDataStore) continue;
            if (!includeSubdivisions && claim.parent != null) continue;

            // A published claim is visible to every player, not just its owner and trusted.
            if (plugin.getClaimDataStore().isPublicWaypoint(String.valueOf(claim.getID()))) {
                result.add(claim);
                continue;
            }

            UUID owner = claim.getOwnerID();
            if (owner == null) {
                if (includeAdminClaims && viewer.hasPermission("griefprevention.claim.waypoint.admin")) {
                    result.add(claim);
                }
                continue;
            }
            if (owner.equals(viewerId) || claim.hasExplicitPermission(viewerId, ClaimPermission.Access)) {
                result.add(claim);
            }
        }
        return result;
    }

    /**
     * The claim's configured spawn if it has one, otherwise the centre of its bounds.
     *
     * <p>Y comes from the claim's own bounds rather than a surface scan so this stays callable
     * without touching chunk data.
     */
    private @Nullable Location anchorFor(String claimId, Claim claim) {
        Location spawn = plugin.getClaimDataStore().getSpawn(claimId).orElse(null);
        if (spawn != null && spawn.getWorld() != null) return spawn;

        Location lesser = claim.getLesserBoundaryCorner();
        Location greater = claim.getGreaterBoundaryCorner();
        if (lesser == null || greater == null || lesser.getWorld() == null) return null;

        double x = (Math.min(lesser.getBlockX(), greater.getBlockX()) + Math.max(lesser.getBlockX(), greater.getBlockX())) / 2.0 + 0.5;
        double z = (Math.min(lesser.getBlockZ(), greater.getBlockZ()) + Math.max(lesser.getBlockZ(), greater.getBlockZ())) / 2.0 + 0.5;
        double y = (Math.min(lesser.getBlockY(), greater.getBlockY()) + Math.max(lesser.getBlockY(), greater.getBlockY())) / 2.0;
        return new Location(lesser.getWorld(), x, y, z);
    }

    private @Nullable Color resolveColor(@Nullable String colorName) {
        if (colorName == null) return null;
        NamedTextColor named = NamedTextColor.NAMES.value(colorName.toLowerCase(Locale.ROOT));
        return named == null ? null : Color.fromRGB(named.value());
    }

    // ------------------------------------------------------------------ CrowBar payload

    /**
     * Tells CrowBar clients what each claim is called and where it is.
     *
     * <p>Sends each viewer the claims that viewer is allowed to see, with position, name and
     * colour. Scoping per viewer keeps claim names and locations from leaking to players who have
     * no access to them. Positions come from GP3D's in-memory claim data rather than from an
     * entity, so a claim keeps rendering when its terrain is unloaded. Clients without CrowBar
     * ignore the channel.
     */
    private void broadcastClaimData(Map<String, Claim> claims, Map<String, Set<UUID>> viewersByClaimId,
                                    List<Player> viewers, boolean vanillaPackets) {
        for (Player viewer : viewers) {
            com.google.gson.JsonArray entries = new com.google.gson.JsonArray();

            for (Map.Entry<String, Claim> entry : claims.entrySet()) {
                String claimId = entry.getKey();
                if (!viewersByClaimId.getOrDefault(claimId, Set.of()).contains(viewer.getUniqueId())) continue;

                Claim claim = entry.getValue();
                Location anchor = anchorFor(claimId, claim);
                if (anchor == null || anchor.getWorld() == null) continue;
                // Cross-world claims cannot be drawn on a bar that is relative to the viewer.
                if (!anchor.getWorld().equals(viewer.getWorld())) continue;

                com.google.gson.JsonObject json = new com.google.gson.JsonObject();
                json.addProperty("id", claimId);
                json.addProperty("name", displayNameFor(claimId, claim));
                json.addProperty("owned", viewer.getUniqueId().equals(claim.getOwnerID()));
                json.addProperty("x", anchor.getX());
                json.addProperty("y", anchor.getY());
                json.addProperty("z", anchor.getZ());

                Color color = resolveColor(plugin.getClaimDataStore().getWaypointColor(claimId).orElse(null));
                if (color != null) json.addProperty("color", color.asRGB());

                // Present only when this claim is also sent as a vanilla waypoint packet. CrowBar
                // uses it to suppress that duplicate so the claim is not drawn twice.
                if (vanillaPackets) {
                    json.addProperty("uuid", waypointUuid(claimId).toString());
                }

                entries.add(json);
            }

            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            root.add("claims", entries);
            try {
                viewer.sendPluginMessage(plugin, CLAIM_DATA_CHANNEL,
                    root.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // A client that never registered the channel is normal, not an error.
            }
        }
    }

    /** The claim's custom name if it has one, otherwise the owner's name. */
    private String displayNameFor(String claimId, Claim claim) {
        String custom = plugin.getClaimDataStore().getCustomName(claimId).orElse(null);
        if (custom != null && !custom.isBlank()) return custom;
        String owner = claim.getOwnerName();
        return owner != null && !owner.isBlank() ? owner + "'s Claim" : "Claim #" + claimId;
    }

    // ------------------------------------------------------------------ diagnostics

    /** The vanilla waypoints currently sent to a player, keyed by claim ID. For diagnostics only. */
    public Map<String, SentWaypoint> sentWaypoints(UUID playerId) {
        Map<String, SentWaypoint> sent = sentByPlayer.get(playerId);
        return sent == null ? Map.of() : Map.copyOf(sent);
    }
}
