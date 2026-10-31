package codes.castled.gpexpansion.gui;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.platform.PaperEvents;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One-shot chat input for GUIs: the next chat line a player sends is captured instead of
 * broadcast, and handed to a callback on the player's own thread. Typing {@code cancel}
 * (or waiting out the timeout) runs the cancel callback instead.
 */
public final class ChatPromptManager {

    private static final long TIMEOUT_MS = 120_000L;

    private record Prompt(Consumer<String> onInput, Runnable onCancel, long expiresAt) {}

    private final GPExpansionPlugin plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public ChatPromptManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Ask a player for a line of chat. The prompt message (a lang key) is sent right away.
     */
    public void begin(Player player, String promptKey, String[] placeholders, Consumer<String> onInput, Runnable onCancel) {
        prompts.put(player.getUniqueId(), new Prompt(onInput, onCancel, System.currentTimeMillis() + TIMEOUT_MS));
        plugin.getMessages().send(player, promptKey, placeholders == null ? new String[0] : placeholders);
    }

    public boolean hasPending(UUID playerId) {
        Prompt prompt = prompts.get(playerId);
        if (prompt == null) return false;
        if (System.currentTimeMillis() > prompt.expiresAt()) {
            prompts.remove(playerId);
            return false;
        }
        return true;
    }

    /** Hand a captured chat line to the waiting prompt. Must run on the player's thread. */
    void handle(Player player, String message) {
        Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) return;
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("cancel")) {
            plugin.getMessages().send(player, "gui.prompt-cancelled");
            if (prompt.onCancel() != null) prompt.onCancel().run();
            return;
        }
        prompt.onInput().accept(trimmed);
    }

    public void cancel(UUID playerId) {
        prompts.remove(playerId);
    }

    /** Spigot + Paper chat listener (hooks Paper's AsyncChatEvent reflectively when present). */
    public static final class ChatListener implements Listener {
        private final GPExpansionPlugin plugin;
        private final ChatPromptManager manager;
        private boolean paperChatHooked;

        public ChatListener(GPExpansionPlugin plugin, ChatPromptManager manager) {
            this.plugin = plugin;
            this.manager = manager;
        }

        public boolean registerPaperHook() {
            this.paperChatHooked = PaperEvents.registerAsyncChat(plugin, this, (player, message) -> {
                if (!manager.hasPending(player.getUniqueId()) || message.startsWith("/")) {
                    return false;
                }
                plugin.getSchedulerFacade().runAtEntity(player, () -> manager.handle(player, message));
                return true;
            });
            return this.paperChatHooked;
        }

        @SuppressWarnings("deprecation")
        @EventHandler(priority = EventPriority.LOWEST)
        public void onChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            if (!manager.hasPending(player.getUniqueId()) || event.getMessage().startsWith("/")) return;
            event.setCancelled(true);
            if (!paperChatHooked) {
                String message = event.getMessage();
                plugin.getSchedulerFacade().runAtEntity(player, () -> manager.handle(player, message));
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            manager.cancel(event.getPlayer().getUniqueId());
        }
    }
}
