package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
            "claim-resize.yml",
            "claim-map-editor.yml",
            "claim-trusted-players.yml",
            "claim-trust-editor.yml",
            "banned-players.yml",
            "children-claims.yml",
            "claim-settings.yml",
            "setup-wizards.yml",
            "admin-menu.yml",
            "admin-claims.yml",
            "all-player-claims.yml",
            "claim-flags.yml",
            "global-claim-list.yml",
            "global-claim-settings.yml",
            "icon-selection.yml"
        };
        
        for (String fileName : guiFiles) {
            loadOrCreateConfig(guisFolder, fileName);
        }
    }
    
    private void loadOrCreateConfig(File folder, String fileName) {
        File file = new File(folder, fileName);
        boolean createdFile = false;
        if (!file.exists()) {
            // Try to save from resources
            try (InputStream in = plugin.getResource("guis/" + fileName)) {
                if (in != null) {
                    plugin.saveResource("guis/" + fileName, false);
                    createdFile = true;
                } else {
                    // Create empty file
                    file.createNewFile();
                    createdFile = true;
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
                boolean needsSave = createdFile;
                if (!needsSave) {
                    for (String key : defConfig.getKeys(true)) {
                        if (!config.contains(key)) {
                            needsSave = true;
                            break;
                        }
                    }
                }
                config.setDefaults(defConfig);
                config.options().copyDefaults(true);
                if (needsSave) {
                    config.save(file);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to merge GUI defaults for " + fileName + ": " + e.getMessage());
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
        OwnedClaimsGUI.openAsync(this, player, null);
    }
    
    // Open trusted claims menu
    public void openTrustedClaims(Player player) {
        openGUI(player, new TrustedClaimsGUI(this, player));
    }
    
    // Open claim options menu (hopper style)
    public void openClaimOptions(Player player, Object claim, String claimId) {
        openGUI(player, new ClaimOptionsGUI(this, player, claim, claimId));
    }

    public void openClaimOptions(Player player, Object claim, String claimId, boolean armAbandonConfirm) {
        openGUI(player, new ClaimOptionsGUI(this, player, claim, claimId, armAbandonConfirm));
    }

    // Open claim resize controls
    public void openClaimResize(Player player, Object claim, String claimId) {
        if (!canOpenClaimEditGUI(player, "/resizeclaim", "Resize GUI")) {
            return;
        }
        openGUI(player, new ClaimResizeGUI(this, player, claim, claimId));
    }

    // Open map-based claim editor
    public void openClaimMapEditor(Player player, Object claim, String claimId) {
        if (!canOpenClaimEditGUI(player, "/claimmap", "Claim Map Editor")) {
            return;
        }
        openGUI(player, new ClaimMapEditorGUI(this, player, claim, claimId));
    }

    // Open claim trusted players menu
    public void openClaimTrustedPlayers(Player player, Object claim, String claimId) {
        openGUI(player, new ClaimTrustedPlayersGUI(this, player, claim, claimId));
    }

    // Open trust editor for a single player
    public void openClaimTrustEditor(Player player, Object claim, String claimId, String targetName) {
        openGUI(player, new ClaimTrustEditorGUI(this, player, claim, claimId, targetName));
    }

    public void openClaimTrustEditor(Player player, Object claim, String claimId, String targetName, java.util.UUID targetId) {
        openGUI(player, new ClaimTrustEditorGUI(this, player, claim, claimId, targetName, targetId));
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
        GlobalClaimListGUI.openAsync(this, player, null);
    }

    // Open banned players menu for a claim
    public void openBannedPlayers(Player player, Object claim, String claimId) {
        BannedPlayersGUI.openAsync(this, player, claim, claimId);
    }
    
    // Open admin menu
    public void openAdminMenu(Player player) {
        openGUI(player, new AdminMenuGUI(this, player));
    }
    
    // Open admin claims menu
    public void openAdminClaims(Player player) {
        AdminClaimsGUI.openAsync(this, player, null);
    }
    
    // Open all player claims menu (admin view)
    public void openAllPlayerClaims(Player player) {
        AllPlayerClaimsGUI.openAsync(this, player, null);
    }
    
    // Open claim flags menu (GPFlags integration)
    public void openClaimFlags(Player player, Object claim, String claimId) {
        openGUI(player, new ClaimFlagsGUI(this, player, claim, claimId));
    }
    
    // Open global claim settings menu (consolidated)
    public void openGlobalClaimSettings(Player player, Object claim, String claimId) {
        openGUI(player, new GlobalClaimSettingsGUI(this, player, claim, claimId));
    }
    
    // Open global claim settings menu with fromSign flag
    public void openGlobalClaimSettings(Player player, Object claim, String claimId, boolean fromSign) {
        openGUI(player, new GlobalClaimSettingsGUI(this, player, claim, claimId, fromSign));
    }

    private boolean canOpenClaimEditGUI(Player player, String retryCommand, String guiName) {
        if (player.hasPermission("griefprevention.extendclaim.toolbypass")) {
            return true;
        }
        Material held = player.getInventory().getItemInMainHand().getType();
        if (held == Material.GOLDEN_SHOVEL) {
            return true;
        }
        player.sendMessage(ChatColor.RED + "Try doing " + retryCommand + " again while holding golden shovel to access the " + guiName + ".");
        return false;
    }
}
