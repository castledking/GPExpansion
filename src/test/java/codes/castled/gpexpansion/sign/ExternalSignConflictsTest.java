package codes.castled.gpexpansion.sign;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.setup.SetupSession;
import codes.castled.gpexpansion.setup.SetupWizardManager;
import codes.castled.gpexpansion.util.Config;
import codes.castled.gpexpansion.util.EcoKind;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalSignConflictsTest {

    @TempDir
    Path tempDir;

    private MockedStatic<Bukkit> bukkit;

    public static final class FakeEssentialsSign {
        private final String name;
        FakeEssentialsSign(String name) { this.name = name; }
        public String getName() { return name; }
    }

    public static final class FakeEssentialsSettings {
        private final List<FakeEssentialsSign> signs;
        FakeEssentialsSettings(List<FakeEssentialsSign> signs) { this.signs = signs; }
        public List<FakeEssentialsSign> enabledSigns() { return signs; }
    }

    public interface FakeEssentialsPlugin extends Plugin {
        FakeEssentialsSettings getSettings();
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
            bukkit = null;
        }
    }

    @Test
    void parsesEssentialsAndAlliumEnabledSignsConfigs() throws IOException {
        Path essConfig = tempDir.resolve("essentials-config.yml");
        Files.writeString(essConfig, """
                enabledSigns:
                  #- color
                  - buy
                  - sell
                  - warp
                """);
        assertEquals(
                Set.of("[buy]", "[sell]"),
                ExternalSignConflicts.readConflictedHeadersFromConfigFile(essConfig.toFile(), false)
        );

        Path essCommented = tempDir.resolve("essentials-commented.yml");
        Files.writeString(essCommented, """
                enabledSigns:
                  #- buy
                  #- sell
                  - warp
                """);
        assertEquals(
                Set.of(),
                ExternalSignConflicts.readConflictedHeadersFromConfigFile(essCommented.toFile(), false)
        );

        Path alliumConfig = tempDir.resolve("allium-config.yml");
        Files.writeString(alliumConfig, """
                enabledSigns:
                  - buy
                  - heal
                """);
        assertEquals(
                Set.of("[buy]"),
                ExternalSignConflicts.readConflictedHeadersFromConfigFile(alliumConfig.toFile(), true)
        );

        Path alliumSignsDisabled = tempDir.resolve("allium-disabled.yml");
        Files.writeString(alliumSignsDisabled, """
                modules:
                  signs: false
                enabledSigns:
                  - buy
                  - sell
                """);
        assertEquals(
                Set.of(),
                ExternalSignConflicts.readConflictedHeadersFromConfigFile(alliumSignsDisabled.toFile(), true)
        );
    }

    @Test
    void detectsLiveEssentialsAndAlliumAndSkipsYieldWhenConfiguredOff() throws IOException {
        PluginManager pm = mock(PluginManager.class);
        Server server = mock(Server.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServer).thenReturn(server);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pm);

        FakeEssentialsPlugin ess = mock(FakeEssentialsPlugin.class);
        when(ess.isEnabled()).thenReturn(true);
        when(ess.getSettings()).thenReturn(new FakeEssentialsSettings(List.of(
                new FakeEssentialsSign("Buy"),
                new FakeEssentialsSign("Warp")
        )));
        when(pm.getPlugin("Essentials")).thenReturn(ess);

        Plugin allium = mock(Plugin.class);
        when(allium.isEnabled()).thenReturn(true);
        YamlConfiguration alliumYaml = new YamlConfiguration();
        alliumYaml.set("enabledSigns", List.of("sell"));
        when(allium.getConfig()).thenReturn(alliumYaml);
        when(pm.getPlugin("Allium")).thenReturn(allium);

        GPExpansionPlugin gpx = mockGpxPlugin(true);
        assertEquals(Set.of("[buy]", "[sell]"), ExternalSignConflicts.conflictedHeaders(gpx));

        // Disabled plugins must not trigger conflicts
        when(ess.isEnabled()).thenReturn(false);
        when(allium.isEnabled()).thenReturn(false);
        assertEquals(Set.of(), ExternalSignConflicts.conflictedHeaders(gpx));

        // Respects yield-to-enabled-signs: false
        when(ess.isEnabled()).thenReturn(true);
        when(allium.isEnabled()).thenReturn(true);
        GPExpansionPlugin disabledYield = mockGpxPlugin(false);
        assertEquals(Set.of(), ExternalSignConflicts.conflictedHeaders(disabledYield));
    }

    @Test
    void setupWizardSelectsNonConflictingSellHeader() {
        PluginManager pm = mock(PluginManager.class);
        Server server = mock(Server.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServer).thenReturn(server);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pm);

        FakeEssentialsPlugin ess = mock(FakeEssentialsPlugin.class);
        when(ess.isEnabled()).thenReturn(true);
        when(pm.getPlugin("Essentials")).thenReturn(ess);

        GPExpansionPlugin gpx = mockGpxPlugin(true);
        SetupSession session = new SetupSession(java.util.UUID.randomUUID(), SetupSession.SetupType.SELL);
        session.setClaimId("42");
        session.setEcoKind(EcoKind.MONEY);
        session.setPrice("500");
        SetupWizardManager.PendingSignData pending = new SetupWizardManager.PendingSignData(session);

        // No conflict -> [sell]
        when(ess.getSettings()).thenReturn(new FakeEssentialsSettings(List.of()));
        assertEquals("[sell]", pending.getSignLines(gpx, false)[0]);
        assertEquals("[sell]", pending.getSignLines(gpx, true)[0]);

        // Only sell enabled -> regular sign picks [sell claim], hanging picks [buy]
        when(ess.getSettings()).thenReturn(new FakeEssentialsSettings(List.of(new FakeEssentialsSign("Sell"))));
        assertEquals("[sell claim]", pending.getSignLines(gpx, false)[0]);
        assertEquals("[buy]", pending.getSignLines(gpx, true)[0]);

        // Both buy & sell enabled -> both regular and hanging signs fall back to [sell claim]
        when(ess.getSettings()).thenReturn(new FakeEssentialsSettings(List.of(
                new FakeEssentialsSign("Buy"),
                new FakeEssentialsSign("Sell")
        )));
        assertEquals("[sell claim]", pending.getSignLines(gpx, false)[0]);
        assertEquals("[sell claim]", pending.getSignLines(gpx, true)[0]);
    }

    private GPExpansionPlugin mockGpxPlugin(boolean yieldToEnabledSigns) {
        GPExpansionPlugin plugin = mock(GPExpansionPlugin.class);
        YamlConfiguration gpxCfg = new YamlConfiguration();
        gpxCfg.set("signs.sell.sign-formats.inputs", List.of("[sell]", "[sell claim]", "[buy]", "[buy claim]"));
        gpxCfg.set("signs.sell.hanging-sign-formats.inputs", List.of("[sell]", "[buy]"));
        when(plugin.getConfig()).thenReturn(gpxCfg);

        Config configManager = mock(Config.class);
        when(configManager.shouldYieldToExternalEnabledSigns()).thenReturn(yieldToEnabledSigns);
        when(plugin.getConfigManager()).thenReturn(configManager);
        return plugin;
    }
}
