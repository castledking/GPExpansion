package codes.castled.gpexpansion.platform;

import codes.castled.gpexpansion.util.Mini;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpigotCompatibilityTest {

    @Test
    void pluginYmlAndPaperPluginYmlExistAndStayInSync() throws Exception {
        YamlConfiguration paperYml = loadResourceYaml("/paper-plugin.yml");
        YamlConfiguration spigotYml = loadResourceYaml("/plugin.yml");

        assertEquals(paperYml.getString("name"), spigotYml.getString("name"));
        assertEquals(paperYml.getString("main"), spigotYml.getString("main"));
        assertEquals(paperYml.getString("api-version"), spigotYml.getString("api-version"));
        assertTrue(spigotYml.getBoolean("folia-supported"), "plugin.yml must declare folia-supported: true");

        // Load-order parity: GPExpansion loads before GriefPrevention on both platforms
        assertEquals("AFTER", paperYml.getString("dependencies.server.GriefPrevention.load"));
        assertTrue(spigotYml.getStringList("loadbefore").contains("GriefPrevention"),
                "plugin.yml must declare loadbefore: [GriefPrevention]");

        // Soft-depend parity for BEFORE dependencies
        ConfigurationSection serverDeps = paperYml.getConfigurationSection("dependencies.server");
        assertNotNull(serverDeps);
        List<String> expectedSoftDepends = new ArrayList<>();
        for (String dep : serverDeps.getKeys(false)) {
            if ("BEFORE".equalsIgnoreCase(serverDeps.getString(dep + ".load"))) {
                expectedSoftDepends.add(dep);
            }
        }
        assertEquals(expectedSoftDepends, spigotYml.getStringList("softdepend"),
                "plugin.yml softdepend must match paper-plugin.yml BEFORE dependencies");

        // Permission node & default parity
        ConfigurationSection paperPerms = paperYml.getConfigurationSection("permissions");
        ConfigurationSection spigotPerms = spigotYml.getConfigurationSection("permissions");
        assertNotNull(paperPerms, "paper-plugin.yml permissions missing");
        assertNotNull(spigotPerms, "plugin.yml permissions missing");

        Set<String> paperKeys = paperPerms.getKeys(false);
        Set<String> spigotKeys = spigotPerms.getKeys(false);
        assertEquals(paperKeys, spigotKeys, "Permission nodes in plugin.yml and paper-plugin.yml must match 1:1");

        for (String perm : paperKeys) {
            assertEquals(
                    String.valueOf(paperPerms.get(perm + ".default")),
                    String.valueOf(spigotPerms.get(perm + ".default")),
                    "Default mismatch for permission " + perm
            );
        }
    }

    @Test
    void miniPreservesHexColorsAndBungeeClickEventsForSpigot() {
        Component interactive = Mini.deserialize(
                "<#ff5500><bold>Confirm Purchase</bold></#ff5500> "
                        + "<green><click:run_command:'/gpxconfirm accept'><hover:show_text:'Click to confirm'>[CONFIRM]</hover></click></green>"
        );

        assertEquals("Confirm Purchase [CONFIRM]", Mini.plain(interactive));

        // Spigot 24-bit hex section format (§x§f§f§5§5§0§0)
        String legacy = Mini.displayName(interactive);
        assertTrue(legacy.contains("\u00a7x\u00a7f\u00a7f\u00a75\u00a75\u00a70\u00a70"),
                "Legacy section output should preserve 24-bit Spigot hex formatting, got: " + legacy);

        // Spigot BungeeCord chat serialization preserves click & hover events
        BaseComponent[] bungee = Mini.toBungee(interactive);
        assertNotNull(bungee);
        assertTrue(hasRunCommand(bungee, "/gpxconfirm accept"),
                "BungeeComponentSerializer output must preserve clickable /gpxconfirm action on Spigot");

        // Legacy ampersand + hex parsing for GUI YAML strings
        Component guiTitle = Mini.fromLegacyAmpersand("&#33ccff&lClaim Market");
        assertEquals("Claim Market", Mini.plain(guiTitle));
        assertTrue(Mini.inventoryTitle(guiTitle).contains("\u00a7x\u00a73\u00a73\u00a7c\u00a7c\u00a7f\u00a7f"));
    }

    private static boolean hasRunCommand(BaseComponent[] components, String expectedValue) {
        if (components == null) return false;
        for (BaseComponent comp : components) {
            if (comp == null) continue;
            ClickEvent click = comp.getClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND
                    && expectedValue.equals(click.getValue())) {
                return true;
            }
            if (comp.getExtra() != null && hasRunCommand(comp.getExtra().toArray(new BaseComponent[0]), expectedValue)) {
                return true;
            }
        }
        return false;
    }

    private static YamlConfiguration loadResourceYaml(String path) throws Exception {
        try (InputStream in = SpigotCompatibilityTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing classpath resource: " + path);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }
}
