package dev.towki.gpexpansion.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class Mini {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();

    private Mini() {}

    public static Component deserialize(String input) {
        return MM.deserialize(input);
    }

    public static String deserializeToLegacy(String input) {
        return LEGACY_AMP.serialize(MM.deserialize(input));
    }
}
