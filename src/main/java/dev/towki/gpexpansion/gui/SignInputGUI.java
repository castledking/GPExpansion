package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.function.Consumer;

/**
 * Sign-based text input GUI for searching claims.
 * Opens a sign editor and captures the input when the player clicks Done.
 */
public class SignInputGUI implements Listener {
    
    private final GPExpansionPlugin plugin;
    private final Player player;
    private final Consumer<String> onComplete;
    private final Runnable onCancel;
    private final String[] promptLines;
    
    private Location signLocation;
    private Material originalBlockType;
    private boolean completed = false;
    
    public SignInputGUI(GPExpansionPlugin plugin, Player player, String[] promptLines,
                        Consumer<String> onComplete, Runnable onCancel) {
        this.plugin = plugin;
        this.player = player;
        this.promptLines = promptLines != null ? promptLines : new String[]{"", "", "", ""};
        this.onComplete = onComplete;
        this.onCancel = onCancel;
    }
    
    public void open() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        
        // Find a location for the fake sign (below player's feet, underground)
        signLocation = player.getLocation().clone();
        signLocation.setY(Math.max(signLocation.getWorld().getMinHeight(), signLocation.getBlockY() - 5));
        
        Block block = signLocation.getBlock();
        originalBlockType = block.getType();
        
        // Place a temporary sign
        block.setType(Material.OAK_SIGN);
        
        if (block.getState() instanceof Sign) {
            Sign sign = (Sign) block.getState();
            
            // Set prompt lines on the sign
            try {
                // Try modern API first (1.20+)
                for (int i = 0; i < 4 && i < promptLines.length; i++) {
                    sign.getSide(Side.FRONT).setLine(i, promptLines[i] != null ? promptLines[i] : "");
                }
            } catch (NoSuchMethodError | NoClassDefFoundError e) {
                // Fallback to legacy API
                for (int i = 0; i < 4 && i < promptLines.length; i++) {
                    sign.setLine(i, promptLines[i] != null ? promptLines[i] : "");
                }
            }
            sign.update();
            
            // Open sign editor for player
            runLater(() -> player.openSign(sign), 2L);
            
            // Schedule cleanup in case player doesn't complete
            runLater(() -> {
                if (!completed) {
                    cleanup();
                    if (onCancel != null) {
                        onCancel.run();
                    }
                }
            }, 200L); // 10 second timeout
        } else {
            cleanup();
            if (onCancel != null) {
                onCancel.run();
            }
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        if (!event.getPlayer().equals(player)) return;
        if (!event.getBlock().getLocation().equals(signLocation)) return;
        
        completed = true;
        event.setCancelled(true);
        
        // Collect all non-empty lines as the search input
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String line = event.getLine(i);
            if (line != null && !line.isEmpty()) {
                if (input.length() > 0) input.append(" ");
                input.append(line);
            }
        }
        
        String result = input.toString().trim();
        
        // Cleanup and process
        runLater(() -> {
            cleanup();
            if (onComplete != null && !result.isEmpty()) {
                onComplete.accept(result);
            } else if (onCancel != null) {
                onCancel.run();
            }
        }, 1L);
    }
    
    private void cleanup() {
        HandlerList.unregisterAll(this);
        
        // Restore original block
        if (signLocation != null) {
            Block block = signLocation.getBlock();
            block.setType(originalBlockType != null ? originalBlockType : Material.AIR);
        }
    }
    
    private void runLater(Runnable task, long delayTicks) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runOnEntityLater(plugin, player, task, null, delayTicks);
    }
    
    // Static factory methods
    
    /**
     * Open sign editor for searching claims.
     */
    public static void openSearch(GPExpansionPlugin plugin, Player player,
                                  Consumer<String> onComplete, Runnable onCancel) {
        String[] lines = {"", "Search:", "Enter ID or name", ""};
        new SignInputGUI(plugin, player, lines, onComplete, onCancel).open();
    }
    
    /**
     * Open sign editor for renaming.
     */
    public static void openRename(GPExpansionPlugin plugin, Player player, String currentName,
                                  Consumer<String> onComplete, Runnable onCancel) {
        String[] lines = {currentName != null ? currentName : "", "", "Enter new name", ""};
        new SignInputGUI(plugin, player, lines, onComplete, onCancel).open();
    }
    
    /**
     * Open sign editor for setting description.
     */
    public static void openDescription(GPExpansionPlugin plugin, Player player, String currentDesc,
                                       Consumer<String> onComplete, Runnable onCancel) {
        String[] lines = {currentDesc != null ? currentDesc : "", "", "Max 32 chars", ""};
        new SignInputGUI(plugin, player, lines, onComplete, onCancel).open();
    }
}
