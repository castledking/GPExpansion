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
    private int inputLine = 0; // Which line the user should type on
    
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
        // Use player's current location so we're in the same region
        // IMPORTANT: Use block coordinates for proper comparison with SignChangeEvent
        Location playerLoc = player.getLocation();
        signLocation = new Location(
            playerLoc.getWorld(),
            playerLoc.getBlockX(),
            Math.max(playerLoc.getWorld().getMinHeight(), playerLoc.getBlockY() - 5),
            playerLoc.getBlockZ()
        );
        
        // Schedule block operations on the correct region thread
        runAtSignLocation(() -> {
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
                
                // Open sign editor for player - must run on sign's region thread (not player's)
                runAtSignLocationDelayed(() -> player.openSign(sign), 2L);
                
                // Schedule cleanup in case player doesn't complete
                runOnPlayer(() -> {
                    if (!completed) {
                        cleanupSign();
                        if (onCancel != null) {
                            onCancel.run();
                        }
                    }
                }, 200L); // 10 second timeout
            } else {
                cleanupSign();
                if (onCancel != null) {
                    runOnPlayer(() -> onCancel.run(), 1L);
                }
            }
        });
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        if (!event.getPlayer().equals(player)) return;
        if (!event.getBlock().getLocation().equals(signLocation)) return;
        
        completed = true;
        event.setCancelled(true);
        
        // Get the user input from the designated input line only
        String rawInput = event.getLine(inputLine);
        final String result = (rawInput != null ? rawInput : "").trim();
        
        // Cleanup and process - run cleanup on sign location thread, callback on player thread
        runOnPlayer(() -> {
            cleanupSign();
            if (onComplete != null && !result.isEmpty()) {
                onComplete.accept(result);
            } else if (onCancel != null) {
                onCancel.run();
            }
        }, 1L);
    }
    
    private void cleanupSign() {
        HandlerList.unregisterAll(this);
        
        // Restore original block - schedule on sign's region thread
        if (signLocation != null) {
            runAtSignLocation(() -> {
                Block block = signLocation.getBlock();
                block.setType(originalBlockType != null ? originalBlockType : Material.AIR);
            });
        }
    }
    
    private void runOnPlayer(Runnable task, long delayTicks) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runLaterEntity(plugin, player, task, delayTicks);
    }
    
    private void runAtSignLocation(Runnable task) {
        if (signLocation != null) {
            dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocation(plugin, signLocation, task);
        }
    }
    
    private void runAtSignLocationDelayed(Runnable task, long delayTicks) {
        if (signLocation != null) {
            dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocationLater(plugin, signLocation, task, delayTicks);
        }
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
