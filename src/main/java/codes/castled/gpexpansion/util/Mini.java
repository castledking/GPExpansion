package codes.castled.gpexpansion.util;

import codes.castled.gpexpansion.platform.PlatformDetection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Mini {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
            .character('\u00a7')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /**
     * Assembled dynamically so {@code maven-shade-plugin} does not rewrite server-side
     * Paper Adventure class names into our relocated {@code codes.castled.gpexpansion.libs.kyori} package.
     */
    private static final String SERVER_ADVENTURE_PKG =
            String.join(".", "net", "kyori", "adventure");

    private static volatile boolean paperBridgeProbed = false;
    private static boolean paperBridgeAvailable = false;
    private static Object paperMiniMessage;
    private static Method paperMiniDeserialize;
    private static Method paperMiniSerialize;
    private static Class<?> paperComponentClass;
    private static Method paperAudienceSend;
    private static Method paperCreateInvSize;
    private static Method paperCreateInvType;
    private static Method paperMetaGetDisplayName;
    private static Method paperMetaSetDisplayName;
    private static Method paperMetaGetLore;
    private static Method paperMetaSetLore;

    private Mini() {}

    private static void ensurePaperBridge() {
        if (paperBridgeProbed) return;
        synchronized (Mini.class) {
            if (paperBridgeProbed) return;
            try {
                if (PlatformDetection.getPlatform() != PlatformDetection.Platform.SPIGOT) {
                    ClassLoader serverLoader = Bukkit.class.getClassLoader();
                    Class<?> compCls = Class.forName(SERVER_ADVENTURE_PKG + ".text.Component", false, serverLoader);
                    Class<?> audienceCls = Class.forName(SERVER_ADVENTURE_PKG + ".audience.Audience", false, serverLoader);
                    Class<?> mmCls = Class.forName(SERVER_ADVENTURE_PKG + ".text.minimessage.MiniMessage", false, serverLoader);

                    Object mmInst = mmCls.getMethod("miniMessage").invoke(null);
                    Method mmDeser;
                    try {
                        mmDeser = mmCls.getMethod("deserialize", String.class);
                    } catch (NoSuchMethodException e) {
                        mmDeser = mmCls.getMethod("deserialize", Object.class);
                    }
                    Method mmSer;
                    try {
                        mmSer = mmCls.getMethod("serialize", compCls);
                    } catch (NoSuchMethodException e) {
                        mmSer = mmCls.getMethod("serialize", Object.class);
                    }
                    Method audSend = audienceCls.getMethod("sendMessage", compCls);

                    paperComponentClass = compCls;
                    paperMiniMessage = mmInst;
                    paperMiniDeserialize = mmDeser;
                    paperMiniSerialize = mmSer;
                    paperAudienceSend = audSend;

                    try {
                        paperCreateInvSize = Bukkit.class.getMethod("createInventory", InventoryHolder.class, int.class, compCls);
                        paperCreateInvType = Bukkit.class.getMethod("createInventory", InventoryHolder.class, InventoryType.class, compCls);
                    } catch (NoSuchMethodException ignored) {}

                    try {
                        paperMetaGetDisplayName = ItemMeta.class.getMethod("displayName");
                        paperMetaSetDisplayName = ItemMeta.class.getMethod("displayName", compCls);
                        paperMetaGetLore = ItemMeta.class.getMethod("lore");
                        paperMetaSetLore = ItemMeta.class.getMethod("lore", List.class);
                    } catch (NoSuchMethodException ignored) {}

                    paperBridgeAvailable = true;
                }
            } catch (Throwable ignored) {
                paperBridgeAvailable = false;
            } finally {
                paperBridgeProbed = true;
            }
        }
    }

    private static @Nullable Object toPaperComponent(@Nullable ComponentLike like) {
        if (like == null) return null;
        ensurePaperBridge();
        if (!paperBridgeAvailable) return null;
        try {
            String serialized = MM.serialize(like.asComponent());
            return paperMiniDeserialize.invoke(paperMiniMessage, serialized);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Component fromPaperComponent(@Nullable Object paperComp) {
        if (paperComp == null) return null;
        ensurePaperBridge();
        if (!paperBridgeAvailable || !paperComponentClass.isInstance(paperComp)) return null;
        try {
            String serialized = (String) paperMiniSerialize.invoke(paperMiniMessage, paperComp);
            return MM.deserialize(serialized);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Component deserialize(String input) {
        return MM.deserialize(input);
    }

    public static String deserializeToLegacy(String input) {
        return LEGACY_AMP.serialize(MM.deserialize(input));
    }

    /**
     * Sends a component to any command sender on any platform, preserving hover/click
     * interactivity and 24-bit RGB colours on both Paper and Spigot.
     */
    public static void send(CommandSender receiver, ComponentLike message) {
        if (receiver == null || message == null) return;
        Component component = message.asComponent();

        // 1. Paper / Folia / Purpur / Canvas native Audience path
        Object paperComp = toPaperComponent(component);
        if (paperComp != null && paperAudienceSend != null
                && paperAudienceSend.getDeclaringClass().isInstance(receiver)) {
            try {
                paperAudienceSend.invoke(receiver, paperComp);
                return;
            } catch (Throwable ignored) {
                // Fall through to Bungee / legacy below.
            }
        }

        // 2. Spigot rich chat path (preserves clickable [CONFIRM]/[EVICT], hover tooltips, and hex RGB)
        if (!(receiver instanceof ConsoleCommandSender)) {
            try {
                BaseComponent[] bungee = toBungee(component);
                receiver.spigot().sendMessage(bungee);
                return;
            } catch (Throwable ignored) {
                // Fall through to legacy section string below.
            }
        }

        // 3. Console / plain legacy fallback
        receiver.sendMessage(LEGACY_SECTION.serialize(component));
    }

    /**
     * Direct Adventure {@link Component} -> BungeeCord {@link BaseComponent} tree converter.
     *
     * <p>Constructs BungeeCord Java objects directly rather than round-tripping through Gson JSON,
     * avoiding both the 1.21.4/1.21.5 {@code clickEvent} vs {@code click_event} JSON schema split
     * and reflective mutation of {@code Gson.factories} final fields on Java 25+.
     */
    @SuppressWarnings("deprecation")
    public static BaseComponent[] toBungee(@Nullable ComponentLike like) {
        if (like == null) return new BaseComponent[]{new net.md_5.bungee.api.chat.TextComponent("")};
        return new BaseComponent[]{convertNode(like.asComponent())};
    }

    @SuppressWarnings("deprecation")
    private static BaseComponent convertNode(Component node) {
        BaseComponent out;
        if (node instanceof net.kyori.adventure.text.TextComponent textNode) {
            out = new net.md_5.bungee.api.chat.TextComponent(textNode.content());
        } else if (node instanceof net.kyori.adventure.text.TranslatableComponent transNode) {
            List<net.kyori.adventure.text.TranslationArgument> args = transNode.arguments();
            Object[] bungeeArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                bungeeArgs[i] = convertNode(args.get(i).asComponent());
            }
            out = new net.md_5.bungee.api.chat.TranslatableComponent(transNode.key(), bungeeArgs);
        } else if (node instanceof net.kyori.adventure.text.KeybindComponent keyNode) {
            out = new net.md_5.bungee.api.chat.KeybindComponent(keyNode.keybind());
        } else {
            out = new net.md_5.bungee.api.chat.TextComponent(plain(node));
        }

        net.kyori.adventure.text.format.Style style = node.style();
        net.kyori.adventure.text.format.TextColor color = style.color();
        if (color != null) {
            out.setColor(net.md_5.bungee.api.ChatColor.of(color.asHexString()));
        }
        applyDecoration(style, TextDecoration.BOLD, out::setBold);
        applyDecoration(style, TextDecoration.ITALIC, out::setItalic);
        applyDecoration(style, TextDecoration.UNDERLINED, out::setUnderlined);
        applyDecoration(style, TextDecoration.STRIKETHROUGH, out::setStrikethrough);
        applyDecoration(style, TextDecoration.OBFUSCATED, out::setObfuscated);

        if (style.insertion() != null) {
            out.setInsertion(style.insertion());
        }

        net.kyori.adventure.text.event.ClickEvent<?> click = style.clickEvent();
        if (click != null) {
            net.md_5.bungee.api.chat.ClickEvent.Action action = mapClickAction(click.action().name());
            String clickVal = extractClickValue(click.payload());
            if (action != null && clickVal != null) {
                out.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(action, clickVal));
            }
        }

        net.kyori.adventure.text.event.HoverEvent<?> hover = style.hoverEvent();
        if (hover != null && hover.action() == net.kyori.adventure.text.event.HoverEvent.Action.SHOW_TEXT
                && hover.value() instanceof Component hoverComp) {
            BaseComponent[] hoverBungee = new BaseComponent[]{convertNode(hoverComp)};
            try {
                out.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.hover.content.Text(hoverBungee)
                ));
            } catch (NoClassDefFoundError | NoSuchMethodError ignored) {
                out.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        hoverBungee
                ));
            }
        }

        for (Component child : node.children()) {
            out.addExtra(convertNode(child));
        }
        return out;
    }

    private static void applyDecoration(net.kyori.adventure.text.format.Style style,
                                        TextDecoration dec,
                                        java.util.function.Consumer<Boolean> setter) {
        TextDecoration.State state = style.decoration(dec);
        if (state == TextDecoration.State.TRUE) {
            setter.accept(Boolean.TRUE);
        } else if (state == TextDecoration.State.FALSE) {
            setter.accept(Boolean.FALSE);
        }
    }

    private static @Nullable String extractClickValue(
            net.kyori.adventure.text.event.ClickEvent.Payload payload) {
        if (payload instanceof net.kyori.adventure.text.event.ClickEvent.Payload.Text textPayload) {
            return textPayload.value();
        }
        if (payload instanceof net.kyori.adventure.text.event.ClickEvent.Payload.Int intPayload) {
            return String.valueOf(intPayload.integer());
        }
        return null;
    }

    private static @Nullable net.md_5.bungee.api.chat.ClickEvent.Action mapClickAction(String actionName) {
        if (actionName == null) return null;
        return switch (actionName) {
            case "open_url" -> net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL;
            case "open_file" -> net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_FILE;
            case "run_command" -> net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND;
            case "suggest_command" -> net.md_5.bungee.api.chat.ClickEvent.Action.SUGGEST_COMMAND;
            case "change_page" -> net.md_5.bungee.api.chat.ClickEvent.Action.CHANGE_PAGE;
            case "copy_to_clipboard" -> {
                try {
                    yield net.md_5.bungee.api.chat.ClickEvent.Action.valueOf("COPY_TO_CLIPBOARD");
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    // ====================== SIGN / INVENTORY / META COMPAT ======================

    /**
     * Cross-platform read of a sign side line.
     */
    @SuppressWarnings("deprecation")
    public static @NotNull Component signLine(SignSide side, int index) {
        return LEGACY_SECTION.deserialize(side.getLine(index));
    }

    /**
     * Cross-platform write of a sign side line using the legacy section string variant, which
     * exists on both Paper and Spigot.
     */
    @SuppressWarnings("deprecation")
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
            out[i] = signLine(side, i);
        }
        return out;
    }

    /** Cross-platform read of a SignChangeEvent line. */
    @SuppressWarnings("deprecation")
    public static Component eventLine(SignChangeEvent event, int index) {
        String raw = event.getLine(index);
        return raw == null ? Component.empty() : LEGACY_SECTION.deserialize(raw);
    }

    /** Cross-platform write of a SignChangeEvent line. */
    @SuppressWarnings("deprecation")
    public static void eventLine(SignChangeEvent event, int index, ComponentLike value) {
        if (value == null) {
            event.setLine(index, "");
            return;
        }
        event.setLine(index, LEGACY_SECTION.serialize(value.asComponent()));
    }

    /** Cross-platform plain-text flattening helper (shaded PlainText serializer). */
    public static String plain(ComponentLike like) {
        if (like == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(like.asComponent());
    }

    /** Legacy ampersand -> {@code §} translation used by legacy Bukkit string APIs. */
    public static String toSection(String legacyAmp) {
        return legacyAmp != null ? legacyAmp.replace('&', '\u00a7') : null;
    }

    /** Legacy ampersand + hex (&amp;#RRGGBB) deserialization helper. */
    public static Component fromLegacyAmpersand(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return LEGACY_AMP.deserialize(text);
    }

    // ====================== INVENTORY / META COMPAT ======================

    /**
     * Creates a chest inventory with a rich component title on Paper and a 24-bit hex section
     * string title on Spigot.
     */
    @SuppressWarnings("deprecation")
    public static Inventory createInventory(@Nullable InventoryHolder owner, int size, ComponentLike title) {
        Object paperTitle = toPaperComponent(title != null ? title : Component.empty());
        if (paperTitle != null && paperCreateInvSize != null) {
            try {
                return (Inventory) paperCreateInvSize.invoke(null, owner, size, paperTitle);
            } catch (Throwable ignored) {}
        }
        return Bukkit.createInventory(owner, size, inventoryTitle(title));
    }

    /**
     * Creates a typed inventory (e.g. HOPPER) with a rich component title on Paper and a 24-bit
     * hex section string title on Spigot.
     */
    @SuppressWarnings("deprecation")
    public static Inventory createInventory(@Nullable InventoryHolder owner, InventoryType type, ComponentLike title) {
        Object paperTitle = toPaperComponent(title != null ? title : Component.empty());
        if (paperTitle != null && paperCreateInvType != null) {
            try {
                return (Inventory) paperCreateInvType.invoke(null, owner, type, paperTitle);
            } catch (Throwable ignored) {}
        }
        return Bukkit.createInventory(owner, type, inventoryTitle(title));
    }

    /**
     * Sets an ItemMeta display name natively on Paper (preserving explicit non-italic state and
     * custom fonts) and via 24-bit hex legacy section string on Spigot.
     *
     * <p>An empty component (used on GUI filler panes) becomes {@code " "} on Spigot because
     * CraftBukkit's {@code fromStringOrNull("")} returns {@code null} and would otherwise expose
     * the underlying vanilla material name.
     */
    @SuppressWarnings("deprecation")
    public static void setDisplayName(@Nullable ItemMeta meta, @Nullable ComponentLike name) {
        if (meta == null) return;
        if (name == null) {
            if (paperBridgeAvailable && paperMetaSetDisplayName != null) {
                try {
                    paperMetaSetDisplayName.invoke(meta, new Object[]{null});
                    return;
                } catch (Throwable ignored) {}
            }
            meta.setDisplayName(null);
            return;
        }
        Object paperName = toPaperComponent(name);
        if (paperName != null && paperMetaSetDisplayName != null) {
            try {
                paperMetaSetDisplayName.invoke(meta, paperName);
                return;
            } catch (Throwable ignored) {}
        }
        String legacy = displayName(name);
        meta.setDisplayName(legacy.isEmpty() ? " " : legacy);
    }

    /**
     * Sets ItemMeta lore natively on Paper and via 24-bit hex legacy section strings on Spigot.
     */
    @SuppressWarnings("deprecation")
    public static void setLore(@Nullable ItemMeta meta, @Nullable List<? extends ComponentLike> loreLines) {
        if (meta == null) return;
        if (loreLines == null) {
            if (paperBridgeAvailable && paperMetaSetLore != null) {
                try {
                    paperMetaSetLore.invoke(meta, new Object[]{null});
                    return;
                } catch (Throwable ignored) {}
            }
            meta.setLore(null);
            return;
        }
        ensurePaperBridge();
        if (paperBridgeAvailable && paperMetaSetLore != null) {
            try {
                List<Object> paperList = new ArrayList<>(loreLines.size());
                boolean allConverted = true;
                for (ComponentLike line : loreLines) {
                    Object converted = toPaperComponent(line != null ? line : Component.empty());
                    if (converted == null) {
                        allConverted = false;
                        break;
                    }
                    paperList.add(converted);
                }
                if (allConverted) {
                    paperMetaSetLore.invoke(meta, paperList);
                    return;
                }
            } catch (Throwable ignored) {}
        }
        meta.setLore(lore(loreLines));
    }

    /**
     * Reads existing lore from an {@link ItemMeta} and appends additional component lines.
     */
    @SuppressWarnings({"deprecation", "unchecked"})
    public static void appendLore(@Nullable ItemMeta meta, @NotNull List<? extends ComponentLike> extraLines) {
        if (meta == null || extraLines.isEmpty()) return;
        List<Component> combined = new ArrayList<>();
        ensurePaperBridge();
        boolean readViaPaper = false;
        if (paperBridgeAvailable && paperMetaGetLore != null && meta.hasLore()) {
            try {
                List<Object> rawLore = (List<Object>) paperMetaGetLore.invoke(meta);
                if (rawLore != null) {
                    readViaPaper = true;
                    for (Object entry : rawLore) {
                        Component c = fromPaperComponent(entry);
                        combined.add(c != null ? c : Component.empty());
                    }
                }
            } catch (Throwable ignored) {
                combined.clear();
                readViaPaper = false;
            }
        }
        if (!readViaPaper && meta.hasLore()) {
            List<String> legacyLore = meta.getLore();
            if (legacyLore != null) {
                for (String line : legacyLore) {
                    combined.add(line == null || line.isEmpty()
                            ? Component.empty()
                            : LEGACY_SECTION.deserialize(line).decoration(TextDecoration.ITALIC, false));
                }
            }
        }
        for (ComponentLike extra : extraLines) {
            combined.add(extra != null ? extra.asComponent() : Component.empty());
        }
        setLore(meta, combined);
    }

    /**
     * Reads an {@link ItemMeta}'s display name as plain stripped text, or {@code null} if unset.
     */
    @SuppressWarnings("deprecation")
    public static @Nullable String getDisplayNamePlain(@Nullable ItemMeta meta) {
        if (meta == null || !meta.hasDisplayName()) return null;
        ensurePaperBridge();
        if (paperBridgeAvailable && paperMetaGetDisplayName != null) {
            try {
                Object paperComp = paperMetaGetDisplayName.invoke(meta);
                Component c = fromPaperComponent(paperComp);
                if (c != null) {
                    return plain(c);
                }
            } catch (Throwable ignored) {}
        }
        String raw = meta.getDisplayName();
        return raw == null ? null : plain(LEGACY_SECTION.deserialize(raw));
    }

    /**
     * Cross-version enchantment glow helper (uses NamespacedKey lookup so 1.20.x servers
     * where {@code Enchantment.UNBREAKING} was still named {@code DURABILITY} do not throw
     * {@code NoSuchFieldError}).
     */
    public static void applyEnchantGlow(@Nullable ItemMeta meta) {
        if (meta == null) return;
        try {
            org.bukkit.enchantments.Enchantment ench =
                    org.bukkit.enchantments.Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("unbreaking"));
            if (ench != null) {
                meta.addEnchant(ench, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Cross-platform inventory title string fallback.
     */
    public static String inventoryTitle(ComponentLike title) {
        if (title == null) return "";
        return LEGACY_SECTION.serialize(title.asComponent());
    }

    /**
     * Cross-platform display name legacy string fallback.
     */
    public static String displayName(ComponentLike name) {
        if (name == null) return "";
        return LEGACY_SECTION.serialize(name.asComponent());
    }

    /**
     * Cross-platform lore legacy string list fallback.
     */
    public static List<String> lore(List<? extends ComponentLike> lore) {
        if (lore == null) return Collections.emptyList();
        List<String> out = new ArrayList<>(lore.size());
        for (ComponentLike line : lore) {
            out.add(line == null ? "" : LEGACY_SECTION.serialize(line.asComponent()));
        }
        return out;
    }
}
