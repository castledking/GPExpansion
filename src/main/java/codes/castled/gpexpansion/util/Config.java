package codes.castled.gpexpansion.util;

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

        // Claim block accrual settings
        DEFAULTS.put("accruals.enabled", true);
        DEFAULTS.put("accruals.pause-while-afk", false);
        DEFAULTS.put("accruals.pause-while-vanished", true);
        DEFAULTS.put("accruals.require-survival-mode", false);
        DEFAULTS.put("accruals.world-blacklist", java.util.List.of());
        DEFAULTS.put("accruals.world-multipliers.world", 1.0D);
        DEFAULTS.put("accruals.world-multipliers.world_nether", 0.5D);
        DEFAULTS.put("accruals.world-multipliers.world_the_end", 0.5D);
        DEFAULTS.put("accruals.notify-on-cap", true);
        
        // Default limits
        DEFAULTS.put("defaults.max-global-claims", 1);
        DEFAULTS.put("defaults.max-sell-signs", 5);
        DEFAULTS.put("defaults.max-rent-signs", 5);
        DEFAULTS.put("defaults.max-mailbox-signs", 5);
        DEFAULTS.put("defaults.max-self-mailboxes-per-claim", 1);
        
        // Permission tracking
        DEFAULTS.put("permission-tracking.enabled", true);
        DEFAULTS.put("permission-tracking.check-interval", 5);
        
        // GUI settings
        DEFAULTS.put("gui.enabled", true);

        // Global claim list settings
        DEFAULTS.put("global-claims.enabled", true);
        DEFAULTS.put("global-claims.allow-teleport", true);
        DEFAULTS.put("global-claims.require-approval", false);
        DEFAULTS.put("global-claims.default-icon", "GRASS_BLOCK");
        DEFAULTS.put("global-claims.default-sort", "newest");
        DEFAULTS.put("global-claims.max-name-length", 48);
        DEFAULTS.put("global-claims.max-description-length", 256);
        DEFAULTS.put("global-claims.teleport-requires-safe-spawn", true);
        DEFAULTS.put("global-claims.public-signs-set-spawn", true);

        // Claim customization settings
        DEFAULTS.put("claim-customization.names.max-length", 48);
        DEFAULTS.put("claim-customization.names.allow-colors", true);
        DEFAULTS.put("claim-customization.names.allow-formats", true);
        DEFAULTS.put("claim-customization.names.allow-minimessage", true);
        DEFAULTS.put("claim-customization.names.strip-obfuscated", true);
        DEFAULTS.put("claim-customization.descriptions.max-length", 256);
        DEFAULTS.put("claim-customization.descriptions.allow-colors", true);
        DEFAULTS.put("claim-customization.descriptions.allow-formats", true);
        DEFAULTS.put("claim-customization.descriptions.allow-minimessage", true);
        DEFAULTS.put("claim-customization.descriptions.allow-links", false);
        DEFAULTS.put("claim-customization.icons.allow-custom-items", true);
        DEFAULTS.put("claim-customization.icons.allow-player-heads", true);
        DEFAULTS.put("claim-customization.icons.deny-materials", java.util.List.of("BARRIER", "COMMAND_BLOCK"));
        DEFAULTS.put("claim-customization.bans.prevent-entry", true);
        DEFAULTS.put("claim-customization.bans.prevent-teleport", true);
        DEFAULTS.put("claim-customization.bans.eject-on-reload", true);
        DEFAULTS.put("claim-customization.bans.public-ban-permission", "griefprevention.claim.ban.public");
        DEFAULTS.put("claim-customization.bans.admin-bypass-permission", "griefprevention.admin");

        // Claim flight settings
        DEFAULTS.put("claim-flight.passive-mode", false);
        DEFAULTS.put("claim-flight.enabled", true);
        DEFAULTS.put("claim-flight.require-toggle-command", true);
        DEFAULTS.put("claim-flight.consume-time-while-hovering", true);
        DEFAULTS.put("claim-flight.consume-time-in-creative", false);
        DEFAULTS.put("claim-flight.default-time", 0);
        DEFAULTS.put("claim-flight.max-time", 0);
        DEFAULTS.put("claim-flight.disable-on-pvp", true);
        DEFAULTS.put("claim-flight.disable-on-damage", false);
        DEFAULTS.put("claim-flight.disable-on-leaving-claim", true);
        DEFAULTS.put("claim-flight.landing-grace-seconds", 5);
        DEFAULTS.put("claim-flight.allow-in-admin-claims", true);
        DEFAULTS.put("claim-flight.allow-in-public-global-claims", false);
        DEFAULTS.put("claim-flight.trust-levels.owner", true);
        DEFAULTS.put("claim-flight.trust-levels.manager", true);
        DEFAULTS.put("claim-flight.trust-levels.builder", true);
        DEFAULTS.put("claim-flight.trust-levels.container", false);
        DEFAULTS.put("claim-flight.trust-levels.access", false);
        DEFAULTS.put("passive-claim-flight", false);

        // Claim teleport settings
        DEFAULTS.put("teleport.delay-seconds", 3);
        DEFAULTS.put("teleport.cooldown-seconds", 10);
        DEFAULTS.put("teleport.cancel-on-move", true);
        DEFAULTS.put("teleport.safe-location.enabled", true);
        DEFAULTS.put("teleport.safe-location.search-radius", 8);
        DEFAULTS.put("teleport.safe-location.allow-nearby-fallback", true);
        DEFAULTS.put("teleport.safe-location.confirm-unsafe-teleport", true);
        DEFAULTS.put("teleport.safe-location.staff-ignore-unsafe-location", true);
        DEFAULTS.put("teleport.effects.start-sound", "entity.enderman.teleport");
        DEFAULTS.put("teleport.effects.complete-sound", "entity.player.levelup");
        DEFAULTS.put("teleport.effects.particles", "portal");
        DEFAULTS.put("teleport.bypass.cooldown-permission", "griefprevention.claim.teleport.bypass.cooldown");
        DEFAULTS.put("teleport.bypass.delay-permission", "griefprevention.claim.teleport.bypass.delay");

        // Mailbox behavior
        DEFAULTS.put("signs.mailbox.enabled", true);
        DEFAULTS.put("signs.mailbox.protocol", "virtual");
        DEFAULTS.put("signs.mailbox.allow-self-mailboxes", true);
        DEFAULTS.put("signs.mailbox.require-container-attached", true);
        DEFAULTS.put("signs.mailbox.allow-stacked-mailboxes", true);
        DEFAULTS.put("signs.mailbox.allow-hoppers", false);
        DEFAULTS.put("signs.mailbox.allow-owner-quick-collect", true);
        DEFAULTS.put("signs.mailbox.virtual.update-mode", "snapshot");
        DEFAULTS.put("signs.mailbox.virtual.snapshot-update-interval", 0);
        DEFAULTS.put("signs.mailbox.virtual.allow-multiple-depositors", false);
        DEFAULTS.put("signs.mailbox.virtual.return-items-when-full", true);
        DEFAULTS.put("signs.mailbox.virtual.save-on-close-only", true);
        DEFAULTS.put("signs.mailbox.storage-warnings.enabled", true);
        DEFAULTS.put("signs.mailbox.sounds.open", "block.chest.open");
        DEFAULTS.put("signs.mailbox.sounds.close", "block.chest.close");
        DEFAULTS.put("signs.mailbox.sounds.deposit", "entity.item.pickup");
        DEFAULTS.put("signs.mailbox.sounds.full", "block.note_block.bass");
        DEFAULTS.put("signs.mailbox.limits.max-signs", 5);
        DEFAULTS.put("signs.mailbox.limits.max-self-mailboxes-per-claim", 1);

        // Sell sign behavior
        DEFAULTS.put("signs.sell.enabled", true);
        DEFAULTS.put("signs.sell.allow-item-payments", true);
        DEFAULTS.put("signs.sell.allow-money-payments", true);
        DEFAULTS.put("signs.sell.allow-claimblock-payments", true);
        DEFAULTS.put("signs.sell.allow-experience-payments", true);
        DEFAULTS.put("signs.sell.clear-trust-on-sale", true);
        DEFAULTS.put("signs.sell.transfer-global-listing", false);
        DEFAULTS.put("signs.sell.transfer-spawn-point", true);
        DEFAULTS.put("signs.sell.require-confirmation", true);
        DEFAULTS.put("signs.sell.remove-sign-after-sale", true);
        DEFAULTS.put("signs.sell.limits.max-signs", 5);

        // Rent sign behavior
        DEFAULTS.put("signs.rent.enabled", true);
        DEFAULTS.put("signs.rent.clear-on-abandon", true);
        DEFAULTS.put("signs.rent.max-rent-duration", "30d");
        DEFAULTS.put("signs.rent.allow-renewals", true);
        DEFAULTS.put("signs.rent.allow-item-payments", true);
        DEFAULTS.put("signs.rent.allow-money-payments", true);
        DEFAULTS.put("signs.rent.allow-claimblock-payments", true);
        DEFAULTS.put("signs.rent.allow-experience-payments", true);
        DEFAULTS.put("signs.rent.require-container-for-item-payments", true);
        DEFAULTS.put("signs.rent.prevent-owner-breaking-active-rental", true);
        DEFAULTS.put("signs.rent.auto-remove-expired-signs", false);
        DEFAULTS.put("signs.rent.renewal.max-click-renewals", 5);
        DEFAULTS.put("signs.rent.renewal.deny-renewal-when-eviction-pending", true);
        DEFAULTS.put("signs.rent.renewal.too-close-to-max-window", "1h");
        DEFAULTS.put("signs.rent.trust.grant-container-trust", false);
        DEFAULTS.put("signs.rent.trust.grant-access-trust", false);
        DEFAULTS.put("signs.rent.trust.clear-renter-trust-on-expire", true);
        DEFAULTS.put("signs.rent.trust.clear-renter-trust-on-evict", true);
        DEFAULTS.put("signs.rent.eviction.notice-period", "14d");
        DEFAULTS.put("signs.rent.eviction.allow-owner-cancel", true);
        DEFAULTS.put("signs.rent.eviction.allow-admin-cancel", true);
        DEFAULTS.put("signs.rent.eviction.require-standing-in-claim", true);
        DEFAULTS.put("signs.rent.eviction.block-sign-break-until-effective", true);
        DEFAULTS.put("signs.rent.eviction.notify-renter-on-start", true);
        DEFAULTS.put("signs.rent.eviction.notify-owner-on-complete", true);
        DEFAULTS.put("signs.rent.eviction.remove-renter-trust-on-start", false);
        DEFAULTS.put("signs.rent.eviction.remove-renter-trust-on-complete", true);
        DEFAULTS.put("signs.rent.snapshots.max-per-claim", 5);
        DEFAULTS.put("signs.rent.snapshots.auto-create.on-rent-sign-create", false);
        DEFAULTS.put("signs.rent.snapshots.auto-create.on-rental-start", false);
        DEFAULTS.put("signs.rent.snapshots.auto-create.before-eviction-complete", false);
        DEFAULTS.put("signs.rent.snapshots.auto-restore.on-rental-expire", false);
        DEFAULTS.put("signs.rent.snapshots.auto-restore.on-eviction-complete", false);
        DEFAULTS.put("signs.rent.limits.max-signs", 5);
        DEFAULTS.put("signs.global.enabled", true);
        DEFAULTS.put("signs.global.limits.max-claims-per-player", 1);

        // Tax settings
        DEFAULTS.put("tax.percent", 5);
        DEFAULTS.put("tax.account-name", "Tax");
        DEFAULTS.put("tax.enabled", true);
        DEFAULTS.put("tax.exempt-permission", "griefprevention.tax.exempt");
        DEFAULTS.put("tax.apply-to.rent", true);
        DEFAULTS.put("tax.apply-to.sell", true);
        DEFAULTS.put("tax.apply-to.mailbox", false);
        DEFAULTS.put("tax.apply-to.claim-block-purchases", false);
        DEFAULTS.put("tax.deposit-mode", "npc-account");
        DEFAULTS.put("tax.round-mode", "nearest");
        DEFAULTS.put("tax.minimum-tax", 0);
        DEFAULTS.put("tax.notify-payer", true);
        DEFAULTS.put("tax.notify-payee", true);

        // Integration behavior
        DEFAULTS.put("integrations.vault.ignore-vault-missing", true);
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

        // Ensure player-commands section is between defaults and permission-tracking (not at bottom).
        if (ensurePlayerCommandsPositionInFile()) {
            config = YamlConfiguration.loadConfiguration(configFile);
        }

        // Keep the JavaPlugin's internal config in sync so that plugin.saveConfig()
        // (used by VersionManager) does not overwrite values we just saved.
        plugin.reloadConfig();
    }

    /**
     * Ensure player-commands section exists and is placed between defaults and permission-tracking.
     * If it was added at the bottom by YamlConfiguration.save(), move it to the correct position.
     * Returns true if the file was modified.
     */
    private boolean ensurePlayerCommandsPositionInFile() {
        try {
            String content = Files.readString(configFile.toPath());
            String[] lines = content.split("\\R", -1);
            java.util.List<String> lineList = new java.util.ArrayList<>(java.util.Arrays.asList(lines));

            // Find insertion point: before "permission-tracking:" or "# Permission tracking"
            int insertBefore = -1;
            for (int i = 0; i < lineList.size(); i++) {
                String t = lineList.get(i).trim();
                if (t.equals("permission-tracking:") || t.startsWith("# Permission tracking")) {
                    insertBefore = i;
                    break;
                }
            }
            if (insertBefore < 0) return false;

            // Find existing player-commands section (with optional preceding comment block)
            int pcStart = -1;
            int pcEnd = -1;
            for (int i = 0; i < lineList.size(); i++) {
                String line = lineList.get(i);
                String t = line.trim();
                if (t.equals("player-commands:")) {
                    pcStart = i;
                    // Include preceding comment lines
                    while (pcStart > 0 && (lineList.get(pcStart - 1).trim().startsWith("#") || lineList.get(pcStart - 1).trim().isEmpty())) {
                        pcStart--;
                    }
                    pcEnd = i;
                    i++;
                    while (i < lineList.size()) {
                        String l = lineList.get(i);
                        String tr = l.trim();
                        if (tr.isEmpty() || tr.startsWith("#") || l.matches("^\\s+-\\s+.*") || tr.startsWith("-")) {
                            pcEnd = i;
                            i++;
                        } else if (tr.matches("^[a-zA-Z0-9_.-]+:.*") && !l.startsWith(" ")) {
                            break;
                        } else {
                            pcEnd = i;
                            i++;
                        }
                    }
                    break;
                }
            }

            // Build the player-commands block to insert (from existing or from default)
            java.util.List<String> block = new java.util.ArrayList<>();
            if (pcStart >= 0 && pcEnd >= pcStart) {
                for (int i = pcStart; i <= pcEnd; i++) {
                    block.add(lineList.get(i));
                }
            } else {
                // Use block from jar default
                block.add("");
                block.add("# Player command permissions (dynamically assigned to gpx.player permission)");
                block.add("# A list of permissions starting from griefprevention.<permission> that the gpx.player permission should have");
                block.add("# Permissions are dynamically updated on /gpx reload");
                block.add("player-commands:");
                @SuppressWarnings("unchecked")
                java.util.List<String> list = (java.util.List<String>) config.getList("player-commands");
                if (list == null && plugin.getResource("config.yml") != null) {
                    FileConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(plugin.getResource("config.yml")));
                    list = def.getStringList("player-commands");
                }
                if (list != null) {
                    for (String item : list) {
                        block.add("  - " + item);
                    }
                }
            }

            // Remove existing section from its current position (so we don't duplicate)
            java.util.List<String> without = new java.util.ArrayList<>();
            for (int i = 0; i < lineList.size(); i++) {
                if (i >= pcStart && i <= pcEnd) continue;
                without.add(lineList.get(i));
            }
            // If player-commands already exists and is immediately before permission-tracking, nothing to do
            if (pcStart >= 0) {
                int j = pcEnd + 1;
                while (j < lineList.size() && lineList.get(j).trim().isEmpty()) j++;
                if (j < lineList.size()) {
                    String next = lineList.get(j).trim();
                    if (next.equals("permission-tracking:") || next.startsWith("# Permission tracking")) {
                        return false;
                    }
                }
            }

            // Adjust insertBefore if we removed lines before it
            if (pcStart >= 0 && pcStart < insertBefore) {
                insertBefore -= (pcEnd - pcStart + 1);
            }

            // Insert block before permission-tracking
            java.util.List<String> out = new java.util.ArrayList<>(without.size() + block.size() + 2);
            out.addAll(without.subList(0, insertBefore));
            if (!out.isEmpty() && !out.get(out.size() - 1).trim().isEmpty()) {
                out.add("");
            }
            out.addAll(block);
            if (insertBefore < without.size() && !without.get(insertBefore).trim().isEmpty()) {
                out.add("");
            }
            out.addAll(without.subList(insertBefore, without.size()));

            String newContent = String.join(System.lineSeparator(), out);
            if (newContent.equals(content)) return false;

            Files.writeString(configFile.toPath(), newContent);
            plugin.getLogger().info("Config updated: placed player-commands between defaults and permission-tracking.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to place player-commands block in config.yml: " + e.getMessage());
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

        // player-commands: when missing, copy list from jar default so it always appears in config
        if (!config.contains("player-commands") && defaultConfig != null && defaultConfig.contains("player-commands")) {
            config.set("player-commands", defaultConfig.getList("player-commands"));
            modified = true;
            plugin.getLogger().info("Added missing config option: player-commands (from default)");
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

    public int getMaxGlobalClaims() {
        return config.getInt("defaults.max-global-claims", 1);
    }

    public int getClaimTeleportDelaySeconds() {
        return Math.max(0, config.getInt("teleport.delay-seconds", 0));
    }

    public int getClaimTeleportCooldownSeconds() {
        return Math.max(0, config.getInt("teleport.cooldown-seconds", 0));
    }

    public boolean isClaimTeleportCancelOnMove() {
        return config.getBoolean("teleport.cancel-on-move", true);
    }

    public boolean isClaimTeleportSafeLocationEnabled() {
        return config.getBoolean("teleport.safe-location.enabled", true);
    }

    public int getClaimTeleportSafeLocationSearchRadius() {
        return Math.max(0, config.getInt("teleport.safe-location.search-radius", 8));
    }

    public boolean isClaimTeleportNearbyFallbackAllowed() {
        return config.getBoolean("teleport.safe-location.allow-nearby-fallback", true);
    }

    public boolean isClaimTeleportUnsafeConfirmationEnabled() {
        return config.getBoolean("teleport.safe-location.confirm-unsafe-teleport", true);
    }

    public boolean isClaimTeleportStaffIgnoreUnsafeLocation() {
        return config.getBoolean("teleport.safe-location.staff-ignore-unsafe-location", true);
    }

    public String getClaimTeleportStartSound() {
        return config.getString("teleport.effects.start-sound", "entity.enderman.teleport");
    }

    public String getClaimTeleportCompleteSound() {
        return config.getString("teleport.effects.complete-sound", "entity.player.levelup");
    }

    public String getClaimTeleportParticle() {
        return config.getString("teleport.effects.particles", "portal");
    }

    public String getClaimTeleportCooldownBypassPermission() {
        return normalizedPermission("teleport.bypass.cooldown-permission", "griefprevention.claim.teleport.bypass.cooldown");
    }

    public String getClaimTeleportDelayBypassPermission() {
        return normalizedPermission("teleport.bypass.delay-permission", "griefprevention.claim.teleport.bypass.delay");
    }

    public boolean isPassiveClaimFlightEnabled() {
        if (config.contains("claim-flight.passive-mode")) {
            return config.getBoolean("claim-flight.passive-mode", false);
        }
        if (config.contains("claim-flight.passive-claim-flight")) {
            return config.getBoolean("claim-flight.passive-claim-flight", false);
        }
        return config.getBoolean("passive-claim-flight", false);
    }

    public boolean isClaimFlightEnabled() {
        return config.getBoolean("claim-flight.enabled", true);
    }

    public boolean isClaimFlightToggleRequired() {
        return config.getBoolean("claim-flight.require-toggle-command", true);
    }

    public boolean isClaimFlightTimeConsumedWhileHovering() {
        return config.getBoolean("claim-flight.consume-time-while-hovering", true);
    }

    public boolean isClaimFlightTimeConsumedInCreative() {
        return config.getBoolean("claim-flight.consume-time-in-creative", false);
    }

    public long getClaimFlightDefaultMillis() {
        return parseDurationMillis(config.get("claim-flight.default-time", 0));
    }

    public long getClaimFlightMaxMillis() {
        return parseDurationMillis(config.get("claim-flight.max-time", 0));
    }

    public boolean isClaimFlightDisabledOnPvp() {
        return config.getBoolean("claim-flight.disable-on-pvp", true);
    }

    public boolean isClaimFlightDisabledOnDamage() {
        return config.getBoolean("claim-flight.disable-on-damage", false);
    }

    public boolean isClaimFlightDisabledOnLeavingClaim() {
        return config.getBoolean("claim-flight.disable-on-leaving-claim", true);
    }

    public int getClaimFlightLandingGraceSeconds() {
        return Math.max(0, config.getInt("claim-flight.landing-grace-seconds", 5));
    }

    public boolean isClaimFlightAllowedInAdminClaims() {
        return config.getBoolean("claim-flight.allow-in-admin-claims", true);
    }

    public boolean isClaimFlightAllowedInPublicGlobalClaims() {
        return config.getBoolean("claim-flight.allow-in-public-global-claims", false);
    }

    public boolean isClaimFlightOwnerTrustAllowed() {
        return config.getBoolean("claim-flight.trust-levels.owner", true);
    }

    public boolean isClaimFlightManagerTrustAllowed() {
        return config.getBoolean("claim-flight.trust-levels.manager", true);
    }

    public boolean isClaimFlightBuilderTrustAllowed() {
        return config.getBoolean("claim-flight.trust-levels.builder", true);
    }

    public boolean isClaimFlightContainerTrustAllowed() {
        return config.getBoolean("claim-flight.trust-levels.container", false);
    }

    public boolean isClaimFlightAccessTrustAllowed() {
        return config.getBoolean("claim-flight.trust-levels.access", false);
    }

    /** "real" = create subdivision + container trust public, owner opens real container; "virtual" = no subdivision, virtual view only */
    public String getMailboxProtocol() {
        String value = config.contains("signs.mailbox.protocol")
            ? config.getString("signs.mailbox.protocol", "virtual")
            : config.getString("mailbox-protocol", "virtual");
        return value == null ? "virtual" : value.trim().toLowerCase(Locale.ROOT);
    }
    
    public boolean isMailboxProtocolReal() {
        return "real".equals(getMailboxProtocol());
    }

    public boolean areRentSignsEnabled() {
        return config.getBoolean("signs.rent.enabled", true);
    }

    public boolean areSellSignsEnabled() {
        return config.getBoolean("signs.sell.enabled", true);
    }

    public boolean areMailboxSignsEnabled() {
        return config.getBoolean("signs.mailbox.enabled", true);
    }

    public boolean areGlobalSignsEnabled() {
        return config.getBoolean("signs.global.enabled", true);
    }

    public boolean areSelfMailboxesAllowed() {
        return config.getBoolean("signs.mailbox.allow-self-mailboxes", true);
    }

    public boolean isMailboxContainerAttachmentRequired() {
        return config.getBoolean("signs.mailbox.require-container-attached", true);
    }

    public boolean areStackedMailboxesAllowed() {
        return config.getBoolean("signs.mailbox.allow-stacked-mailboxes", true);
    }

    public boolean areMailboxHoppersAllowed() {
        return config.getBoolean("signs.mailbox.allow-hoppers", false);
    }

    public boolean isMailboxOwnerQuickCollectAllowed() {
        return config.getBoolean("signs.mailbox.allow-owner-quick-collect", true);
    }

    public String getMailboxVirtualUpdateMode() {
        String value = config.getString("signs.mailbox.virtual.update-mode", "snapshot");
        return value == null ? "snapshot" : value.trim().toLowerCase(Locale.ROOT);
    }

    public int getMailboxVirtualSnapshotUpdateInterval() {
        return Math.max(0, config.getInt("signs.mailbox.virtual.snapshot-update-interval", 0));
    }

    public boolean areMailboxVirtualMultipleDepositorsAllowed() {
        return config.getBoolean("signs.mailbox.virtual.allow-multiple-depositors", false);
    }

    public boolean areMailboxVirtualItemsReturnedWhenFull() {
        return config.getBoolean("signs.mailbox.virtual.return-items-when-full", true);
    }

    public boolean isMailboxVirtualSaveOnCloseOnly() {
        return config.getBoolean("signs.mailbox.virtual.save-on-close-only", true);
    }

    public boolean areMailboxStorageWarningsEnabled() {
        return config.getBoolean("signs.mailbox.storage-warnings.enabled", true);
    }

    public java.util.List<Integer> getMailboxStorageWarningThresholds() {
        java.util.List<Integer> configured = config.getIntegerList("signs.mailbox.storage-warnings.thresholds");
        if (configured.isEmpty()) {
            configured = java.util.List.of(75, 90, 100);
        }
        java.util.List<Integer> thresholds = new java.util.ArrayList<>();
        for (Integer threshold : configured) {
            if (threshold == null) continue;
            thresholds.add(Math.max(0, Math.min(100, threshold)));
        }
        return thresholds;
    }

    public String getMailboxOpenSound() {
        return config.getString("signs.mailbox.sounds.open", "block.chest.open");
    }

    public String getMailboxCloseSound() {
        return config.getString("signs.mailbox.sounds.close", "block.chest.close");
    }

    public String getMailboxDepositSound() {
        return config.getString("signs.mailbox.sounds.deposit", "entity.item.pickup");
    }

    public String getMailboxFullSound() {
        return config.getString("signs.mailbox.sounds.full", "block.note_block.bass");
    }

    public boolean areSellItemPaymentsAllowed() {
        return config.getBoolean("signs.sell.allow-item-payments", true);
    }

    public boolean areSellMoneyPaymentsAllowed() {
        return config.getBoolean("signs.sell.allow-money-payments", true);
    }

    public boolean areSellClaimBlockPaymentsAllowed() {
        return config.getBoolean("signs.sell.allow-claimblock-payments", true);
    }

    public boolean areSellExperiencePaymentsAllowed() {
        return config.getBoolean("signs.sell.allow-experience-payments", true);
    }

    public boolean isSellTrustClearedOnSale() {
        return config.getBoolean("signs.sell.clear-trust-on-sale", true);
    }

    public boolean isSellGlobalListingTransferred() {
        return config.getBoolean("signs.sell.transfer-global-listing", false);
    }

    public boolean isSellSpawnPointTransferred() {
        return config.getBoolean("signs.sell.transfer-spawn-point", true);
    }

    public boolean isSellConfirmationRequired() {
        return config.getBoolean("signs.sell.require-confirmation", true);
    }

    public boolean isSellSignRemovedAfterSale() {
        return config.getBoolean("signs.sell.remove-sign-after-sale", true);
    }

    public boolean areRentRenewalsAllowed() {
        return config.getBoolean("signs.rent.allow-renewals", true);
    }

    public boolean isRentClearedOnAbandon() {
        return config.getBoolean("signs.rent.clear-on-abandon", true);
    }

    public long getMaxRentDurationMillis() {
        return parseDurationMillis(config.get("signs.rent.max-rent-duration", "30d"));
    }

    public boolean areRentItemPaymentsAllowed() {
        return config.getBoolean("signs.rent.allow-item-payments", true);
    }

    public boolean isRentItemPaymentContainerRequired() {
        return config.getBoolean("signs.rent.require-container-for-item-payments", true);
    }

    public boolean areRentMoneyPaymentsAllowed() {
        return config.getBoolean("signs.rent.allow-money-payments", true);
    }

    public boolean areRentClaimBlockPaymentsAllowed() {
        return config.getBoolean("signs.rent.allow-claimblock-payments", true);
    }

    public boolean areRentExperiencePaymentsAllowed() {
        return config.getBoolean("signs.rent.allow-experience-payments", true);
    }

    public boolean isOwnerBreakingActiveRentalPrevented() {
        return config.getBoolean("signs.rent.prevent-owner-breaking-active-rental", true);
    }

    public boolean areExpiredRentSignsAutoRemoved() {
        return config.getBoolean("signs.rent.auto-remove-expired-signs", false);
    }

    public int getMaxRentClickRenewals() {
        return Math.max(0, config.getInt("signs.rent.renewal.max-click-renewals", 5));
    }

    public boolean areRentRenewalsDeniedDuringEviction() {
        return config.getBoolean("signs.rent.renewal.deny-renewal-when-eviction-pending", true);
    }

    public String getRentTooCloseToMaxWindow() {
        return config.getString("signs.rent.renewal.too-close-to-max-window", "1h");
    }

    public boolean isRentContainerTrustGranted() {
        return config.getBoolean("signs.rent.trust.grant-container-trust", false);
    }

    public boolean isRentAccessTrustGranted() {
        return config.getBoolean("signs.rent.trust.grant-access-trust", false);
    }

    public boolean isRenterTrustClearedOnExpire() {
        return config.getBoolean("signs.rent.trust.clear-renter-trust-on-expire", true);
    }

    public boolean isRenterTrustClearedOnEvict() {
        if (config.contains("signs.rent.eviction.remove-renter-trust-on-complete")) {
            return config.getBoolean("signs.rent.eviction.remove-renter-trust-on-complete", true);
        }
        return config.getBoolean("signs.rent.trust.clear-renter-trust-on-evict", true);
    }

    public String getRentEvictionNoticePeriod() {
        if (config.contains("signs.rent.eviction.notice-period")) {
            return String.valueOf(config.get("signs.rent.eviction.notice-period", "14d"));
        }
        return String.valueOf(config.get("eviction.notice-period", "14d"));
    }

    public boolean isRentEvictionOwnerCancelAllowed() {
        return config.getBoolean("signs.rent.eviction.allow-owner-cancel", true);
    }

    public boolean isRentEvictionAdminCancelAllowed() {
        return config.getBoolean("signs.rent.eviction.allow-admin-cancel", true);
    }

    public boolean isRentEvictionStandingRequired() {
        return config.getBoolean("signs.rent.eviction.require-standing-in-claim", true);
    }

    public boolean isRentEvictionSignBreakBlockedUntilEffective() {
        return config.getBoolean("signs.rent.eviction.block-sign-break-until-effective", true);
    }

    public boolean isRenterNotifiedOnEvictionStart() {
        return config.getBoolean("signs.rent.eviction.notify-renter-on-start", true);
    }

    public boolean isOwnerNotifiedOnEvictionComplete() {
        return config.getBoolean("signs.rent.eviction.notify-owner-on-complete", true);
    }

    public boolean isRenterTrustRemovedOnEvictionStart() {
        return config.getBoolean("signs.rent.eviction.remove-renter-trust-on-start", false);
    }

    public int getRentSnapshotMaxPerClaim() {
        if (config.contains("signs.rent.snapshots.max-per-claim")) {
            return Math.max(0, config.getInt("signs.rent.snapshots.max-per-claim", 5));
        }
        return Math.max(0, config.getInt("snapshots.max-per-claim", 5));
    }

    public boolean isRentSnapshotAutoCreateOnSignCreate() {
        if (config.contains("signs.rent.snapshots.auto-create.on-rent-sign-create")) {
            return config.getBoolean("signs.rent.snapshots.auto-create.on-rent-sign-create", false);
        }
        return config.getBoolean("snapshots.auto-create.on-rent-sign-create", false);
    }

    public boolean isRentSnapshotAutoCreateOnRentalStart() {
        if (config.contains("signs.rent.snapshots.auto-create.on-rental-start")) {
            return config.getBoolean("signs.rent.snapshots.auto-create.on-rental-start", false);
        }
        return config.getBoolean("snapshots.auto-create.on-rental-start", false);
    }

    public boolean isRentSnapshotAutoCreateBeforeEvictionComplete() {
        if (config.contains("signs.rent.snapshots.auto-create.before-eviction-complete")) {
            return config.getBoolean("signs.rent.snapshots.auto-create.before-eviction-complete", false);
        }
        return config.getBoolean("snapshots.auto-create.before-eviction-complete", false);
    }

    public boolean isRentSnapshotAutoRestoreOnRentalExpire() {
        if (config.contains("signs.rent.snapshots.auto-restore.on-rental-expire")) {
            return config.getBoolean("signs.rent.snapshots.auto-restore.on-rental-expire", false);
        }
        return config.getBoolean("snapshots.auto-restore.on-rental-expire", false);
    }

    public boolean isRentSnapshotAutoRestoreOnEvictionComplete() {
        if (config.contains("signs.rent.snapshots.auto-restore.on-eviction-complete")) {
            return config.getBoolean("signs.rent.snapshots.auto-restore.on-eviction-complete", false);
        }
        if (config.contains("signs.rent.eviction.restore-snapshot-on-evict")) {
            return config.getBoolean("signs.rent.eviction.restore-snapshot-on-evict", false);
        }
        return config.getBoolean("snapshots.auto-restore.on-eviction-complete", false);
    }
    
    public boolean isPermissionTrackingEnabled() {
        return config.getBoolean("permission-tracking.enabled", true);
    }
    
    public int getPermissionCheckInterval() {
        return config.getInt("permission-tracking.check-interval", 5);
    }

    public boolean areAccrualsEnabled() {
        return config.getBoolean("accruals.enabled", true);
    }

    public boolean areAccrualsPausedWhileAfk() {
        return config.getBoolean("accruals.pause-while-afk", false);
    }

    public boolean areAccrualsPausedWhileVanished() {
        return config.getBoolean("accruals.pause-while-vanished", true);
    }

    public boolean doAccrualsRequireSurvivalMode() {
        return config.getBoolean("accruals.require-survival-mode", false);
    }

    public java.util.List<String> getAccrualWorldBlacklist() {
        return config.getStringList("accruals.world-blacklist");
    }

    public double getAccrualWorldMultiplier(String worldName) {
        if (worldName == null || worldName.isBlank()) return 1.0D;
        return Math.max(0.0D, config.getDouble("accruals.world-multipliers." + worldName, 1.0D));
    }

    public boolean shouldNotifyAccrualCap() {
        return config.getBoolean("accruals.notify-on-cap", true);
    }
    
    public boolean isGUIEnabled() {
        return config.getBoolean("gui.enabled", true);
    }

    public boolean isGlobalClaimsEnabled() {
        return config.getBoolean("global-claims.enabled", true);
    }

    public boolean isGlobalClaimsTeleportAllowed() {
        return config.getBoolean("global-claims.allow-teleport", true);
    }

    public String getGlobalClaimsDefaultIcon() {
        return config.getString("global-claims.default-icon", "GRASS_BLOCK");
    }

    public String getGlobalClaimsDefaultSort() {
        String value = config.getString("global-claims.default-sort", "newest");
        return value == null ? "newest" : value.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isGlobalClaimsApprovalRequired() {
        return config.getBoolean("global-claims.require-approval", false);
    }

    public int getGlobalClaimsMaxNameLength() {
        return Math.max(1, config.getInt("global-claims.max-name-length", 48));
    }

    public int getGlobalClaimsMaxDescriptionLength() {
        return Math.max(1, config.getInt("global-claims.max-description-length", 256));
    }

    public int getClaimNameMaxLength() {
        return Math.max(1, config.getInt("claim-customization.names.max-length", getGlobalClaimsMaxNameLength()));
    }

    public int getClaimDescriptionMaxLength() {
        return Math.max(1, config.getInt("claim-customization.descriptions.max-length", getGlobalClaimsMaxDescriptionLength()));
    }

    public boolean areClaimNameColorsAllowed() {
        return config.getBoolean("claim-customization.names.allow-colors", true);
    }

    public boolean areClaimNameFormatsAllowed() {
        return config.getBoolean("claim-customization.names.allow-formats", true);
    }

    public boolean areClaimNameMiniMessageTagsAllowed() {
        return config.getBoolean("claim-customization.names.allow-minimessage", true);
    }

    public boolean isClaimNameObfuscatedStripped() {
        return config.getBoolean("claim-customization.names.strip-obfuscated", true);
    }

    public boolean areClaimDescriptionColorsAllowed() {
        return config.getBoolean("claim-customization.descriptions.allow-colors", true);
    }

    public boolean areClaimDescriptionFormatsAllowed() {
        return config.getBoolean("claim-customization.descriptions.allow-formats", true);
    }

    public boolean areClaimDescriptionMiniMessageTagsAllowed() {
        return config.getBoolean("claim-customization.descriptions.allow-minimessage", true);
    }

    public boolean areClaimDescriptionLinksAllowed() {
        return config.getBoolean("claim-customization.descriptions.allow-links", false);
    }

    public boolean areCustomClaimIconItemsAllowed() {
        return config.getBoolean("claim-customization.icons.allow-custom-items", true);
    }

    public boolean areClaimIconPlayerHeadsAllowed() {
        return config.getBoolean("claim-customization.icons.allow-player-heads", true);
    }

    public java.util.List<String> getDeniedClaimIconMaterials() {
        return config.getStringList("claim-customization.icons.deny-materials");
    }

    public boolean isClaimBanEntryPreventionEnabled() {
        return config.getBoolean("claim-customization.bans.prevent-entry", true);
    }

    public boolean isClaimBanTeleportPreventionEnabled() {
        return config.getBoolean("claim-customization.bans.prevent-teleport", true);
    }

    public boolean isClaimBanEjectOnReloadEnabled() {
        return config.getBoolean("claim-customization.bans.eject-on-reload", true);
    }

    public String getClaimBanPublicPermission() {
        String permission = config.getString("claim-customization.bans.public-ban-permission", "griefprevention.claim.ban.public");
        return permission == null || permission.isBlank() ? "griefprevention.claim.ban.public" : permission.trim();
    }

    public String getClaimBanAdminBypassPermission() {
        String permission = config.getString("claim-customization.bans.admin-bypass-permission", "griefprevention.admin");
        return permission == null || permission.isBlank() ? "griefprevention.admin" : permission.trim();
    }

    public boolean isGlobalTeleportSafeSpawnRequired() {
        return config.getBoolean("global-claims.teleport-requires-safe-spawn", true);
    }

    public boolean doGlobalClaimSignsSetSpawn() {
        return config.getBoolean("global-claims.public-signs-set-spawn", true);
    }
    
    public int getTaxPercent() {
        return config.getInt("tax.percent", 5);
    }

    public String getTaxAccountName() {
        return config.getString("tax.account-name", "Tax");
    }

    public boolean isTaxEnabled() {
        return config.getBoolean("tax.enabled", getTaxPercent() > 0);
    }

    public String getTaxExemptPermission() {
        String permission = config.getString("tax.exempt-permission", "griefprevention.tax.exempt");
        return permission == null || permission.isBlank() ? "griefprevention.tax.exempt" : permission.trim();
    }

    public boolean doesTaxApplyToRent() {
        return config.getBoolean("tax.apply-to.rent", true);
    }

    public boolean doesTaxApplyToSell() {
        return config.getBoolean("tax.apply-to.sell", true);
    }

    public boolean doesTaxApplyToMailbox() {
        return config.getBoolean("tax.apply-to.mailbox", false);
    }

    public boolean doesTaxApplyToClaimBlockPurchases() {
        return config.getBoolean("tax.apply-to.claim-block-purchases", false);
    }

    public String getTaxDepositMode() {
        String mode = config.getString("tax.deposit-mode", "npc-account");
        return mode == null || mode.isBlank() ? "npc-account" : mode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public String getTaxRoundMode() {
        String mode = config.getString("tax.round-mode", "nearest");
        return mode == null || mode.isBlank() ? "nearest" : mode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public double getMinimumTax() {
        return Math.max(0D, config.getDouble("tax.minimum-tax", 0D));
    }

    public boolean shouldNotifyTaxPayer() {
        return config.getBoolean("tax.notify-payer", true);
    }

    public boolean shouldNotifyTaxPayee() {
        return config.getBoolean("tax.notify-payee", true);
    }

    public boolean isIgnoreVaultMissing() {
        if (config.contains("integrations.vault.ignore-vault-missing")) {
            return config.getBoolean("integrations.vault.ignore-vault-missing", true);
        }
        return config.getBoolean("integrations.vault.ignore-missing", true);
    }

    private String normalizedPermission(String path, String fallback) {
        String permission = config.getString(path, fallback);
        return permission == null || permission.isBlank() ? fallback : permission.trim();
    }

    private long parseDurationMillis(Object raw) {
        if (raw == null) return 0L;
        if (raw instanceof Number number) {
            return Math.max(0L, number.longValue()) * 1000L;
        }
        String value = raw.toString().trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (value.isEmpty()) return 0L;
        long totalSeconds = 0L;
        int index = 0;
        while (index < value.length()) {
            int start = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) index++;
            if (start == index) return 0L;
            long amount;
            try {
                amount = Long.parseLong(value.substring(start, index));
            } catch (NumberFormatException e) {
                return 0L;
            }
            if (index >= value.length()) {
                totalSeconds += amount;
                break;
            }
            char unit = value.charAt(index++);
            switch (unit) {
                case 'w' -> totalSeconds += amount * 604800L;
                case 'd' -> totalSeconds += amount * 86400L;
                case 'h' -> totalSeconds += amount * 3600L;
                case 'm' -> totalSeconds += amount * 60L;
                case 's' -> totalSeconds += amount;
                default -> {
                    return 0L;
                }
            }
        }
        return Math.max(0L, totalSeconds * 1000L);
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
