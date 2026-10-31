package codes.castled.gpexpansion.platform;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Registers listeners for Paper-specific (and Spigot-equivalent) events via Bukkit's reflective
 * {@code PluginManager#registerEvent} API so the entire codebase compiles cleanly against both
 * {@code paper-api} and bare {@code spigot-api} without {@code NoClassDefFoundError} risks.
 */
public final class PaperEvents {

    private static final String SERVER_ADVENTURE_PKG =
            String.join(".", "net", "kyori", "adventure");

    private PaperEvents() {}

    public static boolean hasPaperAsyncChat() {
        return PlatformDetection.classExists("io.papermc.paper.event.player.AsyncChatEvent");
    }

    /**
     * Registers a {@link EventPriority#LOWEST} handler on Paper's {@code AsyncChatEvent} when
     * present. When {@code interceptor} returns {@code true}, the chat event is cancelled.
     *
     * @return {@code true} if Paper's {@code AsyncChatEvent} was found and hooked
     */
    @SuppressWarnings("unchecked")
    public static boolean registerAsyncChat(Plugin plugin, Listener owner, BiPredicate<Player, String> interceptor) {
        if (!hasPaperAsyncChat()) return false;
        try {
            ClassLoader serverLoader = Bukkit.class.getClassLoader();
            Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(
                    "io.papermc.paper.event.player.AsyncChatEvent", false, serverLoader);
            Method messageMethod = eventClass.getMethod("message");

            Class<?> compClass = Class.forName(SERVER_ADVENTURE_PKG + ".text.Component", false, serverLoader);
            Class<?> plainCls = Class.forName(
                    SERVER_ADVENTURE_PKG + ".text.serializer.plain.PlainTextComponentSerializer", false, serverLoader);
            Object plainSerializer = plainCls.getMethod("plainText").invoke(null);
            Method serializeMethod;
            try {
                serializeMethod = plainCls.getMethod("serialize", compClass);
            } catch (NoSuchMethodException e) {
                serializeMethod = plainCls.getMethod("serialize", Object.class);
            }
            final Method finalSerialize = serializeMethod;

            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    owner,
                    EventPriority.LOWEST,
                    (listener, event) -> {
                        if (!eventClass.isInstance(event) || !(event instanceof PlayerEvent playerEvent)) return;
                        try {
                            Object comp = messageMethod.invoke(event);
                            String plain = comp != null ? (String) finalSerialize.invoke(plainSerializer, comp) : "";
                            if (interceptor.test(playerEvent.getPlayer(), plain) && event instanceof Cancellable cancellable) {
                                cancellable.setCancelled(true);
                            }
                        } catch (Throwable ignored) {}
                    },
                    plugin,
                    false
            );
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Registers a {@link EventPriority#MONITOR} handler on Paper's {@code WorldGameRuleChangeEvent}
     * when available.
     */
    @SuppressWarnings("unchecked")
    public static boolean registerWorldGameRuleChange(Plugin plugin, Listener owner, Consumer<GameRule<?>> handler) {
        if (!PlatformDetection.classExists("io.papermc.paper.event.world.WorldGameRuleChangeEvent")) {
            return false;
        }
        try {
            Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(
                    "io.papermc.paper.event.world.WorldGameRuleChangeEvent");
            Method getGameRule = eventClass.getMethod("getGameRule");

            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    owner,
                    EventPriority.MONITOR,
                    (listener, event) -> {
                        if (!eventClass.isInstance(event)) return;
                        try {
                            Object rule = getGameRule.invoke(event);
                            if (rule instanceof GameRule<?> gameRule) {
                                handler.accept(gameRule);
                            }
                        } catch (Throwable ignored) {}
                    },
                    plugin,
                    false
            );
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Registers a {@link EventPriority#LOWEST} handler that intercepts opening a sign editor on
     * Paper ({@code io.papermc.paper.event.player.PlayerOpenSignEvent}) or Spigot
     * ({@code org.bukkit.event.player.PlayerSignOpenEvent}). When {@code shouldCancel} returns
     * {@code true}, the sign editor popup is cancelled.
     */
    @SuppressWarnings("unchecked")
    public static boolean registerSignOpen(Plugin plugin, Listener owner, BiPredicate<Player, Sign> shouldCancel) {
        String[] candidates = {
                "io.papermc.paper.event.player.PlayerOpenSignEvent",
                "org.bukkit.event.player.PlayerSignOpenEvent"
        };
        for (String className : candidates) {
            if (!PlatformDetection.classExists(className)) continue;
            try {
                Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(className);
                Method getSign = eventClass.getMethod("getSign");

                Bukkit.getPluginManager().registerEvent(
                        eventClass,
                        owner,
                        EventPriority.LOWEST,
                        (listener, event) -> {
                            if (!eventClass.isInstance(event) || !(event instanceof PlayerEvent playerEvent)) return;
                            try {
                                Object signObj = getSign.invoke(event);
                                if (signObj instanceof Sign sign
                                        && shouldCancel.test(playerEvent.getPlayer(), sign)
                                        && event instanceof Cancellable cancellable) {
                                    cancellable.setCancelled(true);
                                }
                            } catch (Throwable ignored) {}
                        },
                        plugin,
                        false
                );
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
