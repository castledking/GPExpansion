package codes.castled.gpexpansion.gui;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPFlagsBridge;
import codes.castled.gpexpansion.storage.ClaimDataStore;
import codes.castled.gpexpansion.util.Messages;
import codes.castled.gpexpansion.util.Mini;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerGamemodeFlagGUITest {

    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<Mini> mini;
    private MockedStatic<GPFlagsBridge> gpFlags;

    private GPExpansionPlugin plugin;
    private GUIManager manager;
    private Messages messages;
    private Player player;
    private Object claim;

    @BeforeEach
    void setUp() {
        Server server = mock(Server.class);

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServer).thenReturn(server);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("PlayerGamemodeFlagGUITest"));

        mini = mockStatic(Mini.class, CALLS_REAL_METHODS);
        mini.when(() -> Mini.createInventory(any(), anyInt(), any(Component.class))).thenAnswer(inv -> {
            int size = inv.getArgument(1);
            Inventory invMock = mock(Inventory.class);
            when(invMock.getSize()).thenReturn(size);
            return invMock;
        });

        gpFlags = mockStatic(GPFlagsBridge.class);
        gpFlags.when(GPFlagsBridge::isAvailable).thenReturn(true);

        plugin = mock(GPExpansionPlugin.class);
        messages = mock(Messages.class);
        ClaimDataStore dataStore = mock(ClaimDataStore.class);
        when(dataStore.getCustomName(anyString())).thenReturn(Optional.of("Spawn Arena"));
        when(plugin.getClaimDataStore()).thenReturn(dataStore);
        when(plugin.getMessages()).thenReturn(messages);

        manager = mock(GUIManager.class);
        when(manager.getPlugin()).thenReturn(plugin);
        when(manager.getGUIConfig(anyString())).thenReturn(null);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("griefprevention.admin")).thenReturn(true);
        when(player.hasPermission(ClaimFlagsGUI.GPFLAGS_SET_FLAG)).thenReturn(true);
        when(player.hasPermission("gpflags.flag.playergamemode")).thenReturn(true);

        claim = new Object();
    }

    @AfterEach
    void tearDown() {
        if (gpFlags != null) gpFlags.close();
        if (mini != null) mini.close();
        if (bukkit != null) bukkit.close();
    }

    @Test
    void clickingPlayerGamemodeInClaimFlagsOpensSubmenuInsteadOfBareToggle() {
        gpFlags.when(GPFlagsBridge::getClaimFlagDefinitions).thenReturn(List.of(
                new GPFlagsBridge.FlagInfo("PlayerGamemode", new Object())
        ));
        gpFlags.when(() -> GPFlagsBridge.getAllEffectiveFlagStates(claim)).thenReturn(Map.of(
                "playergamemode", new GPFlagsBridge.ClaimFlagState("PlayerGamemode", false, "", false)
        ));
        gpFlags.when(() -> GPFlagsBridge.getEffectiveFlagState(claim, "PlayerGamemode"))
                .thenReturn(Optional.of(new GPFlagsBridge.ClaimFlagState("PlayerGamemode", false, "", false)));

        ClaimFlagsGUI gui = new TestableClaimFlagsGUI(manager, player, claim, "42");
        gui.createInventory();

        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getRawSlot()).thenReturn(9); // first flag slot
        gui.handleClick(click);

        gpFlags.verify(() -> GPFlagsBridge.toggleEffectiveFlag(any(), any(), any()), never());
        ArgumentCaptor<BaseGUI> opened = ArgumentCaptor.forClass(BaseGUI.class);
        verify(manager).openGUI(eq(player), opened.capture());
        assertInstanceOf(PlayerGamemodeFlagGUI.class, opened.getValue());
    }

    @Test
    void submenuSetsEachGamemodeDisablesAndReturnsToClaimFlags() {
        gpFlags.when(() -> GPFlagsBridge.getEffectiveFlagState(claim, "PlayerGamemode"))
                .thenReturn(Optional.of(new GPFlagsBridge.ClaimFlagState("PlayerGamemode", false, "", false)));
        gpFlags.when(() -> GPFlagsBridge.setFlagState(eq("42"), eq("PlayerGamemode"), eq(true), eq(player), any(String[].class)))
                .thenReturn(true);
        gpFlags.when(() -> GPFlagsBridge.setFlagState(eq("42"), eq("PlayerGamemode"), eq(false), eq(player), any(String[].class)))
                .thenReturn(true);

        PlayerGamemodeFlagGUI submenu = new TestableGamemodeGUI(manager, player, claim, "42", 1);
        submenu.createInventory();

        for (PlayerGamemodeFlagGUI.ModeOption option : PlayerGamemodeFlagGUI.ModeOption.values()) {
            InventoryClickEvent click = mock(InventoryClickEvent.class);
            when(click.getRawSlot()).thenReturn(option.slot);
            submenu.handleClick(click);

            gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                    eq("42"), eq("PlayerGamemode"), eq(true), eq(player), eq(new String[]{option.parameter})));
            verify(messages).send(player, "flags.toggled",
                    "{flag}", "PlayerGamemode",
                    "{state}", "enabled (" + option.displayName + ")");
        }

        // Now mark flag enabled so Disable triggers setFlagState(..., false, player)
        gpFlags.when(() -> GPFlagsBridge.getEffectiveFlagState(claim, "PlayerGamemode"))
                .thenReturn(Optional.of(new GPFlagsBridge.ClaimFlagState("PlayerGamemode", true, "creative", true)));
        PlayerGamemodeFlagGUI activeSubmenu = new TestableGamemodeGUI(manager, player, claim, "42", 1);
        activeSubmenu.createInventory();

        InventoryClickEvent disableClick = mock(InventoryClickEvent.class);
        when(disableClick.getRawSlot()).thenReturn(PlayerGamemodeFlagGUI.DISABLE_SLOT);
        activeSubmenu.handleClick(disableClick);

        gpFlags.verify(() -> GPFlagsBridge.setFlagState(
                eq("42"), eq("PlayerGamemode"), eq(false), eq(player), eq(new String[0])));
        verify(messages).send(player, "flags.toggled",
                "{flag}", "PlayerGamemode",
                "{state}", "disabled");

        // Back button returns to ClaimFlagsGUI
        gpFlags.when(GPFlagsBridge::getClaimFlagDefinitions).thenReturn(List.of());
        gpFlags.when(() -> GPFlagsBridge.getAllEffectiveFlagStates(claim)).thenReturn(Map.of());
        InventoryClickEvent backClick = mock(InventoryClickEvent.class);
        when(backClick.getRawSlot()).thenReturn(PlayerGamemodeFlagGUI.BACK_SLOT);
        activeSubmenu.handleClick(backClick);

        ArgumentCaptor<BaseGUI> returned = ArgumentCaptor.forClass(BaseGUI.class);
        verify(manager).openGUI(eq(player), returned.capture());
        assertInstanceOf(ClaimFlagsGUI.class, returned.getValue());
    }

    private static final class TestableClaimFlagsGUI extends ClaimFlagsGUI {
        TestableClaimFlagsGUI(GUIManager manager, Player player, Object claim, String claimId) {
            super(manager, player, claim, claimId);
        }

        @Override
        protected ItemStack createFiller() {
            return mock(ItemStack.class);
        }

        @Override
        protected ItemStack createItem(Material material, String name, List<String> lore, boolean enchantedGlow) {
            return mock(ItemStack.class);
        }
    }

    private static final class TestableGamemodeGUI extends PlayerGamemodeFlagGUI {
        TestableGamemodeGUI(GUIManager manager, Player player, Object claim, String claimId, int returnPage) {
            super(manager, player, claim, claimId, returnPage);
        }

        @Override
        protected ItemStack createFiller() {
            return mock(ItemStack.class);
        }

        @Override
        protected ItemStack createItem(Material material, String name, List<String> lore, boolean enchantedGlow) {
            return mock(ItemStack.class);
        }
    }
}
