package codes.castled.gpexpansion.waypoint;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.command.ClaimCommand;
import codes.castled.gpexpansion.storage.ClaimDataStore;
import codes.castled.gpexpansion.util.Config;
import codes.castled.gpexpansion.util.Messages;
import codes.castled.gpexpansion.util.Mini;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaimBarVisibilityTest {
    @TempDir Path dataFolder;

    private GPExpansionPlugin plugin;
    private Config config;
    private ClaimWaypointManager manager;
    private WaypointModeStore modes;
    private Player viewer;
    private GriefPrevention originalGp;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<Mini> messages;
    private MockedStatic<WaypointPacketBridge> packets;
    private MockedStatic<ClaimWaypointManager> gameRule;

    @BeforeEach
    void setUp() {
        plugin = mock(GPExpansionPlugin.class, invocation -> {
            // Newer Paper asks the plugin for its namespace rather than deriving its name.
            if (invocation.getMethod().getName().equals("namespace")) return "gpexpansion";
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(plugin.getName()).thenReturn("GPExpansion");
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getMessages()).thenReturn(mock(Messages.class));
        config = mock(Config.class);
        when(plugin.getConfigManager()).thenReturn(config);
        when(config.areClaimWaypointsEnabled()).thenReturn(true);
        when(config.getClaimWaypointTransmitRange()).thenReturn(60000000);
        ClaimDataStore claimData = mock(ClaimDataStore.class);
        when(plugin.getClaimDataStore()).thenReturn(claimData);
        when(claimData.isPublicWaypoint("3")).thenReturn(true);

        World world = mock(World.class);
        viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(viewer.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(viewer.getListeningPluginChannels()).thenReturn(Set.of());
        when(viewer.hasPermission("griefprevention.claim.bar")).thenReturn(true);

        Claim owned = claim(world, 1L, viewer.getUniqueId());
        Claim trusted = claim(world, 2L, UUID.randomUUID());
        when(trusted.hasExplicitPermission(viewer.getUniqueId(), ClaimPermission.Access)).thenReturn(true);
        Claim published = claim(world, 3L, UUID.randomUUID());
        originalGp = GriefPrevention.instance;
        GriefPrevention gp = mock(GriefPrevention.class);
        gp.dataStore = mock(DataStore.class);
        when(gp.dataStore.getClaims()).thenReturn(List.of(owned, trusted, published));
        GriefPrevention.instance = gp;

        modes = new WaypointModeStore(plugin);
        when(plugin.getWaypointModeStore()).thenReturn(modes);
        manager = new ClaimWaypointManager(plugin);
        when(plugin.getClaimWaypointManager()).thenReturn(manager);

        packets = mockStatic(WaypointPacketBridge.class);
        packets.when(WaypointPacketBridge::isAvailable).thenReturn(true);
        // Game-rule registry lookup requires a running Paper server. Exercise the default
        // enabled rule here while keeping all other manager methods real.
        gameRule = mockStatic(ClaimWaypointManager.class, invocation -> {
            if (invocation.getMethod().getName().equals("locatorBarRule")) return null;
            return invocation.callRealMethod();
        });
        messages = mockStatic(Mini.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(viewer));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) bukkit.close();
        if (messages != null) messages.close();
        if (packets != null) packets.close();
        if (gameRule != null) gameRule.close();
        GriefPrevention.instance = originalGp;
    }

    @Test
    void claimBarOffWithdrawsExistingDotsForAClientWithoutCrowBar() {
        manager.rebuildViewer(viewer);
        var previous = manager.sentWaypoints(viewer.getUniqueId());
        assertEquals(Set.of("1", "2", "3"), previous.keySet());

        new ClaimCommand(plugin).run(viewer, "claim", "claim", new String[]{"bar", "off"});

        assertEquals(WaypointBarMode.OFF, modes.get(viewer.getUniqueId()));
        assertEquals(WaypointBarMode.OFF, new WaypointModeStore(plugin).get(viewer.getUniqueId()));
        assertTrue(manager.sentWaypoints(viewer.getUniqueId()).isEmpty());
        for (var waypoint : previous.values()) {
            packets.verify(() -> WaypointPacketBridge.sendRemove(viewer, waypoint.waypointId()));
        }
        manager.rebuildAll();
        assertTrue(manager.sentWaypoints(viewer.getUniqueId()).isEmpty(), "Later rebuilds must keep off applied");

        new ClaimCommand(plugin).run(viewer, "claim", "claim", new String[]{"bar", "trusted"});
        assertEquals(Set.of("1", "2"), manager.sentWaypoints(viewer.getUniqueId()).keySet());
    }

    @Test
    void switchingToCrowBarOnlyWithdrawsAllPreviouslySentVanillaDots() {
        manager.rebuildViewer(viewer);
        var previous = manager.sentWaypoints(viewer.getUniqueId());
        when(config.areClaimWaypointsCrowbarOnly()).thenReturn(true);

        manager.rebuildAll();

        assertTrue(manager.sentWaypoints(viewer.getUniqueId()).isEmpty());
        for (var waypoint : previous.values()) {
            packets.verify(() -> WaypointPacketBridge.sendRemove(viewer, waypoint.waypointId()));
        }
    }

    @Test
    void disablingClaimWaypointsWithdrawsPreviouslySentDotsOnRefresh() {
        manager.rebuildViewer(viewer);
        var previous = manager.sentWaypoints(viewer.getUniqueId());
        assertFalse(previous.isEmpty());
        when(config.areClaimWaypointsEnabled()).thenReturn(false);

        manager.rebuildAll();

        assertTrue(manager.sentWaypoints(viewer.getUniqueId()).isEmpty());
        for (var waypoint : previous.values()) {
            packets.verify(() -> WaypointPacketBridge.sendRemove(viewer, waypoint.waypointId()));
        }
    }

    @Test
    void claimBarOffEmptiesTheCrowBarPayloadAndSendsNoMarkers() {
        becomeCrowBarClient();
        manager.rebuildViewer(viewer);
        assertEquals(Set.of("1", "2", "3"), field(lastPayload(), "id"));

        new ClaimCommand(plugin).run(viewer, "claim", "claim", new String[]{"bar", "off"});

        assertTrue(lastPayload().isEmpty(), "CrowBar must be sent an empty claim list for off");
        assertTrue(manager.sentWaypoints(viewer.getUniqueId()).isEmpty(), "and no vanilla markers either");
    }

    @Test
    void crowBarPayloadFollowsTheBarModeAndLabelsEveryVanillaMarker() {
        becomeCrowBarClient();
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("own", Set.of("1"));
        expected.put("trusted", Set.of("1", "2"));
        expected.put("both", Set.of("1", "2", "3"));

        for (Map.Entry<String, Set<String>> mode : expected.entrySet()) {
            new ClaimCommand(plugin).run(viewer, "claim", "claim", new String[]{"bar", mode.getKey()});

            JsonArray payload = lastPayload();
            var markers = manager.sentWaypoints(viewer.getUniqueId());
            assertEquals(mode.getValue(), field(payload, "id"), "CrowBar claims for " + mode.getKey());
            assertEquals(mode.getValue(), markers.keySet(), "vanilla markers for " + mode.getKey());
            // CrowBar only suppresses a vanilla marker whose uuid the payload carries; any other
            // marker would be drawn again as an unlabelled dot.
            Set<String> labelled = field(payload, "uuid");
            for (var marker : markers.values()) {
                assertTrue(labelled.contains(marker.waypointId().toString()),
                    "vanilla marker missing from the CrowBar payload in mode " + mode.getKey());
            }
        }
    }

    @Test
    void aCrowBarThatFollowsTheModeGetsEveryClaimWithTheModesThatShowIt() {
        becomeModeFollowingCrowBar();
        manager.rebuildViewer(viewer);

        JsonObject state = lastModeState();
        assertEquals("both", state.get("mode").getAsString());
        assertEquals(List.of("own", "trusted", "both", "off"), strings(state.getAsJsonArray("modes")),
            "the claim key cycles /claim bar's modes in their usual order");
        assertEquals(0, state.get("ack").getAsInt());

        Map<String, List<String>> shownIn = claimModes(lastPayload());
        assertEquals(List.of("own", "trusted", "both"), shownIn.get("1"));
        assertEquals(List.of("trusted", "both"), shownIn.get("2"));
        assertEquals(List.of("both"), shownIn.get("3"));
    }

    @Test
    void claimBarOffKeepsTheClaimListSoTheKeyCanTurnClaimsBackOn() {
        becomeModeFollowingCrowBar();
        manager.rebuildViewer(viewer);

        new ClaimCommand(plugin).run(viewer, "claim", "claim", new String[]{"bar", "off"});

        assertEquals("off", lastModeState().get("mode").getAsString());
        assertEquals(Set.of("1", "2", "3"), field(lastPayload(), "id"),
            "off is a mode the client applies, not an empty list it would report as no claims");
        assertTrue(manager.sentWaypoints(viewer.getUniqueId()).isEmpty(), "and no vanilla markers");
    }

    @Test
    void theClaimKeySetsTheBarModeAndIsConfirmed() {
        becomeModeFollowingCrowBar();
        modes.set(viewer.getUniqueId(), WaypointBarMode.OFF);
        manager.rebuildViewer(viewer);

        manager.handleModeRequest(viewer, request("own", 1));

        assertEquals(WaypointBarMode.OWN, modes.get(viewer.getUniqueId()));
        assertEquals(WaypointBarMode.OWN, new WaypointModeStore(plugin).get(viewer.getUniqueId()),
            "saved exactly as /claim bar saves it");
        JsonObject state = lastModeState();
        assertEquals("own", state.get("mode").getAsString());
        assertEquals(1, state.get("ack").getAsInt());
        assertEquals(Set.of("1"), manager.sentWaypoints(viewer.getUniqueId()).keySet(),
            "the vanilla markers follow the new mode");
    }

    @Test
    void refusedAndUnknownRequestsAreAnsweredWithTheModeInForce() {
        becomeModeFollowingCrowBar();
        manager.rebuildViewer(viewer);

        manager.handleModeRequest(viewer, request("sideways", 1));
        assertEquals(WaypointBarMode.BOTH, modes.get(viewer.getUniqueId()));
        assertEquals("both", lastModeState().get("mode").getAsString());
        assertEquals(1, lastModeState().get("ack").getAsInt());

        when(viewer.hasPermission(ClaimWaypointManager.BAR_PERMISSION)).thenReturn(false);
        manager.handleModeRequest(viewer, request("off", 2));

        assertEquals(WaypointBarMode.BOTH, modes.get(viewer.getUniqueId()));
        JsonObject state = lastModeState();
        assertEquals(2, state.get("ack").getAsInt());
        assertTrue(state.getAsJsonArray("modes").isEmpty(),
            "without the permission the claim key goes back to being the client's own");
        assertFalse(lastPayload().get(0).getAsJsonObject().has("modes"));
    }

    @Test
    void malformedRequestsAreIgnored() {
        becomeModeFollowingCrowBar();

        for (String bad : List.of("not json", "[]", "{\"mode\":\"own\"}", "{\"mode\":\"own\",\"seq\":0}")) {
            manager.handleModeRequest(viewer, bad.getBytes(StandardCharsets.UTF_8));
        }
        manager.handleModeRequest(viewer, new byte[1024]);

        assertEquals(WaypointBarMode.BOTH, modes.get(viewer.getUniqueId()));
        verify(viewer, never()).sendPluginMessage(eq(plugin), eq(ClaimWaypointManager.CLAIM_MODE_CHANNEL), any());
    }

    @Test
    void shutdownHandsTheClaimKeyBackToCrowBar() {
        becomeModeFollowingCrowBar();
        manager.rebuildViewer(viewer);

        manager.shutdown();

        assertTrue(lastModeState().getAsJsonArray("modes").isEmpty());
        assertTrue(lastPayload().isEmpty());
    }

    private void becomeCrowBarClient() {
        when(viewer.getListeningPluginChannels()).thenReturn(Set.of(ClaimWaypointManager.CLAIM_DATA_CHANNEL));
    }

    /** A CrowBar new enough to register the mode channel, so its claim key follows /claim bar. */
    private void becomeModeFollowingCrowBar() {
        when(viewer.getListeningPluginChannels()).thenReturn(
            Set.of(ClaimWaypointManager.CLAIM_DATA_CHANNEL, ClaimWaypointManager.CLAIM_MODE_CHANNEL));
    }

    private static byte[] request(String mode, int seq) {
        return ("{\"mode\":\"" + mode + "\",\"seq\":" + seq + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** The most recent mode message sent to the viewer. */
    private JsonObject lastModeState() {
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(viewer, atLeastOnce())
            .sendPluginMessage(eq(plugin), eq(ClaimWaypointManager.CLAIM_MODE_CHANNEL), sent.capture());
        return JsonParser.parseString(new String(sent.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Map<String, List<String>> claimModes(JsonArray claims) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (JsonElement claim : claims) {
            JsonObject entry = claim.getAsJsonObject();
            result.put(entry.get("id").getAsString(), strings(entry.getAsJsonArray("modes")));
        }
        return result;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new java.util.ArrayList<>();
        for (JsonElement element : array) values.add(element.getAsString());
        return values;
    }

    /** The claims array of the most recent CrowBar payload sent to the viewer. */
    private JsonArray lastPayload() {
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(viewer, atLeastOnce())
            .sendPluginMessage(eq(plugin), eq(ClaimWaypointManager.CLAIM_DATA_CHANNEL), sent.capture());
        String json = new String(sent.getValue(), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("claims");
    }

    private static Set<String> field(JsonArray claims, String name) {
        Set<String> values = new HashSet<>();
        for (JsonElement claim : claims) {
            JsonObject entry = claim.getAsJsonObject();
            if (entry.has(name)) values.add(entry.get(name).getAsString());
        }
        return values;
    }

    private Claim claim(World world, long id, UUID owner) {
        Claim claim = mock(Claim.class);
        claim.inDataStore = true;
        when(claim.getID()).thenReturn(id);
        when(claim.getOwnerID()).thenReturn(owner);
        when(claim.getLesserBoundaryCorner()).thenReturn(new Location(world, 100, 60, 100));
        when(claim.getGreaterBoundaryCorner()).thenReturn(new Location(world, 110, 70, 110));
        return claim;
    }
}
