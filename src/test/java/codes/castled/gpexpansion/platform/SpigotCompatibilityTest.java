package codes.castled.gpexpansion.platform;

import codes.castled.gpexpansion.gui.GUIManager;
import codes.castled.gpexpansion.util.Mini;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpigotCompatibilityTest {

    @Test
    void pluginYmlIsTheOnlyDescriptorAndKeepsLoadOrder() throws Exception {
        assertNull(SpigotCompatibilityTest.class.getResource("/paper-plugin.yml"),
                "paper-plugin.yml must not ship: Paper prefers it over plugin.yml and would load GPX "
                        + "in an isolated classloader that Bukkit add-ons cannot see");

        // Parse exactly like the server does; throws InvalidDescriptionException if Bukkit would reject it
        PluginDescriptionFile desc;
        try (InputStream in = SpigotCompatibilityTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "Missing classpath resource: /plugin.yml");
            desc = new PluginDescriptionFile(in);
        }
        assertEquals("GPExpansion", desc.getName());
        assertFalse(desc.getVersion().contains("${"), "plugin.yml version must be Maven-filtered");
        assertNotNull(SpigotCompatibilityTest.class.getResource("/" + desc.getMain().replace('.', '/') + ".class"),
                "plugin.yml main class must exist: " + desc.getMain());
        assertNotNull(desc.getAPIVersion(), "plugin.yml must declare api-version");

        // GPX enables before GriefPrevention so its runtime /claim registers first and wins the label
        assertTrue(desc.getLoadBefore().contains("GriefPrevention"),
                "plugin.yml must declare loadbefore: [GriefPrevention]");
        assertTrue(desc.getDepend().isEmpty(), "GPX must not hard-depend on any plugin");
        for (String excluded : List.of("GriefPrevention", "Nexo", "DiscordSRV")) {
            assertFalse(desc.getSoftDepend().contains(excluded),
                    excluded + " must stay out of softdepend (load-order cycle / /claim label takeover)");
        }

        // Commands are registered at runtime; Bukkit registers plugin.yml commands before onEnable,
        // so a commands: section would take the labels away from the real handlers.
        assertTrue(desc.getCommands().isEmpty(), "plugin.yml must not declare commands (registered at runtime)");

        // Raw SnakeYAML like Bukkit: YamlConfiguration would split dotted permission names into paths.
        Map<?, ?> raw = loadRawYaml("/plugin.yml");
        assertEquals(Boolean.TRUE, raw.get("folia-supported"), "plugin.yml must declare folia-supported: true");
        assertInstanceOf(Map.class, raw.get("permissions"), "plugin.yml permissions missing");
        Map<?, ?> perms = (Map<?, ?>) raw.get("permissions");
        assertTrue(perms.containsKey("gpx.player") && perms.containsKey("gpx.admin"),
                "plugin.yml must keep the gpx.player and gpx.admin base permissions");
        for (Map.Entry<?, ?> perm : perms.entrySet()) {
            assertTrue(perm.getValue() == null || perm.getValue() instanceof Map,
                    "Permission " + perm.getKey() + " must be a mapping");
            Object def = perm.getValue() instanceof Map<?, ?> node ? node.get("default") : null;
            if (def != null) {
                assertNotNull(PermissionDefault.getByName(def.toString()),
                        "Invalid default for permission " + perm.getKey() + ": " + def);
            }
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

    @Test
    void claimOptionsGuiMigratesLegacyMiddleRowAndGlobalSettingsSlot() throws Exception {
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("items.summary.slot", 4);
        legacy.set("items.teleport.slot", 20);
        legacy.set("items.resize.slot", 22);
        legacy.set("items.trust.slot", 24);
        legacy.set("items.abandon.slot", 36);
        legacy.set("items.view-children.slot", 38);
        legacy.set("items.claim-flags.slot", 40);
        legacy.set("items.claim-flags.lore", List.of("&7Toggle GPFlags for this claim."));
        legacy.set("items.global-settings.slot", 42);
        // Simulate an already-merged transfer item colliding on slot 42
        legacy.set("items.transfer.slot", 42);
        legacy.set("items.back.slot", 44);

        assertTrue(GUIManager.migrateGuiConfig("claim-options.yml", legacy));
        assertEquals(19, legacy.getInt("items.teleport.slot"));
        assertEquals(21, legacy.getInt("items.resize.slot"));
        assertEquals(23, legacy.getInt("items.trust.slot"));
        assertEquals(25, legacy.getInt("items.global-settings.slot"));
        assertEquals(42, legacy.getInt("items.transfer.slot"));
        assertFalse(GUIManager.migrateGuiConfig("claim-options.yml", legacy));
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

    private static Map<?, ?> loadRawYaml(String path) throws Exception {
        try (InputStream in = SpigotCompatibilityTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing classpath resource: " + path);
            Object root = new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertInstanceOf(Map.class, root, path + " must be a YAML mapping");
            return (Map<?, ?>) root;
        }
    }
}
