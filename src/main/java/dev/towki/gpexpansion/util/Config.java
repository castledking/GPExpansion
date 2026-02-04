package dev.towki.gpexpansion.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import dev.towki.gpexpansion.gp.GPBridge;

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
        DEFAULTS.put("defaults.max-self-mailboxes-per-claim", 1);
        
        // mailbox-protocol is not in DEFAULTS: set by VersionManager migration from GP detection (GP3D -> real, else virtual)
        
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

        // Ensure mailbox-protocol block (with comments) is placed correctly in the file.
        // Do this AFTER any save() above, because Bukkit's YAML writer doesn't preserve ordering/comments.
        // Startup-only: reload() is read-only and won't call this.
        if (ensureMailboxProtocolBlockInFile()) {
            // Reload config from disk so in-memory values reflect the patched file
            config = YamlConfiguration.loadConfiguration(configFile);
        }
    }

    /**
     * Ensure `mailbox-protocol` exists and is located near the top of the config with its comment block.
     * If it's missing, we insert it with a GP-aware default (GP3D -> real, otherwise virtual).
     * If it's present elsewhere (e.g. appended at bottom), we move it to the intended spot.
     *
     * Returns true if the file was modified.
     */
    private boolean ensureMailboxProtocolBlockInFile() {
        try {
            String content = Files.readString(configFile.toPath());

            // Determine desired value: preserve existing, otherwise default from GP
            String existing = config.getString("mailbox-protocol", null);
            if (existing != null) existing = existing.trim();
            if (existing == null || existing.isEmpty()) {
                boolean gp3d = false;
                try { gp3d = new GPBridge().isGP3D(); } catch (Throwable ignored) {}
                existing = gp3d ? "real" : "virtual";
            }

            String[] lines = content.split("\\R", -1);
            java.util.List<String> out = new java.util.ArrayList<>(lines.length + 8);

            // Remove any existing mailbox-protocol line, capturing value if present.
            // Also remove an immediately-adjacent comment block that looks like our mailbox section.
            String foundValue = null;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();
                if (trimmed.startsWith("mailbox-protocol:")) {
                    String v = trimmed.substring("mailbox-protocol:".length()).trim();
                    if (!v.isEmpty()) foundValue = v;

                    // Drop preceding mailbox-protocol comment block if present in output tail
                    while (!out.isEmpty()) {
                        String prev = out.get(out.size() - 1).trim();
                        if (prev.startsWith("#") &&
                            (prev.toLowerCase(Locale.ROOT).contains("mailbox protocol")
                                || prev.toLowerCase(Locale.ROOT).startsWith("# real =")
                                || prev.toLowerCase(Locale.ROOT).startsWith("# virtual ="))) {
                            out.remove(out.size() - 1);
                            // also remove a single blank line above comments if any
                            if (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) {
                                out.remove(out.size() - 1);
                            }
                            continue;
                        }
                        break;
                    }
                    // Skip this mailbox-protocol line
                    continue;
                }
                out.add(line);
            }

            String valueToWrite = (foundValue != null && !foundValue.isEmpty()) ? foundValue : existing;

            // If mailbox-protocol already exists in the correct spot with comments, no-op (best-effort).
            // We'll always (re)insert our canonical block once.
            java.util.List<String> block = java.util.List.of(
                "# Mailbox protocol (first-time default is set from GP: GP3D -> real, regular GP -> virtual; change below as desired):",
                "# real = create subdivision + container trust public (non-owner opens real chest with dynamic updates)",
                "# virtual = no subdivision, virtual view, non-updating (useful for non-GP3D users who don't want a 1x1 2D subdivision)",
                "mailbox-protocol: " + valueToWrite,
                ""
            );

            // Insert before the "defaults:" section header if present; otherwise insert near top (after messages: section).
            int insertAt = -1;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).trim().equalsIgnoreCase("defaults:")) { insertAt = i; break; }
            }
            if (insertAt == -1) {
                // fallback: before the "# Default limits" comment
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i).toLowerCase(Locale.ROOT).contains("default limits for sign creation")) { insertAt = i; break; }
                }
            }
            if (insertAt == -1) insertAt = Math.min(12, out.size()); // very early fallback

            // If there is already a blank line at insert point, keep one.
            java.util.List<String> finalLines = new java.util.ArrayList<>(out.size() + block.size());
            finalLines.addAll(out.subList(0, insertAt));
            if (!finalLines.isEmpty() && !finalLines.get(finalLines.size() - 1).trim().isEmpty()) {
                finalLines.add("");
            }
            finalLines.addAll(block);
            finalLines.addAll(out.subList(insertAt, out.size()));

            String newContent = String.join(System.lineSeparator(), finalLines);
            if (newContent.equals(content)) return false;

            Files.writeString(configFile.toPath(), newContent);
            plugin.getLogger().info("Config updated: placed mailbox-protocol near top with comments.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to place mailbox-protocol block in config.yml: " + e.getMessage());
            return false;
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

        // mailbox-protocol is handled via ensureMailboxProtocolBlockInFile() so it is inserted with comments/ordering preserved.
        
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
        // Read-only reload: do NOT inject new keys on /gpx reload.
        // Key injection/migrations should happen on startup load or explicit migration steps.
        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
        }
        config = YamlConfiguration.loadConfiguration(configFile);
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
    
    public int getMaxSelfMailboxesPerClaim() {
        return config.getInt("defaults.max-self-mailboxes-per-claim", 1);
    }
    
    /** "real" = create subdivision + container trust public, owner opens real container; "virtual" = no subdivision, virtual view only */
    public String getMailboxProtocol() {
        return config.getString("mailbox-protocol", "virtual").trim().toLowerCase(Locale.ROOT);
    }
    
    public boolean isMailboxProtocolReal() {
        return "real".equals(getMailboxProtocol());
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
