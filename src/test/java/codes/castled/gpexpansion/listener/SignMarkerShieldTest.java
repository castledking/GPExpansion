package codes.castled.gpexpansion.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SignChangeEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignMarkerShieldTest {

    @Test
    void managedHeaderIsHiddenFromBracketMarkerListenerThenRestored() {
        Object event = new Object();
        SignMarkerShield<Object, String> shield = new SignMarkerShield<>();
        String visibleHeader = "[sell]";

        assertTrue(easyBlueMapMarkersWouldClaim(visibleHeader, "100", "", ""));

        visibleHeader = shield.shield(event, visibleHeader, true, "GPExpansion");
        assertFalse(easyBlueMapMarkersWouldClaim(visibleHeader, "100", "", ""));

        assertEquals("[sell]", shield.restore(event).orElseThrow());
    }

    @Test
    void unrelatedBracketMarkerRemainsVisible() {
        Object event = new Object();
        SignMarkerShield<Object, String> shield = new SignMarkerShield<>();

        String visibleHeader = shield.shield(event, "[bank]", false, "GPExpansion");

        assertTrue(easyBlueMapMarkersWouldClaim(visibleHeader, "Town bank", "", ""));
        assertEquals("[bank]", visibleHeader);
        assertTrue(shield.restore(event).isEmpty());
    }

    @Test
    void listenerPrioritiesSurroundNormalPriorityMarkerPlugins() throws ReflectiveOperationException {
        EventHandler shieldHandler = SignListener.class
            .getDeclaredMethod("shieldManagedSignFromBlueMapMarkers", SignChangeEvent.class)
            .getAnnotation(EventHandler.class);
        EventHandler restoreHandler = SignListener.class
            .getDeclaredMethod("restoreManagedSignAfterBlueMapMarkers", SignChangeEvent.class)
            .getAnnotation(EventHandler.class);
        EventHandler creationHandler = SignListener.class
            .getDeclaredMethod("onSignCreate", SignChangeEvent.class)
            .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.LOW, shieldHandler.priority());
        assertTrue(shieldHandler.ignoreCancelled());
        assertEquals(EventPriority.HIGH, restoreHandler.priority());
        assertFalse(restoreHandler.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, creationHandler.priority());
    }

    @Test
    void compatibilityOptionIsDefaultedForNewAndExistingConfigs() throws IOException {
        String bundledConfig = Files.readString(Path.of("src/main/resources/config.yml"));
        String configManager = Files.readString(Path.of(
            "src/main/java/codes/castled/gpexpansion/util/Config.java"));

        assertTrue(bundledConfig.contains("prevent-bluemap-markers: true"));
        assertTrue(configManager.contains(
            "DEFAULTS.put(\"signs.compatibility.prevent-bluemap-markers\", true)"));
    }

    private static boolean easyBlueMapMarkersWouldClaim(String header, String... descriptionLines) {
        if (header.isBlank() || !header.startsWith("[") || !header.endsWith("]")) {
            return false;
        }
        StringBuilder description = new StringBuilder();
        for (String line : descriptionLines) {
            description.append(line).append(' ');
        }
        return !description.toString().isBlank();
    }
}
