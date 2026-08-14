package codes.castled.gpexpansion.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VersionManagerMailboxProtocolTest {

    @Test
    void freshBundledVirtualDefaultBecomesRealOnGp3d() {
        assertEquals("real", VersionManager.mailboxProtocolToWrite(
            true, true, null, true));
    }

    @Test
    void freshBundledVirtualDefaultStaysVirtualOnUpstreamGp() {
        assertEquals("virtual", VersionManager.mailboxProtocolToWrite(
            true, true, null, false));
    }

    @Test
    void existingExplicitVirtualChoiceIsPreservedOnGp3d() {
        assertNull(VersionManager.mailboxProtocolToWrite(
            false, true, null, true));
    }

    @Test
    void missingNestedSettingMigratesLegacyChoice() {
        assertEquals("real", VersionManager.mailboxProtocolToWrite(
            false, false, "real", false));
    }

    @Test
    void missingSettingUsesDetectedServerFlavor() {
        assertEquals("real", VersionManager.mailboxProtocolToWrite(
            false, false, null, true));
        assertEquals("virtual", VersionManager.mailboxProtocolToWrite(
            false, false, null, false));
    }
}
