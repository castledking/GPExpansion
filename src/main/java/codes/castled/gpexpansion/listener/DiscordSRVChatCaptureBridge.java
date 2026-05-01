package codes.castled.gpexpansion.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import codes.castled.gpexpansion.GPExpansionPlugin;

import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Cancels DiscordSRV chat forwarding for players whose chat is being captured by GPExpansion.
 */
public final class DiscordSRVChatCaptureBridge {

    private static final String DISCORDSRV_PLUGIN_NAME = "DiscordSRV";
    private static final String PRE_PROCESS_EVENT_CLASS = "github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent";

    private final GPExpansionPlugin plugin;
    private final Predicate<Player> shouldCapture;

    public DiscordSRVChatCaptureBridge(GPExpansionPlugin plugin, Predicate<Player> shouldCapture) {
        this.plugin = plugin;
        this.shouldCapture = shouldCapture;
    }

    public void registerIfAvailable() {
        Plugin discordSrv = Bukkit.getPluginManager().getPlugin(DISCORDSRV_PLUGIN_NAME);
        if (discordSrv == null || !discordSrv.isEnabled()) {
            return;
        }

        try {
            ClassLoader discordSrvClassLoader = discordSrv.getClass().getClassLoader();
            Class<?> eventClass = Class.forName(PRE_PROCESS_EVENT_CLASS, true, discordSrvClassLoader);
            if (!Event.class.isAssignableFrom(eventClass)) {
                plugin.getLogger().warning("DiscordSRV pre-process event is not a Bukkit event on this version; skipping captured-chat bridge");
                return;
            }
            Method getPlayer = eventClass.getMethod("getPlayer");
            Method setCancelled = eventClass.getMethod("setCancelled", boolean.class);

            Listener listener = new Listener() { };
            EventExecutor executor = (ignored, event) -> handleDiscordSrvEvent(eventClass, getPlayer, setCancelled, event);

            Bukkit.getPluginManager().registerEvent(
                eventClass.asSubclass(Event.class),
                listener,
                EventPriority.LOWEST,
                executor,
                plugin,
                false
            );

            plugin.getLogger().info("- Registered DiscordSRV captured-chat bridge");
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            plugin.getLogger().warning("Failed to register DiscordSRV captured-chat bridge: " + e.getMessage());
        }
    }

    private void handleDiscordSrvEvent(Class<?> eventClass, Method getPlayer, Method setCancelled, Event event)
        throws EventException {
        if (!eventClass.isInstance(event)) {
            return;
        }

        try {
            Object playerObject = getPlayer.invoke(event);
            if (!(playerObject instanceof Player player)) {
                return;
            }

            if (shouldCapture.test(player)) {
                setCancelled.invoke(event, true);
            }
        } catch (ReflectiveOperationException e) {
            throw new EventException(e);
        }
    }
}
