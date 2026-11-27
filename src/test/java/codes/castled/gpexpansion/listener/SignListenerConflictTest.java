package codes.castled.gpexpansion.listener;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.permission.SignLimitManager;
import codes.castled.gpexpansion.util.Config;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignListenerConflictTest {

    private MockedStatic<Bukkit> bukkit;

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
            bukkit = null;
        }
    }

    @Test
    void signListenerDropsBuyAndSellHeadersWhenAlliumOrEssentialsEnablesThem() {
        PluginManager pm = mock(PluginManager.class);
        Server server = mock(Server.class);
        when(server.getVersion()).thenReturn("TestServer");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServer).thenReturn(server);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pm);

        Plugin allium = mock(Plugin.class);
        when(allium.isEnabled()).thenReturn(true);
        YamlConfiguration alliumCfg = new YamlConfiguration();
        alliumCfg.set("enabledSigns", List.of("buy", "sell"));
        when(allium.getConfig()).thenReturn(alliumCfg);
        when(pm.getPlugin("Allium")).thenReturn(allium);

        YamlConfiguration gpxCfg = new YamlConfiguration();
        gpxCfg.set("signs.sell.sign-formats.inputs", List.of("[sell]", "[sell claim]", "[buy]", "[buy claim]"));
        gpxCfg.set("signs.sell.hanging-sign-formats.inputs", List.of("[sell]", "[buy]"));
        gpxCfg.set("signs.rent.sign-formats.inputs", List.of("[rent]", "[rent claim]"));

        Config configManager = mock(Config.class);
        when(configManager.shouldYieldToExternalEnabledSigns()).thenReturn(true);
        when(configManager.areRentSignsEnabled()).thenReturn(true);
        when(configManager.areSellSignsEnabled()).thenReturn(true);
        when(configManager.areMailboxSignsEnabled()).thenReturn(true);
        when(configManager.areGlobalSignsEnabled()).thenReturn(true);

        GPExpansionPlugin gpx = mock(GPExpansionPlugin.class);
        when(gpx.getConfig()).thenReturn(gpxCfg);
        when(gpx.getConfigManager()).thenReturn(configManager);
        when(gpx.getSignLimitManager()).thenReturn(mock(SignLimitManager.class));
        when(gpx.getServer()).thenReturn(server);
        when(gpx.getLogger()).thenReturn(Logger.getLogger("SignListenerConflictTest"));
        when(gpx.getDescription()).thenReturn(new PluginDescriptionFile("GPExpansion", "1.2.8", "codes.castled.gpexpansion.GPExpansionPlugin"));

        SignListener listener = new SignListener(gpx);

        // Short [buy] / [sell] are dropped (both for SignListener and BlueMap marker shield)
        assertFalse(listener.matchesSellInputHeader("[buy]"));
        assertFalse(listener.matchesSellInputHeader("&1[Buy]"));
        assertFalse(listener.matchesSellInputHeader("[sell]"));
        assertFalse(listener.matchesSellInputHeader("§a[SELL]"));
        assertFalse(listener.isEnabledManagedInputHeader("[buy]"));
        assertFalse(listener.isEnabledManagedInputHeader("[sell]"));

        // Claim-specific headers and other GPX sign types remain active
        assertTrue(listener.matchesSellInputHeader("[buy claim]"));
        assertTrue(listener.matchesSellInputHeader("[sell claim]"));
        assertTrue(listener.isEnabledManagedInputHeader("[sell claim]"));
        assertTrue(listener.isEnabledManagedInputHeader("[buy claim]"));
        assertTrue(listener.isEnabledManagedInputHeader("[rent]"));

        // When yield is turned off, [buy] and [sell] work again
        when(configManager.shouldYieldToExternalEnabledSigns()).thenReturn(false);
        assertTrue(listener.matchesSellInputHeader("[buy]"));
        assertTrue(listener.matchesSellInputHeader("[sell]"));
        assertTrue(listener.isEnabledManagedInputHeader("[buy]"));
    }
}
