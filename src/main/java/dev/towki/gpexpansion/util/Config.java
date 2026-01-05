package dev.towki.gpexpansion.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Configuration manager with dynamic default handling.
 * Automatically adds missing config options when the plugin updates,
 * so players don't need to delete their config files.
 */
public class Config {
    
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private File configFile;
    
    // Define all defaults here - when adding new options, add them here
    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();
    
    static {
        // Debug settings
        DEFAULTS.put("debug.enabled", false);
        
        // Message settings
        DEFAULTS.put("messages.show-permission-details", true);
        
        // Default limits
        DEFAULTS.put("defaults.max-sell-signs", 5);
        DEFAULTS.put("defaults.max-rent-signs", 5);
        DEFAULTS.put("defaults.max-mailbox-signs", 5);
        
        // Permission tracking
        DEFAULTS.put("permission-tracking.enabled", true);
        DEFAULTS.put("permission-tracking.check-interval", 5);
        
        // GUI settings
        DEFAULTS.put("gui.enabled", true);
        
        // Tax settings
        DEFAULTS.put("tax.percent", 5);
        DEFAULTS.put("tax.account-name", "Tax");
    }
    
    public Config(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }
    
    /**
     * Load configuration and add any missing defaults.
     */
    public void load() {
        // Create config file if it doesn't exist
        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
        }
        
        // Load the config
        config = YamlConfiguration.loadConfiguration(configFile);
        
        // Check for and add missing defaults
        boolean modified = addMissingDefaults();
        
        if (modified) {
            save();
            plugin.getLogger().info("Config updated with new default values.");
        }
    }
    
    /**
     * Add any missing default values to the config.
     * @return true if any defaults were added
     */
    private boolean addMissingDefaults() {
        boolean modified = false;
        
        // Load the default config from the jar for comments reference
        InputStream defaultStream = plugin.getResource("config.yml");
        FileConfiguration defaultConfig = null;
        if (defaultStream != null) {
            defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
        }
        
        for (Map.Entry<String, Object> entry : DEFAULTS.entrySet()) {
            String path = entry.getKey();
            Object defaultValue = entry.getValue();
            
            if (!config.contains(path)) {
                config.set(path, defaultValue);
                modified = true;
                plugin.getLogger().info("Added missing config option: " + path + " = " + defaultValue);
            }
        }
        
        return modified;
    }
    
    /**
     * Save the configuration file.
     */
    public void save() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config.yml", e);
        }
    }
    
    /**
     * Reload the configuration from disk.
     */
    public void reload() {
        load();
    }
    
    /**
     * Get the underlying FileConfiguration.
     */
    public FileConfiguration getConfig() {
        return config;
    }
    
    // Convenience getters for common options
    
    public boolean isDebugEnabled() {
        return config.getBoolean("debug.enabled", false);
    }
    
    public boolean showPermissionDetails() {
        return config.getBoolean("messages.show-permission-details", true);
    }
    
    public int getMaxSellSigns() {
        return config.getInt("defaults.max-sell-signs", 5);
    }
    
    public int getMaxRentSigns() {
        return config.getInt("defaults.max-rent-signs", 5);
    }
    
    public int getMaxMailboxSigns() {
        return config.getInt("defaults.max-mailbox-signs", 5);
    }
    
    public boolean isPermissionTrackingEnabled() {
        return config.getBoolean("permission-tracking.enabled", true);
    }
    
    public int getPermissionCheckInterval() {
        return config.getInt("permission-tracking.check-interval", 5);
    }
    
    public boolean isGUIEnabled() {
        return config.getBoolean("gui.enabled", true);
    }
    
    public int getTaxPercent() {
        return config.getInt("tax.percent", 5);
    }
    
    public String getTaxAccountName() {
        return config.getString("tax.account-name", "Tax");
    }
    
    // Generic getters
    
    public String getString(String path, String def) {
        return config.getString(path, def);
    }
    
    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }
    
    public boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }
    
    public double getDouble(String path, double def) {
        return config.getDouble(path, def);
    }
}
