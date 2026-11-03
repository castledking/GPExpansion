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
 *   <li><b>CrowBar clients</b> receive their claims on the {@code crowbar:claim_data} plugin
 *       channel — position, name, colour, ownership — and render them themselves. CrowBar's claim
 *       key and {@code /claim bar} share one mode, kept in step over {@code crowbar:claim_mode}.
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

    /**
     * Plugin channel for the {@code /claim bar} mode, both ways. A CrowBar client that registers it
     * follows the player's mode, and its claim key asks to change it rather than keeping a mode of
     * its own. See {@link #sendModeState} and {@link #handleModeRequest}.
     */
    public static final String CLAIM_MODE_CHANNEL = "crowbar:claim_mode";

    /** Who may change their mode, by command or by CrowBar's claim key. */
    public static final String BAR_PERMISSION = "griefprevention.claim.bar";

    private static final int MAX_MODE_REQUEST_BYTES = 256;

    private final GPExpansionPlugin plugin;
    private final NamespacedKey markerKey;

    /** What each online player currently has on their vanilla locator bar, keyed by claim ID. */
    private final Map<UUID, Map<String, SentWaypoint>> sentByPlayer = new ConcurrentHashMap<>();

    /**
     * The last claim key request handled for each player. Echoed in every mode message so CrowBar can
     * tell a confirmation from an older message that crossed a newer request on the wire.
     */
    private final Map<UUID, Integer> modeRequestsHandled = new ConcurrentHashMap<>();

    private boolean loggedBridgeFailure;

    /** One waypoint as last sent to a viewer. Equality is the resend check. */
    public record SentWaypoint(UUID waypointId, int x, int y, int z, @Nullable Integer color) {}

    /** Claim metadata shared by the CrowBar payload and the optional vanilla packet path. */
    private record PreparedClaim(String id, Claim claim, Location anchor, String name,
                                 @Nullable Integer color, UUID waypointId, boolean publicWaypoint) {}

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

    /**
     * Withdraws every sent waypoint so clients are not left with stale markers after a reload, and
     * hands CrowBar's claim key back to the client.
     */
    public void shutdown() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            withdrawFrom(viewer);
        }
        sentByPlayer.clear();
    }

    private void withdrawFrom(Player viewer) {
        sendModeState(viewer, false);
        sendClaimData(viewer, List.of(), false, false);
        withdrawAll(viewer);
    }

    /** Forgets a leaving player's sent state; their client discards waypoints on disconnect. */
    public void handleQuit(Player player) {
        sentByPlayer.remove(player.getUniqueId());
        modeRequestsHandled.remove(player.getUniqueId());
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
     * <p>Claims are walked once and their display metadata is prepared once per rebuild. Visibility
     * still has to be checked for each online viewer, but serialization and packet syncing operate
     * directly on that viewer's final list instead of repeatedly walking the union of all claims.
     */
    public void rebuildAll() {
        if (!enabled()) {
            shutdown();
            return;
        }

        List<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());
        boolean sendPackets = vanillaPacketsActive();
        Map<UUID, List<PreparedClaim>> claimsByViewer = prepareClaimsFor(viewers);

        Set<UUID> online = new HashSet<>();
        for (Player viewer : viewers) {
            online.add(viewer.getUniqueId());
            applyViewer(viewer, claimsByViewer.getOrDefault(viewer.getUniqueId(), List.of()), sendPackets);
        }
        sentByPlayer.keySet().retainAll(online);
    }

    /**
     * Refreshes one player without rebuilding every other online player's snapshot. This is the
     * normal path for joins, plugin-channel registration, and world changes.
     */
    public void rebuildViewer(Player viewer) {
        if (viewer == null || !viewer.isOnline()) return;
        if (!enabled()) {
            withdrawFrom(viewer);
            return;
        }

        boolean sendPackets = vanillaPacketsActive();
        List<PreparedClaim> claims = prepareClaimsFor(List.of(viewer))
            .getOrDefault(viewer.getUniqueId(), List.of());
        applyViewer(viewer, claims, sendPackets);
    }

    private void applyViewer(Player viewer, List<PreparedClaim> claims, boolean sendPackets) {
        boolean followsMode = followsBarMode(viewer);
        // The mode goes first so CrowBar already filters by it when the claims arrive.
        sendModeState(viewer, followsMode);
        sendClaimData(viewer, claims, sendPackets, followsMode);
        if (sendPackets) {
            syncViewer(viewer, claims);
        } else {
            // Mode can flip at runtime via /gpx reload: withdraw anything previously sent.
            withdrawAll(viewer);
        }
    }

    /** Walks GP3D's in-memory claims once and prepares each visible claim only once. */
    private Map<UUID, List<PreparedClaim>> prepareClaimsFor(List<Player> viewers) {
        Map<UUID, List<PreparedClaim>> result = new HashMap<>();
        for (Player viewer : viewers) {
            result.put(viewer.getUniqueId(), new ArrayList<>());
        }
        if (viewers.isEmpty() || GriefPrevention.instance == null
            || GriefPrevention.instance.dataStore == null) {
            return result;
        }

        boolean includeSubdivisions = plugin.getConfigManager().areClaimWaypointsShownForSubdivisions();
        boolean includeAdminClaims = plugin.getConfigManager().areClaimWaypointsShownForAdminClaims();

        for (Claim claim : GriefPrevention.instance.dataStore.getClaims()) {
            if (claim == null || !claim.inDataStore) continue;
            if (!includeSubdivisions && claim.parent != null) continue;

            String claimId = String.valueOf(claim.getID());
            boolean publicWaypoint = plugin.getClaimDataStore().isPublicWaypoint(claimId);
            List<Player> eligible = new ArrayList<>();
            for (Player viewer : viewers) {
                if (canViewClaim(viewer, claim, publicWaypoint, includeAdminClaims)) {
                    eligible.add(viewer);
                }
            }
            if (eligible.isEmpty()) continue;

            PreparedClaim prepared = prepareClaim(claimId, claim, publicWaypoint);
            if (prepared == null) continue;
            for (Player viewer : eligible) {
                result.get(viewer.getUniqueId()).add(prepared);
            }
        }
        return result;
    }

    private @Nullable PreparedClaim prepareClaim(String claimId, Claim claim, boolean publicWaypoint) {
        Location anchor = anchorFor(claimId, claim);
        if (anchor == null || anchor.getWorld() == null) return null;

        Color color = resolveColor(plugin.getClaimDataStore().getWaypointColor(claimId).orElse(null));
        return new PreparedClaim(claimId, claim, anchor, displayNameFor(claimId, claim),
            color == null ? null : color.asRGB(), waypointUuid(claimId), publicWaypoint);
    }

    /** Diffs one viewer's desired waypoint set against what they were already sent. */
    private void syncViewer(Player viewer, List<PreparedClaim> claims) {
        Map<String, SentWaypoint> desired = new HashMap<>();

        // The locatorBar game rule gates the vanilla pipeline server-side; sending packets past it
        // would override an explicit server choice. Absent means enabled, matching vanilla.
        // (GameRule.LOCATOR_BAR is read reflectively: the constant exists only in newer
        // Paper APIs, and the check compiles against the Spigot compat profile too.)
        GameRule<Boolean> locatorRule = locatorBarRule();
        boolean barEnabled = locatorRule == null
            || !Boolean.FALSE.equals(viewer.getWorld().getGameRuleValue(locatorRule));
        long range = plugin.getConfigManager().getClaimWaypointTransmitRange();
        // The bar mode applies to every client. CrowBar hides these markers by uuid and draws its
        // claims from the payload, by the same mode (see sendClaimData and sendModeState).
        WaypointBarMode mode = modeStore().get(viewer.getUniqueId());
        UUID viewerId = viewer.getUniqueId();

        if (barEnabled) {
            for (PreparedClaim claim : claims) {
                if (!allowedByMode(mode, claim, viewerId)) continue;
                Location anchor = claim.anchor();
                if (!anchor.getWorld().equals(viewer.getWorld())) continue;
                if (anchor.distanceSquared(viewer.getLocation()) > (double) range * range) continue;

                desired.put(claim.id(), new SentWaypoint(
                    claim.waypointId(),
                    anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ(),
                    claim.color()));
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
        for (Claim claim : GriefPrevention.instance.dataStore.getClaims()) {
            if (claim == null || !claim.inDataStore) continue;
            if (!includeSubdivisions && claim.parent != null) continue;

            boolean publicWaypoint = plugin.getClaimDataStore()
                .isPublicWaypoint(String.valueOf(claim.getID()));
            if (canViewClaim(viewer, claim, publicWaypoint, includeAdminClaims)) {
                result.add(claim);
            }
        }
        return result;
    }

    private boolean canViewClaim(Player viewer, Claim claim, boolean publicWaypoint,
                                 boolean includeAdminClaims) {
        // A published claim is visible to every player, not just its owner and trusted.
        if (publicWaypoint) return true;

        UUID owner = claim.getOwnerID();
        if (owner == null) {
            return includeAdminClaims
                && viewer.hasPermission("griefprevention.claim.waypoint.admin");
        }
        UUID viewerId = viewer.getUniqueId();
        return owner.equals(viewerId)
            || claim.hasExplicitPermission(viewerId, ClaimPermission.Access);
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
        if (colorName.startsWith("#")) {
            Integer rgb = codes.castled.gpexpansion.integration.map.MapColors.parseRgb(colorName);
            return rgb == null ? null : Color.fromRGB(rgb);
        }
        NamedTextColor named = NamedTextColor.NAMES.value(colorName.toLowerCase(Locale.ROOT));
        return named == null ? null : Color.fromRGB(named.value());
    }

    private WaypointModeStore modeStore() {
        return plugin.getWaypointModeStore();
    }

    /**
     * Whether a waypoint survives the viewer's bar mode.
     *
     * <p>Admin claims (no owner) never appear on the vanilla bar, whichever mode is active.
     * {@code both} matches the historical behaviour: own claims, anything the viewer holds trust
     * on, and published claims. {@code trusted} includes owned claims for simplicity. CrowBar
     * follows the same rules through {@link #crowBarShows}, except that its {@code both} keeps every
     * viewable claim.
     */
    private boolean allowedByMode(WaypointBarMode mode, PreparedClaim claim, UUID viewerId) {
        switch (mode) {
            case OFF:
                return false;
            case OWN: {
                UUID owner = claim.claim().getOwnerID();
                return owner != null && owner.equals(viewerId);
            }
            default: {
                UUID owner = claim.claim().getOwnerID();
                if (owner == null) return false;
                boolean personal = owner.equals(viewerId)
                    || claim.claim().hasExplicitPermission(viewerId, ClaimPermission.Access);
                if (mode == WaypointBarMode.TRUSTED) return personal;
                return personal || claim.publicWaypoint();
            }
        }
    }

    /** Whether CrowBar draws {@code claim} in {@code mode}: the bar's rules, but {@code both} keeps all. */
    private boolean crowBarShows(WaypointBarMode mode, PreparedClaim claim, UUID viewerId) {
        return mode == WaypointBarMode.BOTH || allowedByMode(mode, claim, viewerId);
    }

    // ------------------------------------------------------------------ CrowBar claim key

    /**
     * Whether this viewer's CrowBar follows their {@code /claim bar} mode: it registered
     * {@link #CLAIM_MODE_CHANNEL}, and the player may change their mode. A CrowBar without either
     * keeps its own claim key mode within what the payload sends, as older versions did.
     */
    private boolean followsBarMode(Player viewer) {
        return viewer.getListeningPluginChannels().contains(CLAIM_MODE_CHANNEL)
            && viewer.hasPermission(BAR_PERMISSION);
    }

    /**
     * Tells a CrowBar client the player's {@code /claim bar} mode on {@link #CLAIM_MODE_CHANNEL}:
     * {@code {"mode":"off","modes":["own","trusted","both","off"],"ack":3}}, where {@code modes} is
     * the claim key's cycle and {@code ack} the last request handled. An empty {@code modes} hands
     * the claim key back to the client.
     */
    private void sendModeState(Player viewer, boolean followsMode) {
        if (!viewer.getListeningPluginChannels().contains(CLAIM_MODE_CHANNEL)) return;
        UUID viewerId = viewer.getUniqueId();
        com.google.gson.JsonArray modes = new com.google.gson.JsonArray();
        if (followsMode) {
            for (String mode : WaypointBarMode.ARGUMENTS) modes.add(mode);
        }
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("mode", modeStore().get(viewerId).name().toLowerCase(Locale.ROOT));
        root.add("modes", modes);
        root.addProperty("ack", modeRequestsHandled.getOrDefault(viewerId, 0));
        sendJson(viewer, CLAIM_MODE_CHANNEL, root);
    }

    /**
     * Handles a {@link #CLAIM_MODE_CHANNEL} message: CrowBar's claim key asking for a
     * {@code /claim bar} mode, as {@code {"mode":"own","seq":4}}. The mode is set as the command
     * would set it. Either way the viewer is rebuilt, which answers with the mode now in force, so a
     * refused or unknown request puts the client back where the server is.
     */
    public void handleModeRequest(Player player, byte[] message) {
        if (player == null || !player.isOnline() || !enabled()) return;
        ModeRequest request = parseModeRequest(message);
        if (request == null) return;

        UUID playerId = player.getUniqueId();
        modeRequestsHandled.merge(playerId, request.seq(), Math::max);
        WaypointBarMode mode = request.mode();
        if (mode != null && player.hasPermission(BAR_PERMISSION) && mode != modeStore().get(playerId)) {
            modeStore().set(playerId, mode);
        }
        rebuildViewer(player);
    }

    /** A claim key request; {@code mode} is null when the client named a mode this server lacks. */
    record ModeRequest(@Nullable WaypointBarMode mode, int seq) {}

    /** Reads {@code {"mode":"own","seq":4}}, or null when the message is malformed. */
    static @Nullable ModeRequest parseModeRequest(byte[] message) {
        if (message == null || message.length > MAX_MODE_REQUEST_BYTES) return null;
        try {
            com.google.gson.JsonElement root =
                com.google.gson.JsonParser.parseString(new String(message, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return null;
            com.google.gson.JsonElement mode = root.getAsJsonObject().get("mode");
            com.google.gson.JsonElement seq = root.getAsJsonObject().get("seq");
            if (mode == null || !mode.isJsonPrimitive() || seq == null || !seq.isJsonPrimitive()) return null;
            int number = seq.getAsInt();
            if (number < 1) return null;
            return new ModeRequest(WaypointBarMode.fromString(mode.getAsString()), number);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ CrowBar payload

    /**
     * Tells CrowBar clients what each claim is called and where it is.
     *
     * <p>Sends each viewer the claims that viewer is allowed to see, with position, name and
     * colour. Scoping per viewer keeps claim names and locations from leaking to players who have
     * no access to them. Positions come from GP3D's in-memory claim data rather than from an entity,
     * so a claim keeps rendering when its terrain is unloaded. Clients without CrowBar ignore the
     * channel.
     *
     * <p>A client that follows the bar mode gets every viewable claim, each listing the modes that
     * show it, so its claim key switches without waiting for a new list. Any other CrowBar gets
     * the claims its {@code /claim bar} mode shows and picks within them with its claim key.
     */
    private void sendClaimData(Player viewer, List<PreparedClaim> claims, boolean vanillaPackets,
                               boolean followsMode) {
        com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
        UUID viewerId = viewer.getUniqueId();
        WaypointBarMode mode = modeStore().get(viewerId);

        for (PreparedClaim claim : claims) {
            // An older CrowBar replaces its claim list with this payload, so `off` empties it and
            // `own` or `trusted` narrow it. Either way the payload is a superset of syncViewer's
            // markers, so each vanilla marker still has its uuid here for CrowBar's duplicate
            // suppression.
            if (!followsMode && !crowBarShows(mode, claim, viewerId)) continue;
            Location anchor = claim.anchor();
            // Cross-world claims cannot be drawn on a bar that is relative to the viewer.
            if (!anchor.getWorld().equals(viewer.getWorld())) continue;

            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("id", claim.id());
            json.addProperty("name", claim.name());
            json.addProperty("owned", viewerId.equals(claim.claim().getOwnerID()));
            json.addProperty("x", anchor.getX());
            json.addProperty("y", anchor.getY());
            json.addProperty("z", anchor.getZ());

            if (claim.color() != null) json.addProperty("color", claim.color());

            // Present only when this claim is also sent as a vanilla waypoint packet. CrowBar
            // uses it to suppress that duplicate so the claim is not drawn twice.
            if (vanillaPackets) {
                json.addProperty("uuid", claim.waypointId().toString());
            }

            if (followsMode) {
                com.google.gson.JsonArray modes = new com.google.gson.JsonArray();
                for (WaypointBarMode candidate : WaypointBarMode.values()) {
                    if (crowBarShows(candidate, claim, viewerId)) {
                        modes.add(candidate.name().toLowerCase(Locale.ROOT));
                    }
                }
                json.add("modes", modes);
            }

            entries.add(json);
        }

        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("claims", entries);
        sendJson(viewer, CLAIM_DATA_CHANNEL, root);
    }

    private void sendJson(Player viewer, String channel, com.google.gson.JsonObject root) {
        try {
            viewer.sendPluginMessage(plugin, channel, root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // A client that never registered the channel is normal, not an error.
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

    /**
     * The vanilla locator-bar game rule, or null when this server does not define it
     * (the constant is a Paper API addition; plain Spigot builds lack it).
     */
    @SuppressWarnings("unchecked")
    public static @org.jetbrains.annotations.Nullable GameRule<Boolean> locatorBarRule() {
        try {
            java.lang.reflect.Field field = GameRule.class.getField("LOCATOR_BAR");
            return (GameRule<Boolean>) field.get(null);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    /** The vanilla waypoints currently sent to a player, keyed by claim ID. For diagnostics only. */
    public Map<String, SentWaypoint> sentWaypoints(UUID playerId) {
        Map<String, SentWaypoint> sent = sentByPlayer.get(playerId);
        return sent == null ? Map.of() : Map.copyOf(sent);
    }
}
