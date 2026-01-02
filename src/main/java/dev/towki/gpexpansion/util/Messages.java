package dev.towki.gpexpansion.util;

import dev.towki.gpexpansion.GPExpansionPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages language messages for GPExpansion.
 * Loads messages from lang.yml and provides methods to retrieve and format them.
 * Falls back to hardcoded defaults if lang.yml is missing or incomplete.
 */
public class Messages {
    
    private final GPExpansionPlugin plugin;
    private FileConfiguration langConfig;
    private File langFile;
    
    // Cache for frequently accessed messages
    private final Map<String, String> messageCache = new HashMap<>();
    
    // Hardcoded fallback defaults
    private static final Map<String, String> DEFAULTS = new HashMap<>();
    
    static {
        // General
        DEFAULTS.put("general.prefix", "&8[&6GPX&8]&r ");
        DEFAULTS.put("general.no-permission", "&cYou don't have permission to do that.");
        DEFAULTS.put("general.reload-success", "&aConfiguration and language files reloaded successfully.");
        DEFAULTS.put("general.player-only", "&cThis command can only be used by players.");
        
        // Permissions
        DEFAULTS.put("permissions.economy-type-denied", "&c✖ You don't have permission to use &6{economy}&c economy for {signtype} signs.");
        DEFAULTS.put("permissions.economy-type-denied-detail", "&7  Missing: &e{permission}");
        DEFAULTS.put("permissions.create-sign-denied", "&cYou don't have permission to create {signtype} signs.");
        DEFAULTS.put("permissions.create-sign-denied-detail", "&7  Missing: &e{permission}");
        
        // Sign creation
        DEFAULTS.put("sign-creation.rent-limit-reached", "&cYou have reached your rent sign limit ({current}/{max}).");
        DEFAULTS.put("sign-creation.sell-limit-reached", "&cYou have reached your sell sign limit ({current}/{max}).");
        DEFAULTS.put("sign-creation.mailbox-limit-reached", "&cYou have reached your mailbox sign limit ({current}/{max}).");
        DEFAULTS.put("sign-creation.invalid-economy-type", "&cInvalid economy type: {type}");
        DEFAULTS.put("sign-creation.invalid-claim-id", "&cClaim not found with ID: {id}");
        DEFAULTS.put("sign-creation.vault-required", "&cMoney economy requires a Vault economy provider.");
        DEFAULTS.put("sign-creation.item-required", "&cHold the payment item in your offhand when creating an Item sign.");
        
        // Wizard - General
        DEFAULTS.put("wizard.cancelled", "&cSetup wizard cancelled.");
        DEFAULTS.put("wizard.previous-cancelled", "&7(Previous setup wizard cancelled)");
        DEFAULTS.put("wizard.claim-not-found", "&cClaim ID not found: {id}");
        DEFAULTS.put("wizard.not-claim-owner", "&cYou don't own that claim!");
        DEFAULTS.put("wizard.invalid-claim-id", "&cInvalid claim ID. Please enter a number.\n&7(Type 'cancel' to exit the wizard)");
        DEFAULTS.put("wizard.invalid-duration", "&cInvalid duration format. Use: &e<number><s/m/h/d/w>\n&7Examples: &e30s&7, &e1h&7, &e7d&7, &e1w");
        DEFAULTS.put("wizard.invalid-economy-type", "&cInvalid economy type.\n&7Valid options: &emoney&7, &eexp&7, &eclaimblocks&7, &eitem");
        DEFAULTS.put("wizard.invalid-price", "&cInvalid price. Please enter a number.");
        DEFAULTS.put("wizard.vault-required", "&cMoney payments require Vault and an economy plugin.\n&7Please choose a different payment type.");
        DEFAULTS.put("wizard.yes-or-no", "&7Type &ayes&7 or &cno&7.");
        DEFAULTS.put("wizard.confirm-prompt", "&7Type &ayes&7 to confirm or &cno&7 to cancel.");
        DEFAULTS.put("wizard.gp3d-required", "&cMailbox setup requires GP3D (GriefPrevention 3D) to be installed.");
        DEFAULTS.put("wizard.mailbox-must-be-subdivision", "&cMailbox must reference a 3D subdivision.");
        DEFAULTS.put("wizard.mailbox-wrong-size", "&cMailbox must reference a 1x1x1 subdivision (current: {width}x{height}x{depth}).");
        
        // Wizard - Rent
        DEFAULTS.put("wizard.rent-start", "&aRenting claim &6{id}&a...");
        DEFAULTS.put("wizard.rent-start-no-claim", "&a&l=== Rent Claim Sign Setup ===");
        DEFAULTS.put("wizard.rent-enter-claim-id", "&eEnter the claim ID:");
        DEFAULTS.put("wizard.rent-enter-claim-id-hint", "&7(Quick tip: do &6/claimlist&7 to view your claim IDs)");
        
        // Wizard - Sell
        DEFAULTS.put("wizard.sell-start", "&aSelling claim &6{id}&a...");
        DEFAULTS.put("wizard.sell-start-no-claim", "&a&l=== Sell Claim Sign Setup ===");
        DEFAULTS.put("wizard.sell-enter-claim-id", "&eEnter the claim ID:");
        DEFAULTS.put("wizard.sell-enter-claim-id-hint", "&7(Quick tip: do &6/claimlist&7 to view your claim IDs)");
        
        // Wizard - Mailbox
        DEFAULTS.put("wizard.mailbox-start", "&aSetting up mailbox for claim &6{id}&a...");
        DEFAULTS.put("wizard.mailbox-start-no-claim", "&a&l=== Mailbox Sign Setup ===");
        DEFAULTS.put("wizard.mailbox-enter-claim-id", "&eEnter the 1x1x1 subdivision claim ID:");
        DEFAULTS.put("wizard.mailbox-enter-claim-id-hint", "&7(Quick tip: do &6/claimlist&7 to view your claim IDs)");
        
        // Wizard - Auto-paste
        DEFAULTS.put("wizard.auto-paste-ready", "&a✓ Sign format loaded! Edit if needed, then click Done.");
        DEFAULTS.put("wizard.auto-paste-item-reminder", "&e⚠ Hold the payment item in your offhand when placing the sign!");
        DEFAULTS.put("wizard.auto-paste-cancelled", "&cAuto-paste mode cancelled.");
        
        // Wizard - Step prompts
        DEFAULTS.put("wizard.step-prompt", "&aStep {step}: {prompt}");
        DEFAULTS.put("wizard.cancel-hint", "&8(Type 'cancel' at any time to exit)");
        
        // Claim
        DEFAULTS.put("claim.list-header", "&eClaims ({count}):");
        DEFAULTS.put("claim.list-trusted-header", "&eTrusted Claims ({count}):");
        DEFAULTS.put("claim.list-admin-header", "&eAdmin Claims ({count}):");
        DEFAULTS.put("claim.list-empty", "&7You don't own any claims.");
        DEFAULTS.put("claim.not-found", "&cClaim ID not found: {id}");
        DEFAULTS.put("claim.not-owner", "&cYou must own claim {id} to do that.");
        DEFAULTS.put("claim.name-set", "&aClaim name set to: {name}");
        DEFAULTS.put("claim.name-no-permission", "&cYou lack permission: &egpexpansion.claim.name");
        DEFAULTS.put("claim.transfer-success", "&aClaim {id} transferred to {player}.");
        DEFAULTS.put("claim.transfer-received", "&aYou are now the owner of claim {id}.");
        DEFAULTS.put("claim.ban-success", "&aBanned {player} from claim {id}.");
        DEFAULTS.put("claim.unban-success", "&aUnbanned {player} from claim {id}.");
        
        // Admin
        DEFAULTS.put("admin.gpx-help-header", "&6=== GPExpansion Admin Commands ===");
        DEFAULTS.put("admin.gpx-reload", "&e/gpx reload &7- Reload config and language files");
        DEFAULTS.put("admin.gpx-debug", "&e/gpx debug &7- Toggle debug mode");
        DEFAULTS.put("admin.gpx-max", "&e/gpx max &7- Modify player creation limits.");
        DEFAULTS.put("admin.debug-enabled", "&aDebug mode enabled.");
        DEFAULTS.put("admin.debug-disabled", "&cDebug mode disabled.");
    }
    
    public Messages(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        loadLanguageFile();
    }
    
    /**
     * Load or reload the language file.
     */
    public void loadLanguageFile() {
        messageCache.clear();
        
        langFile = new File(plugin.getDataFolder(), "lang.yml");
        
        if (!langFile.exists()) {
            plugin.saveResource("lang.yml", false);
        }
        
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // Load defaults from jar
        InputStream defaultStream = plugin.getResource("lang.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultStream, StandardCharsets.UTF_8)
            );
            langConfig.setDefaults(defaultConfig);
        }
    }
    
    /**
     * Save any changes to the language file.
     */
    public void saveLanguageFile() {
        try {
            langConfig.save(langFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save lang.yml: " + e.getMessage());
        }
    }
    
    /**
     * Get a raw message string from the language file.
     * Uses caching for performance. Falls back to hardcoded defaults if missing.
     */
    public String getRaw(String path) {
        if (messageCache.containsKey(path)) {
            return messageCache.get(path);
        }
        
        String message = langConfig.getString(path);
        if (message == null) {
            // Try hardcoded fallback
            message = DEFAULTS.get(path);
        }
        if (message == null) {
            // Ultimate fallback - show path for debugging
            message = "&7[" + path + "]";
        }
        
        messageCache.put(path, message);
        return message;
    }
    
    /**
     * Get a raw message with a specific fallback if not found.
     */
    public String getRaw(String path, String fallback) {
        if (messageCache.containsKey(path)) {
            return messageCache.get(path);
        }
        
        String message = langConfig.getString(path);
        if (message == null) {
            message = DEFAULTS.get(path);
        }
        if (message == null) {
            message = fallback;
        }
        
        messageCache.put(path, message);
        return message;
    }
    
    /**
     * Get a message string with placeholders replaced.
     */
    public String getRaw(String path, String... replacements) {
        String message = getRaw(path);
        
        // Replace placeholders in pairs: {key}, value, {key2}, value2, etc.
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = replacements[i];
            String value = replacements[i + 1];
            message = message.replace(placeholder, value);
        }
        
        return message;
    }
    
    /**
     * Get a message as a Component with color codes parsed.
     */
    public Component get(String path) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(getRaw(path));
    }
    
    /**
     * Get a message as a Component with placeholders replaced.
     */
    public Component get(String path, String... replacements) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(getRaw(path, replacements));
    }
    
    /**
     * Send a message to a player.
     */
    public void send(Player player, String path) {
        player.sendMessage(get(path));
    }
    
    /**
     * Send a message to a player with placeholders.
     */
    public void send(Player player, String path, String... replacements) {
        player.sendMessage(get(path, replacements));
    }
    
    /**
     * Send a prefixed message to a player.
     */
    public void sendPrefixed(Player player, String path) {
        String prefix = getRaw("general.prefix");
        String message = getRaw(path);
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + message));
    }
    
    /**
     * Send a prefixed message to a player with placeholders.
     */
    public void sendPrefixed(Player player, String path, String... replacements) {
        String prefix = getRaw("general.prefix");
        String message = getRaw(path, replacements);
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + message));
    }
    
    /**
     * Check if a message path exists.
     */
    public boolean hasMessage(String path) {
        return langConfig.contains(path);
    }
    
    /**
     * Get the underlying configuration for advanced access.
     */
    public FileConfiguration getConfig() {
        return langConfig;
    }
    
    // =========================================================================
    // CONVENIENCE METHODS FOR COMMON MESSAGES
    // =========================================================================
    
    /**
     * Send economy type permission denied message.
     */
    public void sendEconomyPermissionDenied(Player player, String economy, String signType, String permission) {
        send(player, "permissions.economy-type-denied", 
            "{economy}", economy,
            "{signtype}", signType);
        
        if (plugin.getConfig().getBoolean("messages.show-permission-details", true)) {
            send(player, "permissions.economy-type-denied-detail",
                "{permission}", permission);
        }
    }
    
    /**
     * Send sign creation permission denied message.
     */
    public void sendCreatePermissionDenied(Player player, String signType, String permission) {
        send(player, "permissions.create-sign-denied",
            "{signtype}", signType);
        
        if (plugin.getConfig().getBoolean("messages.show-permission-details", true)) {
            send(player, "permissions.create-sign-denied-detail",
                "{permission}", permission);
        }
    }
    
    /**
     * Send sign limit reached message.
     */
    public void sendLimitReached(Player player, String signType, int current, int max) {
        String path = "sign-creation." + signType.toLowerCase() + "-limit-reached";
        send(player, path,
            "{current}", String.valueOf(current),
            "{max}", String.valueOf(max));
    }
    
    /**
     * Send wizard step message.
     */
    public void sendWizardMessage(Player player, String wizardType, String step) {
        String path = "wizard." + wizardType + "-" + step;
        send(player, path);
    }
}
