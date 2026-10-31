package codes.castled.gpexpansion.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandSender;
import org.bukkit.event.block.SignChangeEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Mini {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    /**
     * Cross-platform component sink.
     *
     * <p>On Paper-family servers (including Folia) Adventure is on the server classpath, so the
     * component can be handed to the receiver natively — hover/click data is preserved. On plain
     * Spigot there is no server-side Adventure; the component is rendered to a legacy section
     * string instead (colours survive, hover/click do not — those concepts don't exist there).
     *
     * <p>The Paper branch is reflective rather than statically typed so this class also compiles
     * against the bare Spigot API (the {@code spigot-compat} profile of the compat check build).
     */
    private static volatile boolean paperProbed = false;
    private static boolean paperNative = false;
    private static final Map<Class<?>, Method> NATIVE_SEND = new ConcurrentHashMap<>();

    private Mini() {}

    public static Component deserialize(String input) {
        return MM.deserialize(input);
    }

    public static String deserializeToLegacy(String input) {
        return LEGACY_AMP.serialize(MM.deserialize(input));
    }

    /**
     * Sends a component to any command sender on any platform.
     */
    public static void send(CommandSender receiver, ComponentLike message) {
        if (receiver == null) return;
        if (message == null) return;
        if (!paperProbed) {
            synchronized (Mini.class) {
                if (!paperProbed) {
                    paperNative = codes.castled.gpexpansion.platform.PlatformDetection.getPlatform()
                            != codes.castled.gpexpansion.platform.PlatformDetection.Platform.SPIGOT;
                    paperProbed = true;
                }
            }
        }
        if (paperNative) {
            try {
                Component component = message.asComponent();
                Method m = NATIVE_SEND.computeIfAbsent(receiver.getClass(), cls -> {
                    try {
                        return cls.getMethod("sendMessage", component.getClass());
                    } catch (ReflectiveOperationException e) {
                        return null;
                    }
                });
                if (m != null) {
                    m.invoke(receiver, component);
                    return;
                }
            } catch (Throwable ignored) {
                // Fall through to the legacy string below.
            }
        }
        receiver.sendMessage(LEGACY_SECTION.serialize(message.asComponent()));
    }

    // ====================== SIGN / INVENTORY / META COMPAT ======================

    /**
     * Cross-platform read of a sign side line. Paper offers a Component getter while Spigot
     * exposes only the legacy string variant; both variants produce an equivalent component.
     */
    public static @org.jetbrains.annotations.NotNull Component signLine(SignSide side, int index) {
        return LEGACY_SECTION.deserialize(side.getLine(index));
    }

    /**
     * Cross-platform write of a sign side line using the legacy section string variant, which
     * exists on both Paper and Spigot.
     */
    public static void signLine(SignSide side, int index, ComponentLike value) {
        if (value == null) {
            side.setLine(index, "");
            return;
        }
        side.setLine(index, LEGACY_SECTION.serialize(value.asComponent()));
    }

    /** All lines of a sign side as components. */
    public static Component[] signLines(SignSide side) {
        Component[] out = new Component[4];
        for (int i = 0; i < 4; i++) {
            out[i] = LEGACY_SECTION.deserialize(side.getLine(i));
        }
        return out;
    }

    /** Cross-platform read of a SignChangeEvent line. */
    public static Component eventLine(SignChangeEvent event, int index) {
        return LEGACY_SECTION.deserialize(event.getLine(index));
    }

    /** Cross-platform write of a SignChangeEvent line. */
    public static void eventLine(SignChangeEvent event, int index, ComponentLike value) {
        if (value == null) {
            event.setLine(index, "");
            return;
        }
        event.setLine(index, LEGACY_SECTION.serialize(value.asComponent()));
    }

    /** Cross-platform plain-text flattening helper (shaded PlainText serializer). */
    public static String plain(ComponentLike like) {
        return PlainTextComponentSerializer.plainText().serialize(like.asComponent());
    }

    /** Legacy ampersand -> {@code §} translation used by legacy Bukkit string APIs. */
    public static String toSection(String legacyAmp) {
        return legacyAmp != null ? legacyAmp.replace('&', '§') : null;
    }

    // ====================== INVENTORY / META COMPAT ======================

    /**
     * Cross-platform inventory title. Returns a legacy section string that works
     * on both Paper (where Component is accepted) and Spigot (where only String is accepted).
     * On Paper, the String will be re-parsed to Component by the API; on Spigot it's used directly.
     */
    public static String inventoryTitle(ComponentLike title) {
        if (title == null) return "";
        return LEGACY_SECTION.serialize(title.asComponent());
    }

    /**
     * Cross-platform display name for ItemMeta.
     * Returns a legacy section string that works on both Paper and Spigot.
     */
    public static String displayName(ComponentLike name) {
        if (name == null) return "";
        return LEGACY_SECTION.serialize(name.asComponent());
    }

    /**
     * Cross-platform lore for ItemMeta.
     * Returns a List<String> of legacy section strings that works on both Paper and Spigot.
     */
    public static java.util.List<String> lore(java.util.List<? extends ComponentLike> lore) {
        if (lore == null) return java.util.Collections.emptyList();
        java.util.List<String> out = new java.util.ArrayList<>(lore.size());
        for (ComponentLike line : lore) {
            out.add(line == null ? "" : LEGACY_SECTION.serialize(line.asComponent()));
        }
        return out;
    }
}
