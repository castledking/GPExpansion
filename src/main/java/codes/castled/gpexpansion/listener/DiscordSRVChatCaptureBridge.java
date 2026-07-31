package codes.castled.gpexpansion.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
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

    /**
     * Hooks DiscordSRV now if it is already enabled, otherwise waits for it to enable.
     * <p>
     * GPExpansion is not allowed to declare a load-order dependency on DiscordSRV (that
     * would close the load cycle GPExpansion -> DiscordSRV -> Skript -> GriefPrevention ->
     * GPExpansion), so the hook has to tolerate DiscordSRV enabling after us.
     */
    public void register() {
        if (registerIfAvailable()) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginEnable(PluginEnableEvent event) {
                if (!DISCORDSRV_PLUGIN_NAME.equals(event.getPlugin().getName())) {
                    return;
                }
                registerIfAvailable();
                HandlerList.unregisterAll(this);
            }
        }, plugin);
    }

    private boolean registerIfAvailable() {
        Plugin discordSrv = Bukkit.getPluginManager().getPlugin(DISCORDSRV_PLUGIN_NAME);
        if (discordSrv == null || !discordSrv.isEnabled()) {
            return false;
        }

        try {
            ClassLoader discordSrvClassLoader = discordSrv.getClass().getClassLoader();
            Class<?> eventClass = Class.forName(PRE_PROCESS_EVENT_CLASS, true, discordSrvClassLoader);
            if (!Event.class.isAssignableFrom(eventClass)) {
                plugin.getLogger().warning("DiscordSRV pre-process event is not a Bukkit event on this version; skipping captured-chat bridge");
                return true;
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
        return true;
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
