package codes.castled.gpexpansion.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagesRetiredDefaultsTest {

    private static final String NEW_OFF = "&aClaim markers are now &eoff&a. Player dots are controlled separately.";

    @Test
    void unchangedOldClaimBarTextIsReworded() throws Exception {
        // Same layout as an existing install's lang.yml, where YAML folds the long line in two.
        YamlConfiguration lang = new YamlConfiguration();
        lang.loadFromString("""
            claim:
              bar-set-mode-off: '&aLocator bar mode set to &eoff&a. Nothing appears on the bar.'
              bar-current-mode-off: '&7Your locator bar mode is &eoff&7. Nothing appears on the
                bar.'
            """);

        assertTrue(Messages.replaceRetiredDefaults(lang));
        assertEquals(NEW_OFF, lang.getString("claim.bar-set-mode-off"));
        assertEquals("&7Your claim markers are &eoff&7. Player dots are controlled separately.",
            lang.getString("claim.bar-current-mode-off"));
    }

    @Test
    void customisedMessagesAreKeptAndReplacementRunsOnlyOnce() {
        YamlConfiguration lang = new YamlConfiguration();
        lang.set("claim.bar-set", "&dMy own claim bar text: {mode}");
        lang.set("claim.bar-set-mode-off", "&aLocator bar mode set to &eoff&a. Nothing appears on the bar.");

        assertTrue(Messages.replaceRetiredDefaults(lang));
        assertEquals("&dMy own claim bar text: {mode}", lang.getString("claim.bar-set"));
        assertEquals(NEW_OFF, lang.getString("claim.bar-set-mode-off"));

        assertFalse(Messages.replaceRetiredDefaults(lang), "already-updated files must not be rewritten");
    }

    @Test
    void missingKeysAreLeftForTheNormalDefaultsMerge() {
        YamlConfiguration lang = new YamlConfiguration();
        assertFalse(Messages.replaceRetiredDefaults(lang));
        assertFalse(lang.contains("claim.bar-set-mode-off"));
    }
}
