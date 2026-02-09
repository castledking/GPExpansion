package dev.towki.gpexpansion.config;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.scheduler.SchedulerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

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
        File configFileForFormat = new File(dataFolder, "config.yml");
        boolean isOldFormat = false;
        
        if (configFileForFormat.exists()) {
            try {
                String configContent = Files.readString(configFileForFormat.toPath());
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
        
        // Migrate to 0.1.8a: add player-commands section
        // Always check if section exists by reading file directly (more reliable than config.contains)
        File configFileCheck = new File(dataFolder, "config.yml");
        boolean playerCommandsExists = false;
        
        if (configFileCheck.exists()) {
            try {
                String configContent = Files.readString(configFileCheck.toPath());
                // Check for player-commands section in file
                playerCommandsExists = configContent.contains("player-commands:") || 
                                      configContent.contains("player-commands");
                plugin.getLogger().info("VersionManager: Checked file for player-commands: " + playerCommandsExists);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to read config file to check for player-commands: " + e.getMessage());
                // Fallback to in-memory config check
                playerCommandsExists = plugin.getConfig().contains("player-commands");
            }
        }
        
        // Run migration if version is old OR if section is missing (even if version is already 0.1.8a)
        if (isVersionOlder(currentConfigVersion, "0.1.8a") || !playerCommandsExists) {
            if (!playerCommandsExists) {
                plugin.getLogger().info("VersionManager: player-commands section missing" + 
                    (isVersionOlder(currentConfigVersion, "0.1.8a") ? " (version " + currentConfigVersion + " < 0.1.8a)" : " (version " + currentConfigVersion + " but migration not applied)") + 
                    ", applying 0.1.8a migration");
            }
            migrateToVersion018a();
            // Reload config after migration to get updated version
            loadConfig();
        }
        
        // Migrate to 0.1.9a: replace eviction section with canonical format
        if (isVersionOlder(currentConfigVersion, "0.1.9a")) {
            migrateToVersion019a();
            loadConfig();
        }

        // If file still has old eviction format (notice-period-days), replace with canonical block and new comments
        File configFileEviction = new File(dataFolder, "config.yml");
        if (configFileEviction.exists()) {
            try {
                String content = Files.readString(configFileEviction.toPath());
                if (content.contains("notice-period-days")) {
                    plugin.getLogger().info("VersionManager: Config file contains notice-period-days, migrating to notice-period format");
                    migrateToVersion019a();
                    loadConfig();
                }
            } catch (IOException e) {
                plugin.getLogger().warning("VersionManager: Could not check config for notice-period-days: " + e.getMessage());
            }
        }

        // Ensure eviction section is present and valid (for 0.1.9a+ with empty/corrupted eviction)
        ensureEvictionSectionPresent();

        // Auto-bump version if no migrations needed and version is older than project version
        // Only auto-bump if player-commands section exists (to avoid bumping before migration)
        String projectVersion = getProjectVersion();
        if (isVersionOlder(currentConfigVersion, projectVersion)) {
            // Double-check section exists before auto-bumping
            boolean sectionExists = false;
            if (configFileCheck.exists()) {
                try {
                    String configContent = Files.readString(configFileCheck.toPath());
                    sectionExists = configContent.contains("player-commands:");
                } catch (IOException e) {
                    sectionExists = plugin.getConfig().contains("player-commands");
                }
            }
            
            if (sectionExists) {
                autoBumpConfigVersion(projectVersion);
            } else {
                plugin.getLogger().warning("VersionManager: Skipping auto-bump - player-commands section still missing after migration check.");
            }
        }
        
        // Schedule update check if enabled
        if (updateCheckerEnabled) {
            scheduleUpdateCheck();
        }
    }
    
    /**
     * Get the project version from plugin.yml
     * Falls back to reading from pom.properties or hardcoded version
     */
    private String getProjectVersion() {
        String version = plugin.getDescription().getVersion();
        // Remove any ${project.version} placeholders if present
        if (version.contains("${") || version.isEmpty()) {
            // Try to read from Maven properties file
            try {
                java.io.InputStream is = plugin.getClass().getClassLoader().getResourceAsStream("META-INF/maven/dev.towki.gpexpansion/gpexpansion/pom.properties");
                if (is != null) {
                    java.util.Properties props = new java.util.Properties();
                    props.load(is);
                    version = props.getProperty("version", "0.1.8a");
                    is.close();
                } else {
                    version = "0.1.8a"; // Fallback to current version
                }
            } catch (Exception e) {
                version = "0.1.8a"; // Fallback to current version
            }
        }
        return version;
    }
    
    /**
     * Auto-bump config version to project version (when no migrations are needed)
     */
    private void autoBumpConfigVersion(String targetVersion) {
        FileConfiguration config = plugin.getConfig();
        String currentVersion = config.getString("version.config-version", currentConfigVersion);
        
        if (isVersionOlder(currentVersion, targetVersion)) {
            config.set("version.config-version", targetVersion);
            plugin.saveConfig();
            currentConfigVersion = targetVersion;
            plugin.getLogger().info("VersionManager: Auto-bumped config version from " + currentVersion + " to " + targetVersion + " (no migrations needed)");
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
     * Migrate to config version 0.1.8a: add player-commands section
     * This method writes directly to the file to avoid issues with config defaults
     */
    private void migrateToVersion018a() {
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            plugin.getLogger().warning("VersionManager: config.yml does not exist, cannot migrate");
            return;
        }
        
        try {
            String configContent = Files.readString(configFile.toPath());
            
            // Check if player-commands already exists in the actual file (not just defaults)
            if (configContent.contains("player-commands:")) {
                plugin.getLogger().info("VersionManager: player-commands section already exists in file, skipping addition");
                // Still update version if needed
                FileConfiguration config = plugin.getConfig();
                String currentVersion = config.getString("version.config-version", currentConfigVersion);
                if (isVersionOlder(currentVersion, "0.1.8a")) {
                    config.set("version.config-version", "0.1.8a");
                    plugin.saveConfig();
                    currentConfigVersion = "0.1.8a";
                }
                return;
            }
            
            // Build the player-commands section
            StringBuilder playerCommandsSection = new StringBuilder();
            playerCommandsSection.append("\n# Player command permissions (dynamically assigned to gpx.player permission)\n");
            playerCommandsSection.append("# A list of permissions starting from griefprevention.<permission> that the gpx.player permission should have\n");
            playerCommandsSection.append("# Permissions are dynamically updated on /gpx reload\n");
            playerCommandsSection.append("player-commands:\n");
            playerCommandsSection.append("#  - restoresnapshot\n");
            playerCommandsSection.append("  - claims\n");
            playerCommandsSection.append("  - claim.name\n");
            playerCommandsSection.append("  - claim.name.anywhere\n");
            playerCommandsSection.append("  - claim.description\n");
            playerCommandsSection.append("  - claim.description.anywhere\n");
            playerCommandsSection.append("  - claim.icon\n");
            playerCommandsSection.append("  - claim.ban\n");
            playerCommandsSection.append("  - claim.ban.anywhere\n");
            playerCommandsSection.append("  - claim.unban\n");
            playerCommandsSection.append("  - claim.unban.anywhere\n");
            playerCommandsSection.append("  - claim.banlist\n");
            playerCommandsSection.append("  - claim.banlist.anywhere\n");
            playerCommandsSection.append("  - sign.create.rent\n");
            playerCommandsSection.append("  - sign.use.rent\n");
            playerCommandsSection.append("  - sign.rent.money\n");
            playerCommandsSection.append("  - sign.create.sell\n");
            playerCommandsSection.append("  - sign.use.sell\n");
            playerCommandsSection.append("  - sign.sell.money\n");
            playerCommandsSection.append("  - sign.create.buy\n");
            playerCommandsSection.append("  - sign.use.buy\n");
            playerCommandsSection.append("  - sign.create.mailbox\n");
            playerCommandsSection.append("  - sign.use.mailbox\n");
            playerCommandsSection.append("  - sign.mailbox.money\n");
            playerCommandsSection.append("  - sign.create.self-mailbox\n");
            playerCommandsSection.append("  - claiminfo\n");
            playerCommandsSection.append("#  - claim.teleport\n");
            playerCommandsSection.append("  - claim.setspawn\n");
            playerCommandsSection.append("  - claim.gui.setclaimflag.own\n");
            playerCommandsSection.append("  - claim.gui.globallist\n");
            playerCommandsSection.append("  - claim.toggleglobal\n");
            playerCommandsSection.append("  - claim.toggleglobal.anywhere\n");
            playerCommandsSection.append("  - claim.toggleglobal.5\n");
            playerCommandsSection.append("  - claim.color.black\n");
            playerCommandsSection.append("  - claim.color.dark_blue\n");
            playerCommandsSection.append("  - claim.color.dark_green\n");
            playerCommandsSection.append("  - claim.color.dark_aqua\n");
            playerCommandsSection.append("  - claim.color.dark_red\n");
            playerCommandsSection.append("  - claim.color.dark_purple\n");
            playerCommandsSection.append("  - claim.color.gold\n");
            playerCommandsSection.append("  - claim.color.gray\n");
            playerCommandsSection.append("  - claim.color.dark_gray\n");
            playerCommandsSection.append("  - claim.color.blue\n");
            playerCommandsSection.append("  - claim.color.green\n");
            playerCommandsSection.append("  - claim.color.aqua\n");
            playerCommandsSection.append("  - claim.color.red\n");
            playerCommandsSection.append("  - claim.color.light_purple\n");
            playerCommandsSection.append("  - claim.color.yellow\n");
            playerCommandsSection.append("  - claim.color.white\n");
            playerCommandsSection.append("  - claim.format.bold\n");
            playerCommandsSection.append("  - claim.format.italic\n");
            playerCommandsSection.append("  - claim.format.reset\n");
            
            // Find insertion point: after defaults section, before permission-tracking
            String[] lines = configContent.split("\\R", -1);
            java.util.List<String> newLines = new java.util.ArrayList<>();
            int insertAt = -1;
            
            // Find where to insert (after defaults section)
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();
                
                // Look for the end of defaults section (empty line after max-self-mailboxes-per-claim)
                if (trimmed.equals("max-self-mailboxes-per-claim:") || trimmed.startsWith("max-self-mailboxes-per-claim:")) {
                    // Check next few lines for empty line or next section
                    for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                        if (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#") || 
                            lines[j].trim().startsWith("permission-tracking:")) {
                            insertAt = j;
                            break;
                        }
                    }
                    if (insertAt == -1) insertAt = i + 2; // Default to 2 lines after
                    break;
                }
            }
            
            // Fallback: insert before permission-tracking section
            if (insertAt == -1) {
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].trim().startsWith("permission-tracking:") || 
                        lines[i].trim().startsWith("# Permission tracking")) {
                        insertAt = i;
                        break;
                    }
                }
            }
            
            // Final fallback: insert before version section
            if (insertAt == -1) {
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].trim().startsWith("version:") || 
                        lines[i].trim().startsWith("# Version tracking")) {
                        insertAt = i;
                        break;
                    }
                }
            }
            
            // If still not found, append before version section or at end
            if (insertAt == -1) {
                insertAt = lines.length;
            }
            
            // Build new file content
            for (int i = 0; i < insertAt; i++) {
                newLines.add(lines[i]);
            }
            
            // Ensure there's a blank line before insertion
            if (!newLines.isEmpty() && !newLines.get(newLines.size() - 1).trim().isEmpty()) {
                newLines.add("");
            }
            
            // Add player-commands section
            String[] sectionLines = playerCommandsSection.toString().split("\\R", -1);
            for (String sectionLine : sectionLines) {
                newLines.add(sectionLine);
            }
            
            // Add remaining lines
            for (int i = insertAt; i < lines.length; i++) {
                newLines.add(lines[i]);
            }
            
            // Update version in the content
            String newContent = String.join(System.lineSeparator(), newLines);
            // Update config-version if needed
            newContent = newContent.replaceAll(
                "(?m)^(\\s*config-version:\\s*)\"[^\"]*\"",
                "$1\"0.1.8a\""
            );
            // If config-version line doesn't exist, add it
            if (!newContent.contains("config-version:")) {
                newContent = newContent.replaceFirst(
                    "(?m)^(\\s*version:)",
                    "$1\n  config-version: \"0.1.8a\""
                );
            }
            
            // Write to file
            Files.writeString(configFile.toPath(), newContent);
            currentConfigVersion = "0.1.8a";
            plugin.getLogger().info("VersionManager: Successfully migrated config to version 0.1.8a - added player-commands section with 50 permissions");
            
            // Verify the save worked
            String verifyContent = Files.readString(configFile.toPath());
            if (verifyContent.contains("player-commands:")) {
                plugin.getLogger().info("VersionManager: Verified player-commands section was saved to file");
            } else {
                plugin.getLogger().warning("VersionManager: WARNING - player-commands section not found in file after save!");
            }
            
            // Reload plugin's config manager to pick up the changes
            // This ensures plugin.getConfig() returns the updated values
            try {
                plugin.reloadConfig();
            } catch (Exception e) {
                plugin.getLogger().warning("VersionManager: Could not reload plugin config after migration: " + e.getMessage());
            }
            
        } catch (IOException e) {
            plugin.getLogger().severe("VersionManager: Failed to migrate config to 0.1.8a: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Canonical eviction section for 0.1.9a (matches config.yml in resources).
     * Placeholder {value} is replaced with the notice-period value (e.g. "14d").
     */
    private static final String EVICTION_SECTION_019A =
        "# Eviction settings\n" +
        "eviction:\n" +
        "  # Time before a renter can be evicted after notice is given.\n" +
        "  # Supports: 14d, 1d, 24h, 1h, 30m, 10s, 1w (days/hours/minutes/seconds/weeks).\n" +
        "  # Plain number (e.g. 14) is treated as days.\n" +
        "  notice-period: {value}";

    /**
     * Migrate to config version 0.1.9a: replace entire eviction section with canonical format.
     * - If notice-period exists and is valid, preserves it.
     * - If only notice-period-days exists, converts (e.g. 14 -> "14d").
     * - If eviction is missing/empty, adds section with default "14d".
     * File-based replacement like 0.1.8a for robustness.
     */
    private void migrateToVersion019a() {
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            plugin.getLogger().warning("VersionManager: config.yml does not exist, cannot migrate");
            return;
        }

        try {
            FileConfiguration config = plugin.getConfig();
            String noticePeriodValue = resolveNoticePeriodValue(config);

            String configContent = Files.readString(configFile.toPath());
            String newEvictionBlock = EVICTION_SECTION_019A.replace("{value}", noticePeriodValue);

            // Find eviction section: from "# Eviction settings" or "eviction:" to next top-level section
            String[] lines = configContent.split("\\R", -1);
            int startIdx = -1;
            int endIdx = -1;

            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (startIdx < 0 && (trimmed.equals("# Eviction settings") || trimmed.startsWith("eviction:"))) {
                    startIdx = i;
                }
                if (startIdx >= 0 && endIdx < 0 && i > startIdx) {
                    // End when we hit a top-level section (no leading spaces) that's not part of eviction
                    if (lines[i].matches("^[a-zA-Z#].*") && !lines[i].startsWith("  ")) {
                        endIdx = i;
                        break;
                    }
                }
            }
            if (startIdx >= 0 && endIdx < 0) endIdx = lines.length;

            String newContent;
            if (startIdx >= 0) {
                // Replace existing eviction block
                java.util.List<String> newLines = new java.util.ArrayList<>();
                for (int i = 0; i < startIdx; i++) newLines.add(lines[i]);
                for (String line : newEvictionBlock.split("\\R", -1)) newLines.add(line);
                for (int i = endIdx; i < lines.length; i++) newLines.add(lines[i]);
                newContent = String.join(System.lineSeparator(), newLines);
                plugin.getLogger().info("VersionManager: Replaced eviction section with canonical 0.1.9a format (notice-period: " + noticePeriodValue + ")");
            } else {
                // Eviction section missing: insert before version section
                int insertAt = -1;
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].trim().startsWith("version:") || lines[i].trim().startsWith("# Version tracking")) {
                        insertAt = i;
                        break;
                    }
                }
                if (insertAt < 0) insertAt = lines.length;

                java.util.List<String> newLines = new java.util.ArrayList<>();
                for (int i = 0; i < insertAt; i++) newLines.add(lines[i]);
                if (!newLines.isEmpty() && !newLines.get(newLines.size() - 1).trim().isEmpty()) newLines.add("");
                for (String line : newEvictionBlock.split("\\R", -1)) newLines.add(line);
                newLines.add("");
                for (int i = insertAt; i < lines.length; i++) newLines.add(lines[i]);
                newContent = String.join(System.lineSeparator(), newLines);
                plugin.getLogger().info("VersionManager: Added eviction section (was missing) with notice-period: " + noticePeriodValue);
            }

            // Update config-version
            newContent = newContent.replaceAll(
                "(?m)^(\\s*config-version:\\s*)\"[^\"]*\"",
                "$1\"0.1.9a\""
            );

            Files.writeString(configFile.toPath(), newContent);
            currentConfigVersion = "0.1.9a";
            plugin.getLogger().info("VersionManager: Config version updated to 0.1.9a");

            plugin.reloadConfig();
        } catch (IOException e) {
            plugin.getLogger().severe("VersionManager: Failed to migrate config to 0.1.9a: " + e.getMessage());
        }
    }

    /** Resolve notice-period value: from notice-period, notice-period-days, or default "14d". */
    private String resolveNoticePeriodValue(FileConfiguration config) {
        if (config.contains("eviction.notice-period")) {
            Object val = config.get("eviction.notice-period");
            if (val != null) {
                String s = String.valueOf(val).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
            }
        }
        if (config.contains("eviction.notice-period-days")) {
            int days = config.getInt("eviction.notice-period-days", 14);
            return days + "d";
        }
        return "14d";
    }

    /**
     * Ensure eviction section has notice-period. Runs after migrations.
     * If eviction.notice-period is missing or empty, or file still has notice-period-days, replaces eviction section via file.
     */
    private void ensureEvictionSectionPresent() {
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) return;
        FileConfiguration config = plugin.getConfig();
        if (!config.contains("eviction")) return;

        // If file still contains old key, always replace block (getConfig() may merge defaults and hide missing notice-period)
        boolean fileHasOldFormat = false;
        try {
            fileHasOldFormat = Files.readString(configFile.toPath()).contains("notice-period-days");
        } catch (IOException ignored) {
        }
        if (!fileHasOldFormat) {
            Object val = config.get("eviction.notice-period");
            if (val != null) {
                String s = String.valueOf(val).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return; // Already valid
            }
        }

        try {
            String noticePeriodValue = config.contains("eviction.notice-period-days")
                ? (config.getInt("eviction.notice-period-days", 14) + "d")
                : "14d";
            String configContent = Files.readString(configFile.toPath());
            String newBlock = EVICTION_SECTION_019A.replace("{value}", noticePeriodValue);
            String[] lines = configContent.split("\\R", -1);
            int startIdx = -1, endIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                if (startIdx < 0 && (t.equals("# Eviction settings") || t.startsWith("eviction:"))) startIdx = i;
                if (startIdx >= 0 && i > startIdx && lines[i].matches("^[a-zA-Z#].*") && !lines[i].startsWith("  ")) {
                    endIdx = i;
                    break;
                }
            }
            if (startIdx >= 0 && endIdx < 0) endIdx = lines.length;
            if (startIdx < 0) return;

            java.util.List<String> newLines = new java.util.ArrayList<>();
            for (int i = 0; i < startIdx; i++) newLines.add(lines[i]);
            for (String line : newBlock.split("\\R", -1)) newLines.add(line);
            for (int i = endIdx; i < lines.length; i++) newLines.add(lines[i]);
            Files.writeString(configFile.toPath(), String.join(System.lineSeparator(), newLines));
            plugin.reloadConfig();
            plugin.getLogger().info("VersionManager: Repaired empty/corrupted eviction section (notice-period: " + noticePeriodValue + ")");
        } catch (IOException e) {
            plugin.getLogger().warning("VersionManager: Could not repair eviction section: " + e.getMessage());
        }
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
            // Schedule for later (Folia-safe via SchedulerAdapter)
            long delayTicks = Math.max(1L, (checkInterval - timeSinceLastCheck) / 50L);
            SchedulerAdapter.runLaterGlobal(plugin, this::checkForUpdates, delayTicks);
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
                .uri(URI.create("https://api.spigotmc.org/legacy/update.php?resource=131358"))
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
