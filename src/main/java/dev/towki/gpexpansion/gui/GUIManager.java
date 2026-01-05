package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages all GUI instances and configurations.
 */
public class GUIManager implements Listener {
    
    private final GPExpansionPlugin plugin;
    private final Map<UUID, BaseGUI> openGUIs = new HashMap<>();
    private final Map<String, FileConfiguration> guiConfigs = new HashMap<>();
    private boolean guiEnabled = true;
    
    public GUIManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        loadConfigs();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    
    public void loadConfigs() {
        guiEnabled = plugin.getConfig().getBoolean("gui.enabled", true);
        
        // Ensure guis folder exists
        File guisFolder = new File(plugin.getDataFolder(), "guis");
        if (!guisFolder.exists()) {
            guisFolder.mkdirs();
        }
        
        // Load all GUI config files
        String[] guiFiles = {
            "main-menu.yml",
            "owned-claims.yml",
            "trusted-claims.yml",
            "claim-options.yml",
            "children-claims.yml",
            "claim-settings.yml",
            "setup-wizards.yml",
            "admin-menu.yml",
            "admin-claims.yml",
            "all-player-claims.yml",
            "claim-flags.yml"
        };
        
        for (String fileName : guiFiles) {
            loadOrCreateConfig(guisFolder, fileName);
        }
    }
    
    private void loadOrCreateConfig(File folder, String fileName) {
        File file = new File(folder, fileName);
        if (!file.exists()) {
            // Try to save from resources
            try (InputStream in = plugin.getResource("guis/" + fileName)) {
                if (in != null) {
                    plugin.saveResource("guis/" + fileName, false);
                } else {
                    // Create empty file
                    file.createNewFile();
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create GUI config: " + fileName);
            }
        }
        
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        // Load defaults from jar if available
        try (InputStream defStream = plugin.getResource("guis/" + fileName)) {
            if (defStream != null) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
                config.setDefaults(defConfig);
            }
        } catch (IOException e) {
            // Ignore
        }
        
        String key = fileName.replace(".yml", "");
        guiConfigs.put(key, config);
    }
    
    public void reload() {
        guiConfigs.clear();
        loadConfigs();
    }
    
    public boolean isGUIEnabled() {
        return guiEnabled;
    }
    
    public FileConfiguration getGUIConfig(String name) {
        return guiConfigs.get(name);
    }
    
    public GPExpansionPlugin getPlugin() {
        return plugin;
    }
    
    public void openGUI(Player player, BaseGUI gui) {
        // Close any existing GUI first
        closeGUI(player);
        
        Inventory inv = gui.createInventory();
        openGUIs.put(player.getUniqueId(), gui);
        player.openInventory(inv);
    }
    
    public void closeGUI(Player player) {
        BaseGUI gui = openGUIs.remove(player.getUniqueId());
        if (gui != null) {
            gui.onClose(player);
        }
    }
    
    public BaseGUI getOpenGUI(Player player) {
        return openGUIs.get(player.getUniqueId());
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        
        BaseGUI gui = openGUIs.get(player.getUniqueId());
        if (gui == null) return;
        
        // Check if the click is in our GUI
        if (!event.getInventory().equals(gui.getInventory())) return;
        
        event.setCancelled(true);
        
        // Don't process clicks outside the GUI area
        if (event.getRawSlot() < 0 || event.getRawSlot() >= gui.getInventory().getSize()) return;
        
        gui.handleClick(event);
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        
        BaseGUI gui = openGUIs.get(player.getUniqueId());
        if (gui == null) return;
        
        if (event.getInventory().equals(gui.getInventory())) {
            event.setCancelled(true);
        }
    }
    
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        
        BaseGUI gui = openGUIs.get(player.getUniqueId());
        if (gui != null && event.getInventory().equals(gui.getInventory())) {
            openGUIs.remove(player.getUniqueId());
            gui.onClose(player);
        }
    }
    
    // Open main menu for player
    public void openMainMenu(Player player) {
        openGUI(player, new MainMenuGUI(this, player));
    }
    
    // Open owned claims menu
    public void openOwnedClaims(Player player) {
        openGUI(player, new OwnedClaimsGUI(this, player));
    }
    
    // Open trusted claims menu
    public void openTrustedClaims(Player player) {
        openGUI(player, new TrustedClaimsGUI(this, player));
    }
    
    // Open claim options menu (hopper style)
    public void openClaimOptions(Player player, Object claim, String claimId) {
        openGUI(player, new ClaimOptionsGUI(this, player, claim, claimId));
    }
    
    // Open children claims menu
    public void openChildrenClaims(Player player, Object parentClaim, String parentClaimId) {
        openGUI(player, new ChildrenClaimsGUI(this, player, parentClaim, parentClaimId));
    }
    
    // Open claim settings menu
    public void openClaimSettings(Player player, Object claim, String claimId) {
        openGUI(player, new ClaimSettingsGUI(this, player, claim, claimId));
    }
    
    // Open setup wizards menu
    public void openSetupWizards(Player player, Object claim, String claimId) {
        openGUI(player, new SetupWizardsGUI(this, player, claim, claimId));
    }
    
    // Open global claim list
    public void openGlobalClaimList(Player player) {
        openGUI(player, new GlobalClaimListGUI(this, player));
    }
    
    // Open banned players menu
    public void openBannedPlayers(Player player, Object claim, String claimId) {
        openGUI(player, new BannedPlayersGUI(this, player, claim, claimId));
    }
    
    // Open admin menu
    public void openAdminMenu(Player player) {
        openGUI(player, new AdminMenuGUI(this, player));
    }
    
    // Open admin claims menu
    public void openAdminClaims(Player player) {
        openGUI(player, new AdminClaimsGUI(this, player));
    }
    
    // Open all player claims menu (admin view)
    public void openAllPlayerClaims(Player player) {
        openGUI(player, new AllPlayerClaimsGUI(this, player));
    }
    
    // Open claim flags menu (GPFlags integration)
    public void openClaimFlags(Player player, Object claim, String claimId) {
        openGUI(player, new ClaimFlagsGUI(this, player, claim, claimId));
    }
}
