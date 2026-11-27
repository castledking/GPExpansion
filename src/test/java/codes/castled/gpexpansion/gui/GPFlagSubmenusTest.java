package codes.castled.gpexpansion.gui;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPFlagsBridge;
import codes.castled.gpexpansion.storage.ClaimDataStore;
import codes.castled.gpexpansion.util.Messages;
import codes.castled.gpexpansion.util.Mini;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GPFlagSubmenusTest {

    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<Mini> mini;
    private MockedStatic<GPFlagsBridge> gpFlags;

    private GPExpansionPlugin plugin;
    private GUIManager manager;
    private Messages messages;
    private ChatPromptManager chatPrompts;
    private Player player;
    private Object claim;

    @BeforeEach
    void setUp() {
        Server server = mock(Server.class);

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServer).thenReturn(server);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("GPFlagSubmenusTest"));

        mini = mockStatic(Mini.class, CALLS_REAL_METHODS);
        mini.when(() -> Mini.createInventory(any(), anyInt(), any(Component.class))).thenAnswer(inv -> {
            int size = inv.getArgument(1);
            Inventory invMock = mock(Inventory.class);
            when(invMock.getSize()).thenReturn(size);
            return invMock;
        });

        gpFlags = mockStatic(GPFlagsBridge.class);
        gpFlags.when(GPFlagsBridge::isAvailable).thenReturn(true);
        gpFlags.when(() -> GPFlagsBridge.getEffectiveFlagState(any(), anyString())).thenReturn(Optional.empty());

        plugin = mock(GPExpansionPlugin.class);
        messages = mock(Messages.class);
        chatPrompts = mock(ChatPromptManager.class);
        ClaimDataStore dataStore = mock(ClaimDataStore.class);
        when(dataStore.getCustomName(anyString())).thenReturn(Optional.of("Town Square"));
        when(plugin.getClaimDataStore()).thenReturn(dataStore);
        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getChatPromptManager()).thenReturn(chatPrompts);

        manager = mock(GUIManager.class);
        when(manager.getPlugin()).thenReturn(plugin);
        when(manager.getGUIConfig(anyString())).thenReturn(null);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("griefprevention.admin")).thenReturn(true);
        when(player.hasPermission(ClaimFlagsGUI.GPFLAGS_SET_FLAG)).thenReturn(true);
        when(player.hasPermission(startsWith("gpflags.flag."))).thenReturn(true);

        claim = new Object();
    }

    @AfterEach
    void tearDown() {
        if (gpFlags != null) gpFlags.close();
        if (mini != null) mini.close();
        if (bukkit != null) bukkit.close();
    }

    @Test
    void registryCoversAllParameterizedFlagsAndLeavesBooleanFlagsAsToggles() {
        List<String> parameterized = List.of(
                "PlayerGamemode", "PlayerTime", "PlayerWeather",
                "NoOpenDoors", "NoPotionEffects", "SpawnReasonWhitelist", "NoMobSpawnsType",
                "HealthRegen", "BuyAccessTrust", "BuyBuildTrust", "BuyContainerTrust", "BuySubclaim", "SpleefArena",
                "RespawnLocation", "TrappedDestination", "NoEnterPlayer",
                "EnterMessage", "ExitMessage", "EnterActionbar", "ExitActionbar",
                "EnterCommand", "EnterCommand-Members", "EnterCommand-Owner", "EnterPlayerCommand",
                "ExitCommand", "ExitCommand-Members", "ExitCommand-Owner", "ExitPlayerCommand",
                "NetherPortalConsoleCommand", "NetherPortalPlayerCommand",
                "CommandBlackList", "CommandWhiteList"
        );
        for (String flag : parameterized) {
            assertTrue(GPFlagSubmenuRegistry.hasSubmenu(flag), "Expected submenu for " + flag);
            assertNotNull(GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", flag, 0),
                    "Expected non-null submenu for " + flag);
        }

        for (String plainToggle : List.of("AllowPvP", "KeepInventory", "NoFlight", "NoMonsters", "NotifyEnter", "NotifyExit")) {
            assertFalse(GPFlagSubmenuRegistry.hasSubmenu(plainToggle), "Plain toggle should not open submenu: " + plainToggle);
        }
    }

    @Test
    void playerTimeAndWeatherChoiceMenusSetExpectedParameters() {
        gpFlags.when(() -> GPFlagsBridge.setFlagState(eq("7"), anyString(), eq(true), eq(player), any(String[].class)))
                .thenReturn(true);

        GPFlagChoiceGUI timeGui = spyChoice(
                (GPFlagChoiceGUI) GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", "PlayerTime", 0));
        timeGui.createInventory();
        clickSlot(timeGui, 12); // Noon
        gpFlags.verify(() -> GPFlagsBridge.setFlagState(eq("7"), eq("PlayerTime"), eq(true), eq(player), eq(new String[]{"noon"})));

        GPFlagChoiceGUI weatherGui = spyChoice(
                (GPFlagChoiceGUI) GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", "PlayerWeather", 0));
        weatherGui.createInventory();
        clickSlot(weatherGui, 15); // Rain
        gpFlags.verify(() -> GPFlagsBridge.setFlagState(eq("7"), eq("PlayerWeather"), eq(true), eq(player), eq(new String[]{"rain"})));
    }

    @Test
    void noOpenDoorsMultiSelectNarrowsFromAllAndDisablesWhenEmpty() {
        // Initially enabled with empty params (= all 3 openables blocked)
        gpFlags.when(() -> GPFlagsBridge.getEffectiveFlagState(claim, "NoOpenDoors"))
                .thenReturn(Optional.of(new GPFlagsBridge.ClaimFlagState("NoOpenDoors", true, "", true)));
        gpFlags.when(() -> GPFlagsBridge.setFlagState(eq("7"), eq("NoOpenDoors"), anyBoolean(), eq(player), any(String[].class)))
                .thenReturn(true);

        GPFlagMultiSelectGUI doorsGui = spyMulti(
                (GPFlagMultiSelectGUI) GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", "NoOpenDoors", 0));
        doorsGui.createInventory();

        // Compact 4 slots: 10=All, 12=doors, 14=trapdoors, 16=gates -> clicking doors (12) narrows to trapdoors,gates
        clickSlot(doorsGui, 12);
        gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                eq("7"), eq("NoOpenDoors"), eq(true), eq(player), eq(new String[]{"trapdoors,gates"})));

        // Clicking All (10) passes empty args (default all)
        clickSlot(doorsGui, 10);
        gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                eq("7"), eq("NoOpenDoors"), eq(true), eq(player), eq(new String[0])));
    }

    @Test
    void locationMenusCapturePreciseAndBlockCoordinatesFromPlayerLocation() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("survival");
        Location loc = new Location(world, 10.5, 64.0, -20.25, 90.0f, 15.5f);
        when(player.getLocation()).thenReturn(loc);
        gpFlags.when(() -> GPFlagsBridge.setFlagState(eq("7"), anyString(), eq(true), eq(player), any(String[].class)))
                .thenReturn(true);

        GPFlagParameterGUI respawn = spyParam(
                (GPFlagParameterGUI) GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", "RespawnLocation", 0));
        respawn.createInventory();
        clickSlot(respawn, GPFlagParameterGUI.LOCATION_CURRENT_SLOT);
        gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                eq("7"), eq("RespawnLocation"), eq(true), eq(player),
                eq(new String[]{"survival", "10.50", "64.00", "-20.25", "90.0", "15.5"})));

        GPFlagParameterGUI trapped = spyParam(
                (GPFlagParameterGUI) GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", "TrappedDestination", 0));
        trapped.createInventory();
        clickSlot(trapped, GPFlagParameterGUI.LOCATION_CURRENT_SLOT);
        gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                eq("7"), eq("TrappedDestination"), eq(true), eq(player),
                eq(new String[]{"survival", "10", "64", "-21"})));
    }

    @Test
    void noEnterPlayerAddAndRemoveThroughChatPromptSplitsPlayerArgs() {
        gpFlags.when(() -> GPFlagsBridge.getEffectiveFlagState(claim, "NoEnterPlayer"))
                .thenReturn(Optional.of(new GPFlagsBridge.ClaimFlagState("NoEnterPlayer", true, "Alice", true)));
        gpFlags.when(() -> GPFlagsBridge.setFlagState(eq("7"), eq("NoEnterPlayer"), anyBoolean(), eq(player), any(String[].class)))
                .thenReturn(true);

        GPFlagParameterGUI playersGui = spyParam(
                (GPFlagParameterGUI) GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", "NoEnterPlayer", 0));
        playersGui.createInventory();

        // Add Bob via chat prompt
        clickSlot(playersGui, GPFlagParameterGUI.PLAYERS_ADD_SLOT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<String>> addCallback = ArgumentCaptor.forClass(Consumer.class);
        verify(chatPrompts).begin(eq(player), eq("flags.parameter-prompt"), any(), addCallback.capture(), any());
        addCallback.getValue().accept("Bob");

        gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                eq("7"), eq("NoEnterPlayer"), eq(true), eq(player), eq(new String[]{"Alice", "Bob"})));

        // Remove Alice (the only existing player in mocked state) -> disables flag
        clickSlot(playersGui, GPFlagParameterGUI.PLAYERS_REMOVE_SLOT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<String>> removeCallback = ArgumentCaptor.forClass(Consumer.class);
        verify(chatPrompts, times(2)).begin(eq(player), eq("flags.parameter-prompt"), any(), removeCallback.capture(), any());
        removeCallback.getValue().accept("alice");

        gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                eq("7"), eq("NoEnterPlayer"), eq(false), eq(player), eq(new String[0])));
    }

    @Test
    void presetAndTextMenusApplyValuesAndRespectChatInput() {
        gpFlags.when(() -> GPFlagsBridge.setFlagState(eq("7"), anyString(), eq(true), eq(player), any(String[].class)))
                .thenReturn(true);

        // HealthRegen preset slot 11 -> "2"
        GPFlagParameterGUI regen = spyParam(
                (GPFlagParameterGUI) GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", "HealthRegen", 0));
        regen.createInventory();
        clickSlot(regen, 11);
        gpFlags.verify(() -> GPFlagsBridge.setFlagState(eq("7"), eq("HealthRegen"), eq(true), eq(player), eq(new String[]{"2"})));

        // SpleefArena preset slot 11 -> {"SNOW_BLOCK", "DIRT", "200"}
        GPFlagParameterGUI spleef = spyParam(
                (GPFlagParameterGUI) GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", "SpleefArena", 0));
        spleef.createInventory();
        clickSlot(spleef, 11);
        gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                eq("7"), eq("SpleefArena"), eq(true), eq(player), eq(new String[]{"SNOW_BLOCK", "DIRT", "200"})));

        // EnterMessage via chat prompt -> single message arg preserved intact
        GPFlagParameterGUI msg = spyParam(
                (GPFlagParameterGUI) GPFlagSubmenuRegistry.createSubmenu(manager, player, claim, "7", "EnterMessage", 0));
        msg.createInventory();
        clickSlot(msg, GPFlagParameterGUI.TEXT_SET_SLOT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<String>> msgCallback = ArgumentCaptor.forClass(Consumer.class);
        verify(chatPrompts).begin(eq(player), eq("flags.parameter-prompt"), any(), msgCallback.capture(), any());
        msgCallback.getValue().accept("&aWelcome to %owner%'s claim!");

        gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                eq("7"), eq("EnterMessage"), eq(true), eq(player), eq(new String[]{"&aWelcome to %owner%'s claim!"})));
    }

    private static void clickSlot(BaseGUI gui, int slot) {
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getRawSlot()).thenReturn(slot);
        gui.handleClick(click);
    }

    private static GPFlagChoiceGUI spyChoice(GPFlagChoiceGUI gui) {
        GPFlagChoiceGUI spied = spy(gui);
        doNothing().when(spied).playClickSound();
        doReturn(mock(ItemStack.class)).when(spied).createFiller();
        doReturn(mock(ItemStack.class)).when(spied).createItem(any(Material.class), anyString(), anyList(), anyBoolean());
        return spied;
    }

    private static GPFlagMultiSelectGUI spyMulti(GPFlagMultiSelectGUI gui) {
        GPFlagMultiSelectGUI spied = spy(gui);
        doNothing().when(spied).playClickSound();
        doReturn(mock(ItemStack.class)).when(spied).createFiller();
        doReturn(mock(ItemStack.class)).when(spied).createItem(any(Material.class), anyString(), anyList(), anyBoolean());
        return spied;
    }

    private static GPFlagParameterGUI spyParam(GPFlagParameterGUI gui) {
        GPFlagParameterGUI spied = spy(gui);
        doNothing().when(spied).playClickSound();
        doReturn(mock(ItemStack.class)).when(spied).createFiller();
        doReturn(mock(ItemStack.class)).when(spied).createItem(any(Material.class), anyString(), anyList(), anyBoolean());
        return spied;
    }
}
