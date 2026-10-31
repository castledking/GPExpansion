package codes.castled.gpexpansion.command;

import codes.castled.gpexpansion.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaimListFormattingTest {

    @Test
    void claimListOptionsLoadBundledConfigDefaults() throws Exception {
        YamlConfiguration config = loadResourceYaml("/config.yml");
        ClaimCommand.ClaimListOptions options = ClaimCommand.ClaimListOptions.from(config);

        assertTrue(options.showTrustedClaims());
        assertTrue(options.showSubclaims());
        assertTrue(options.showId());
        assertFalse(options.showIdOnHover());
        assertTrue(options.showDivider());
        assertTrue(options.pageButtons());
        assertTrue(options.clickTeleport());
        assertEquals("griefprevention.claimslist.teleport", options.clickTeleportPermission());
        assertEquals(10, options.pageSize());
    }

    @Test
    void interactiveRowPreservesHoverAndClickAcrossPaperAndSpigotPaths() throws Exception {
        YamlConfiguration config = loadResourceYaml("/config.yml");
        YamlConfiguration lang = loadResourceYaml("/lang.yml");
        ClaimCommand.ClaimListOptions options = ClaimCommand.ClaimListOptions.from(config);

        Component row = ClaimCommand.buildClaimRowComponent(
                lang::getString,
                false,
                "42",
                "Spawn Castle",
                null,
                "world",
                120,
                -340,
                256,
                true,
                options
        );

        String plain = Mini.plain(row);
        assertEquals(" • #42 Spawn Castle · world · x120, z-340 · 256 blocks", plain);

        // Root + siblings: prefix (0), id (1), name (2), world (3), coords (4), area (5)
        List<Component> children = row.children();
        assertEquals(6, children.size());

        Component idChild = children.get(1);
        Component nameChild = children.get(2);
        Component worldChild = children.get(3);

        assertEquals(ClickEvent.runCommand("/claim list _teleport 42"), idChild.clickEvent());
        assertEquals(ClickEvent.runCommand("/claim list _teleport 42"), nameChild.clickEvent());
        assertNull(worldChild.clickEvent(), "Click event must not bleed onto world/coordinate suffixes");

        HoverEvent<?> hover = idChild.hoverEvent();
        assertNotNull(hover);
        assertEquals("Claim ID: 42\nClick to teleport", Mini.plain((Component) hover.value()));

        // Paper MiniMessage bridge round-trip (used by Mini.send on Paper/Purpur/Folia)
        String mmSerialized = MiniMessage.miniMessage().serialize(row);
        Component paperRoundTripped = MiniMessage.miniMessage().deserialize(mmSerialized);
        assertEquals(plain, Mini.plain(paperRoundTripped));
        assertTrue(hasAdventureClick(paperRoundTripped, "/claim list _teleport 42"));

        // Spigot BungeeCord conversion (used by Mini.send on Spigot)
        BaseComponent[] bungee = Mini.toBungee(row);
        assertTrue(hasBungeeClick(bungee, "/claim list _teleport 42"));
    }

    @Test
    void showIdOnHoverStripsInlineIdAndKeepsIdInNameHoverAndClickTeleport() throws Exception {
        YamlConfiguration config = loadResourceYaml("/config.yml");
        config.set("claim-list.show-id-on-hover", true);
        YamlConfiguration lang = loadResourceYaml("/lang.yml");
        ClaimCommand.ClaimListOptions options = ClaimCommand.ClaimListOptions.from(config);

        Component subRow = ClaimCommand.buildClaimRowComponent(
                lang::getString,
                true,
                "99",
                "Market Stall",
                "42",
                "world",
                10,
                20,
                16,
                true,
                options
        );

        String plain = Mini.plain(subRow);
        assertFalse(plain.contains("#99"), "Inline ID should be stripped when show-id-on-hover is true: " + plain);
        assertFalse(plain.contains("Child of #42"), "Parent ID suffix should be hidden when show-id-on-hover is true: " + plain);
        assertEquals("   └ Market Stall · world · x10, z20 · 16 blocks", plain);

        Component nameChild = subRow.children().get(1);
        assertEquals(ClickEvent.runCommand("/claim list _teleport 99"), nameChild.clickEvent());
        HoverEvent<?> hover = nameChild.hoverEvent();
        assertNotNull(hover);
        assertEquals("Claim ID: 99\nClick to teleport", Mini.plain((Component) hover.value()));
    }

    @Test
    void pageButtonsKeepIndependentClickEventsWithoutBleeding() throws Exception {
        YamlConfiguration config = loadResourceYaml("/config.yml");
        YamlConfiguration lang = loadResourceYaml("/lang.yml");
        ClaimCommand.ClaimListOptions options = ClaimCommand.ClaimListOptions.from(config);

        Component pageTwoOfThree = ClaimCommand.buildClaimListPageComponent(
                lang::getString,
                "Steve",
                true,
                true,
                2,
                3,
                options
        );

        assertEquals("« Previous  Page 2/3  Next »", Mini.plain(pageTwoOfThree));
        List<Component> parts = pageTwoOfThree.children();
        assertEquals(3, parts.size());
        assertEquals(ClickEvent.runCommand("/claim list Steve 1"), parts.get(0).clickEvent());
        assertNull(parts.get(1).clickEvent(), "Previous button click event must not bleed onto Page X/Y label");
        assertEquals(ClickEvent.runCommand("/claim list Steve 3"), parts.get(2).clickEvent());

        Component pageOneOfTwo = ClaimCommand.buildClaimListPageComponent(
                lang::getString,
                "Alex",
                false,
                true,
                1,
                2,
                options
        );
        assertNull(pageOneOfTwo.children().get(0).clickEvent(), "Previous button must be disabled on page 1");
        assertEquals(ClickEvent.runCommand("/claim list 2"), pageOneOfTwo.children().get(2).clickEvent());
    }

    private static boolean hasAdventureClick(Component comp, String expectedCommand) {
        if (ClickEvent.runCommand(expectedCommand).equals(comp.clickEvent())) {
            return true;
        }
        for (Component child : comp.children()) {
            if (hasAdventureClick(child, expectedCommand)) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static boolean hasBungeeClick(BaseComponent[] comps, String expectedCommand) {
        if (comps == null) return false;
        for (BaseComponent c : comps) {
            if (c == null) continue;
            net.md_5.bungee.api.chat.ClickEvent click = c.getClickEvent();
            if (click != null
                    && click.getAction() == net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND
                    && expectedCommand.equals(click.getValue())) {
                return true;
            }
            if (c.getExtra() != null && hasBungeeClick(c.getExtra().toArray(new BaseComponent[0]), expectedCommand)) {
                return true;
            }
        }
        return false;
    }

    private static YamlConfiguration loadResourceYaml(String path) throws Exception {
        try (InputStream in = ClaimListFormattingTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing classpath resource: " + path);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }
}
