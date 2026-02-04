package dev.towki.gpexpansion.config;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

/**
 * Handles configuration versioning, migrations, and update checking
 */
public class VersionManager {
    
    private final GPExpansionPlugin plugin;
    private final File dataFolder;
    private String currentConfigVersion;
    private boolean updateCheckerEnabled;
    private long lastUpdateCheck;
    
    public VersionManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
        loadConfig();
    }
    
    /**
     * Convert Adventure Component to plain text for logging (strips color codes)
     */
    private String plainText(Component component) {
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(component);
        return serialized.replaceAll("&[0-9a-fA-Fk-oK-OrR]", "");
    }
    
    /**
     * Load version settings from config
     */
    private void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        
        plugin.getLogger().info("VersionManager: Loading config...");
        
        // Check actual file content for version section
        File configFile = new File(dataFolder, "config.yml");
        boolean fileHasVersionSection = false;
        String actualVersion = null;
        
        if (configFile.exists()) {
            try {
                String configContent = Files.readString(configFile.toPath());
                fileHasVersionSection = configContent.contains("version:") && configContent.contains("config-version:");
                
                // Extract actual version from file if present
                if (fileHasVersionSection) {
                    String[] lines = configContent.split("\n");
                    for (String line : lines) {
                        if (line.trim().startsWith("config-version:")) {
                            actualVersion = line.split(":")[1].trim().replace("\"", "");
                            break;
                        }
                    }
                }
                
                plugin.getLogger().info("VersionManager: File contains version section: " + fileHasVersionSection);
                if (actualVersion != null) {
                    plugin.getLogger().info("VersionManager: Actual version in file: " + actualVersion);
                }
                
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to read config file for version detection: " + e.getMessage());
            }
        }
        
        // Use actual file content for version detection
        if (!fileHasVersionSection || actualVersion == null) {
            // Old config without version section - treat as pre-0.1.3a
            currentConfigVersion = "0.1.2";
            plugin.getLogger().info("VersionManager: No version section found in file, assuming version: " + currentConfigVersion);
        } else {
            currentConfigVersion = actualVersion;
            plugin.getLogger().info("VersionManager: Found version section in file, version: " + currentConfigVersion);
        }
        
        updateCheckerEnabled = config.getBoolean("version.update-checker.enabled", true);
        lastUpdateCheck = config.getLong("version.update-checker.last-check", 0);
    }
    
    /**
     * Check if configuration migration is needed and perform it
     */
    public void checkAndMigrateConfiguration() {
        String configVersion = currentConfigVersion;
        
        plugin.getLogger().info("VersionManager: Checking for migrations...");
        plugin.getLogger().info("VersionManager: Current version: " + configVersion);
        
        // Check actual file content for old format indicators
        File configFile = new File(dataFolder, "config.yml");
        boolean isOldFormat = false;
        
        if (configFile.exists()) {
            try {
                String configContent = Files.readString(configFile.toPath());
                plugin.getLogger().info("VersionManager: Contains 'messages:': " + configContent.contains("messages:"));
                plugin.getLogger().info("VersionManager: Contains 'show-permission-details: true': " + configContent.contains("show-permission-details: true"));
                plugin.getLogger().info("VersionManager: Contains 'eviction:': " + configContent.contains("eviction:"));
                
                // Check for old format indicators in the actual file content
                isOldFormat = configContent.contains("messages:") && 
                             configContent.contains("show-permission-details: true") &&
                             !configContent.contains("eviction:");
                
                plugin.getLogger().info("VersionManager: File content analysis - isOldFormat: " + isOldFormat);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to read config file for format detection: " + e.getMessage());
                // Fallback to config-based detection
                isOldFormat = !plugin.getConfig().contains("eviction") || 
                              !plugin.getConfig().contains("version.update-checker.enabled");
            }
        }
        
        // If version is older than 0.1.3a, migrate (don't migrate based on format alone)
        if (isVersionOlder(configVersion, "0.1.3a")) {
            plugin.getLogger().info("VersionManager: Migration needed, starting...");
            performMigrationToVersion013a(configVersion);
        } else {
            plugin.getLogger().info("VersionManager: No migration needed for 0.1.3a");
        }

        // Migrate self-mailboxes section to defaults.max-self-mailboxes-per-claim
        migrateSelfMailboxesConfig();

        // If version is older than 0.1.5a, set mailbox-protocol default from GP detection and bump version
        if (isVersionOlder(currentConfigVersion, "0.1.5a")) {
            migrateToVersion015a();
        }

        // Always ensure mailbox-protocol exists (some users may already be on 0.1.5a without this key).
        ensureMailboxProtocolPresent();
        
        // Schedule update check if enabled
        if (updateCheckerEnabled) {
            scheduleUpdateCheck();
        }
    }
    
    /**
     * Reload version manager configuration
     */
    public void reload() {
        loadConfig();
        // Re-check for migrations after reload
        checkAndMigrateConfiguration();
    }
    
    /**
     * Migrate configuration to version 0.1.3a
     */
    private void performMigrationToVersion013a(String fromVersion) {
        plugin.getLogger().info(plainText(plugin.getMessages().get("migration.console-starting"))
            .replace("{old}", fromVersion != null ? fromVersion : "unknown")
            .replace("{new}", "0.1.3a"));
        
        boolean migrationSuccess = true;
        boolean foundDeprecatedFiles = false;
        
        try {
            // Check if this looks like an old config format by reading the actual file
            boolean isOldFormat = false;
            File configFile = new File(dataFolder, "config.yml");
            
            if (configFile.exists()) {
                try {
                    String configContent = Files.readString(configFile.toPath());
                    // Check for old format indicators in the actual file content
                    isOldFormat = configContent.contains("messages:") && 
                                 configContent.contains("show-permission-details: true") &&
                                 !configContent.contains("eviction:");
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to read config file for format detection: " + e.getMessage());
                    // Fallback to config-based detection
                    isOldFormat = !plugin.getConfig().contains("eviction") || 
                                  !plugin.getConfig().contains("version.update-checker.enabled");
                }
            }
            
            // Backup and recreate main config.yml if it's outdated
            if (configFile.exists() && isOldFormat) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                File backupFile = new File(dataFolder, "config.yml.backup_" + timestamp);
                
                try {
                    Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    plugin.getLogger().info(plainText(plugin.getMessages().get("migration.console-backup-created"))
                        .replace("{backup-file}", backupFile.getName()));
                    
                    // Delete old config and let it be recreated with new format
                    configFile.delete();
                    plugin.getLogger().info(plainText(plugin.getMessages().get("migration.console-file-recreated"))
                        .replace("{file}", "config.yml"));
                    
                    // Reload config to recreate from defaults
                    plugin.reloadConfig();
                    
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to backup config.yml: " + e.getMessage());
                    migrationSuccess = false;
                }
            }
            
            File guisFolder = new File(dataFolder, "guis");
            if (guisFolder.exists()) {
                // Check for deprecated claim-options.yml
                File claimOptionsFile = new File(guisFolder, "claim-options.yml");
                if (claimOptionsFile.exists()) {
                    foundDeprecatedFiles = true;
                    plugin.getLogger().info(plainText(plugin.getMessages().get("migration.console-deprecated-file"))
                        .replace("{file}", "claim-options.yml"));
                }
                
                // Backup and recreate claim-settings.yml
                File claimSettingsFile = new File(guisFolder, "claim-settings.yml");
                if (claimSettingsFile.exists()) {
                    // Create backup
                    String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                    File backupFile = new File(guisFolder, "claim-settings.yml.backup_" + timestamp);
                    
                    try {
                        Files.copy(claimSettingsFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        plugin.getLogger().info(plainText(plugin.getMessages().get("migration.console-backup-created"))
                            .replace("{backup-file}", backupFile.getName()));
                        
                        // Delete old file and let it be recreated with new format
                        claimSettingsFile.delete();
                        plugin.getLogger().info(plainText(plugin.getMessages().get("migration.console-file-recreated"))
                            .replace("{file}", "claim-settings.yml"));
                        
                    } catch (IOException e) {
                        plugin.getLogger().warning("Failed to backup claim-settings.yml: " + e.getMessage());
                        migrationSuccess = false;
                    }
                }
            }
            
            // Update config version
            FileConfiguration config = plugin.getConfig();
            config.set("version.config-version", "0.1.3a");
            plugin.saveConfig();
            
            if (migrationSuccess) {
                plugin.getLogger().info(plainText(plugin.getMessages().get("migration.console-migration-complete")));
                
                // Notify admins in-game
                if (foundDeprecatedFiles) {
                    notifyAdminsAboutMigration();
                }
            } else {
                plugin.getLogger().severe(plainText(plugin.getMessages().get("migration.console-migration-failed"))
                    .replace("{error}", "See above logs for details"));
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe(plainText(plugin.getMessages().get("migration.console-migration-failed"))
                .replace("{error}", e.getMessage()));
        }
    }
    
    /**
     * Notify admins about completed migration
     */
    private void notifyAdminsAboutMigration() {
        Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.hasPermission("gpx.admin"))
            .forEach(player -> player.sendMessage(
                plugin.getMessages().get("migration.ingame-migration-complete")
            ));
        
        Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.hasPermission("gpx.admin"))
            .forEach(player -> player.sendMessage(
                plugin.getMessages().get("migration.ingame-deprecated-files")
            ));
    }
    
    /**
     * Migrate deprecated self-mailboxes section to defaults.max-self-mailboxes-per-claim
     */
    private void migrateSelfMailboxesConfig() {
        FileConfiguration config = plugin.getConfig();
        if (config.contains("self-mailboxes")) {
            if (config.contains("self-mailboxes.max") && !config.contains("defaults.max-self-mailboxes-per-claim")) {
                int max = config.getInt("self-mailboxes.max", 1);
                config.set("defaults.max-self-mailboxes-per-claim", max);
                plugin.getLogger().info("VersionManager: Migrated self-mailboxes.max to defaults.max-self-mailboxes-per-claim: " + max);
            }
            config.set("self-mailboxes", null);
            plugin.saveConfig();
            plugin.getLogger().info("VersionManager: Removed deprecated self-mailboxes section");
        }
    }

    /**
     * Migrate to config version 0.1.5a: set mailbox-protocol default from GP detection (first time only).
     * If GP3D is detected default to "real", otherwise "virtual". Only sets when key is missing.
     */
    private void migrateToVersion015a() {
        FileConfiguration config = plugin.getConfig();
        if (!config.contains("mailbox-protocol")) {
            GPBridge gp = new GPBridge();
            boolean gp3d = gp.isGP3D();
            String defaultProtocol = gp3d ? "real" : "virtual";
            config.set("mailbox-protocol", defaultProtocol);
            plugin.getLogger().info("VersionManager: Set mailbox-protocol to " + defaultProtocol + " (detected " + (gp3d ? "GP3D" : "regular GP") + "). You can change this in config.yml.");
        }
        config.set("version.config-version", "0.1.5a");
        plugin.saveConfig();
        currentConfigVersion = "0.1.5a";
        plugin.getLogger().info("VersionManager: Config version updated to 0.1.5a");
    }

    /**
     * Ensure mailbox-protocol exists without overwriting user choice.
     * Used for cases where config-version is already 0.1.5a but the key is missing.
     */
    private void ensureMailboxProtocolPresent() {
        FileConfiguration config = plugin.getConfig();
        if (config.contains("mailbox-protocol")) return;
        GPBridge gp = new GPBridge();
        boolean gp3d = gp.isGP3D();
        String defaultProtocol = gp3d ? "real" : "virtual";
        config.set("mailbox-protocol", defaultProtocol);
        plugin.saveConfig();
        plugin.getLogger().info("VersionManager: Added missing mailbox-protocol=" + defaultProtocol + " (detected " + (gp3d ? "GP3D" : "regular GP") + ").");
    }

    /**
     * Schedule update checking
     */
    private void scheduleUpdateCheck() {
        long checkInterval = plugin.getConfig().getLong("version.update-checker.check-interval", 24) * 60 * 60 * 1000L; // Convert hours to milliseconds
        long timeSinceLastCheck = System.currentTimeMillis() - lastUpdateCheck;
        
        if (timeSinceLastCheck >= checkInterval) {
            // Check immediately
            checkForUpdates();
        } else {
            // Schedule for later
            new BukkitRunnable() {
                @Override
                public void run() {
                    checkForUpdates();
                }
            }.runTaskLater(plugin, (checkInterval - timeSinceLastCheck) / 50); // Convert to ticks
        }
    }
    
    /**
     * Check for updates on SpigotMC
     */
    public void checkForUpdates() {
        if (!updateCheckerEnabled) return;
        
        plugin.getLogger().info(plainText(plugin.getMessages().get("update-checker.console-checking")));
        
        // Run async to avoid blocking main thread
        CompletableFuture.supplyAsync(this::fetchLatestVersion)
            .thenAcceptAsync(latestVersion -> {
                if (latestVersion != null) {
                    handleUpdateCheckResult(latestVersion);
                }
            })
            .exceptionally(throwable -> {
                plugin.getLogger().warning(plainText(plugin.getMessages().get("update-checker.console-check-failed"))
                    .replace("{error}", throwable.getMessage()));
                return null;
            });
    }
    
    /**
     * Fetch latest version from SpigotMC API
     */
    private String fetchLatestVersion() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.spigotmc.org/legacy/update.php/resource=131358"))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return response.body().trim();
            } else {
                throw new IOException("HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch version from SpigotMC", e);
        }
    }
    
    /**
     * Handle the result of update check
     */
    private void handleUpdateCheckResult(String latestVersion) {
        String currentVersion = plugin.getPluginMeta().getVersion();
        
        // Update last check time
        FileConfiguration config = plugin.getConfig();
        config.set("version.update-checker.last-check", System.currentTimeMillis());
        plugin.saveConfig();
        
        if (currentVersion.equals(latestVersion)) {
            plugin.getLogger().info(plainText(plugin.getMessages().get("update-checker.console-up-to-date")));
        } else {
            String updateMessage = plainText(plugin.getMessages().get("update-checker.console-update-available"))
                .replace("{current}", currentVersion)
                .replace("{latest}", latestVersion);
            plugin.getLogger().info(updateMessage);
            
            String downloadMessage = plainText(plugin.getMessages().get("update-checker.console-download-link"))
                .replace("{url}", "https://www.spigotmc.org/resources/gpexpansion-%E2%9C%85-folia-support.131358/");
            plugin.getLogger().info(downloadMessage);
            
            // Notify admins in-game if enabled
            if (plugin.getConfig().getBoolean("version.update-checker.notify-admins", true)) {
                notifyAdminsAboutUpdate(currentVersion, latestVersion);
            }
        }
    }
    
    /**
     * Notify admins about available update
     */
    private void notifyAdminsAboutUpdate(String currentVersion, String latestVersion) {
        String downloadUrl = "https://www.spigotmc.org/resources/gpexpansion-%E2%9C%85-folia-support.131358/";
        
        Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.hasPermission("gpx.admin"))
            .forEach(player -> {
                player.sendMessage(plugin.getMessages().get("update-checker.ingame-update-available", 
                    "{current}", currentVersion, "{latest}", latestVersion));
                player.sendMessage(plugin.getMessages().get("update-checker.ingame-download-link", 
                    "{url}", downloadUrl));
            });
    }
    
    /**
     * Get current configuration version
     */
    public String getCurrentConfigVersion() {
        return currentConfigVersion;
    }
    
    /**
     * Compare version strings (simple version comparison)
     * Returns true if version1 is older than version2
     */
    private boolean isVersionOlder(String version1, String version2) {
        if (version1 == null) return true;
        if (version1.equals(version2)) return false;
        
        // Simple version comparison - split by dots and compare numerically
        String[] v1Parts = version1.replace("a", "").split("\\.");
        String[] v2Parts = version2.replace("a", "").split("\\.");
        
        int maxLength = Math.max(v1Parts.length, v2Parts.length);
        for (int i = 0; i < maxLength; i++) {
            int v1Part = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
            int v2Part = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;
            
            if (v1Part < v2Part) return true;
            if (v1Part > v2Part) return false;
        }
        
        // If numeric parts are equal, check for 'a' suffix
        boolean v1HasA = version1.contains("a");
        boolean v2HasA = version2.contains("a");
        
        // Consider versions without 'a' as newer than versions with 'a'
        return v1HasA && !v2HasA;
    }
    
    /**
     * Check if update checker is enabled
     */
    public boolean isUpdateCheckerEnabled() {
        return updateCheckerEnabled;
    }
}
