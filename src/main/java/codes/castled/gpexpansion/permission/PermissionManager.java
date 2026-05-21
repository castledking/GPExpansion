package codes.castled.gpexpansion.permission;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;

import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Utility class for integrating with Vault Permission API to manage dynamic permissions
 */
public class PermissionManager {
    
    private final GPExpansionPlugin plugin;
    private Permission vaultPermission = null;
    private final Set<String> warnedRedundantAccrualGroups = new HashSet<>();
    
    public PermissionManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        setupVault();
        // Delay permission update until server is ready (avoids Purpur startup issues)
        // Run on next tick to ensure server is fully initialized
        SchedulerAdapter.runLaterGlobal(plugin, this::updatePlayerCommandPermissions, 1L);
        SchedulerAdapter.runLaterGlobal(plugin, this::validateAccrualGroups, 2L);
    }
    
    /**
     * Setup Vault permission provider
     */
    @SuppressWarnings("all")
    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().info("Vault not found - permission management via /gpx max will use in-memory limits only");
            return;
        }
        
        try {
            RegisteredServiceProvider<Permission> rsp = Bukkit.getServicesManager().getRegistration(Permission.class);
            if (rsp != null) {
                vaultPermission = rsp.getProvider();
                plugin.getLogger().info("Vault permission provider found: " + vaultPermission.getName());
            } else {
                plugin.getLogger().warning("Vault found but no permission provider registered");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to setup Vault permissions: " + e.getMessage());
        }
    }
    
    /**
     * Clean up sell sign permissions for a player
     */
    public boolean cleanupSellPermissions(Player player, int newLimit) {
        return cleanupPermissions(player, "griefprevention.sign.create.buy.", newLimit, "sell sign");
    }
    
    /**
     * Clean up rent sign permissions for a player
     */
    public boolean cleanupRentPermissions(Player player, int newLimit) {
        return cleanupPermissions(player, "griefprevention.sign.create.rent.", newLimit, "rent sign");
    }
    
    /**
     * Clean up mailbox sign permissions for a player
     */
    public boolean cleanupMailboxPermissions(Player player, int newLimit) {
        return cleanupPermissions(player, "griefprevention.sign.create.mailbox.", newLimit, "mailbox sign");
    }
    
    /**
     * Clean up global claim permissions for a player
     */
    public boolean cleanupGlobalClaimPermissions(Player player, int newLimit) {
        return cleanupPermissions(player, "griefprevention.claim.toggleglobal.", newLimit, "global claim");
    }
    
    /**
     * Generic permission cleanup using Vault API
     */
    private boolean cleanupPermissions(Player player, String permissionPrefix, int newLimit, String typeName) {
        if (vaultPermission == null) {
            plugin.getLogger().warning("Cannot cleanup permissions - Vault not available");
            return false;
        }
        
        try {
            // Find all existing numbered permissions with this prefix
            List<String> toRemove = new ArrayList<>();
            for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                String perm = info.getPermission();
                if (perm.startsWith(permissionPrefix) && info.getValue()) {
                    // Check if it ends with a number
                    String suffix = perm.substring(permissionPrefix.length());
                    try {
                        Integer.parseInt(suffix);
                        toRemove.add(perm);
                    } catch (NumberFormatException ignored) {
                        // Not a numbered permission, skip
                    }
                }
            }
            
            // Remove old permissions
            for (String perm : toRemove) {
                vaultPermission.playerRemove(null, player, perm);
            }
            
            // Add new permission
            String newPerm = permissionPrefix + newLimit;
            vaultPermission.playerAdd(null, player, newPerm);
            
            plugin.getLogger().info("Cleaned up " + typeName + " permissions for " + player.getName() + 
                " (removed " + toRemove.size() + " old permissions, set to " + newLimit + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to cleanup permissions via Vault: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if permission cleanup is supported (Vault available)
     */
    public boolean isCleanupSupported() {
        return vaultPermission != null;
    }
    
    /**
     * Get the name of the permission provider
     */
    public String getSupportedPlugin() {
        if (vaultPermission != null) {
            return "Vault (" + vaultPermission.getName() + ")";
        }
        return "None";
    }

    public AccrualProfile resolveAccrualProfile(Player player) {
        List<AccrualProfile> profiles = getAccrualProfiles();
        AccrualProfile selected = null;

        for (AccrualProfile profile : profiles) {
            if ("default".equalsIgnoreCase(profile.name)) {
                selected = profile;
                break;
            }
        }
        if (selected == null && !profiles.isEmpty()) {
            selected = profiles.get(0);
        }
        if (selected == null) {
            selected = new AccrualProfile("default", 100, 80000, 0, null,
                plugin.getMessages().getRaw("commands.accruals-source-fallback"));
        }

        for (AccrualProfile profile : profiles) {
            if ("default".equalsIgnoreCase(profile.name)) {
                continue;
            }
            if (matchesAccrualProfile(player, profile)) {
                selected = profile;
            }
        }

        AccrualOverride override = getPlayerAccrualOverride(player.getUniqueId());
        if (!override.isEmpty()) {
            selected = selected.withOverride(override,
                plugin.getMessages().getRaw("commands.accruals-source-player-override"));
        }

        return selected;
    }

    public AccrualOverride getPlayerAccrualOverride(UUID playerId) {
        String path = "accruals.overrides.players." + playerId;
        FileConfiguration config = plugin.getConfig();
        if (!config.isConfigurationSection(path)) {
            return AccrualOverride.empty();
        }
        return new AccrualOverride(
            optionalInt(config, path + ".blocks-per-hour"),
            optionalInt(config, path + ".max-blocks"),
            optionalInt(config, path + ".max-claims")
        );
    }

    public void setPlayerAccrualOverride(OfflinePlayer player, AccrualField field, int amount) {
        String path = "accruals.overrides.players." + player.getUniqueId() + "." + field.configKey;
        plugin.getConfig().set(path, amount);
        String name = player.getName();
        if (name != null && !name.isEmpty()) {
            plugin.getConfig().set("accruals.overrides.players." + player.getUniqueId() + ".last-known-name", name);
        }
        plugin.saveConfig();
    }

    public boolean resetPlayerAccrualOverride(OfflinePlayer player) {
        String path = "accruals.overrides.players." + player.getUniqueId();
        if (!plugin.getConfig().contains(path)) {
            return false;
        }
        plugin.getConfig().set(path, null);
        plugin.saveConfig();
        return true;
    }

    public boolean clearPlayerAccrualOverride(OfflinePlayer player, AccrualField field) {
        String path = "accruals.overrides.players." + player.getUniqueId() + "." + field.configKey;
        if (!plugin.getConfig().contains(path)) {
            return false;
        }
        plugin.getConfig().set(path, null);
        if (getPlayerAccrualOverride(player.getUniqueId()).isEmpty()) {
            plugin.getConfig().set("accruals.overrides.players." + player.getUniqueId(), null);
        }
        plugin.saveConfig();
        return true;
    }

    public boolean setAccrualGroupValue(String groupName, AccrualField field, int amount) {
        String path = findAccrualGroupPath(groupName);
        if (path == null) {
            return false;
        }
        plugin.getConfig().set(path + "." + field.configKey, amount);
        plugin.saveConfig();
        validateAccrualGroups();
        return true;
    }

    public boolean adjustAccrualGroupValue(String groupName, AccrualField field, int delta) {
        String path = findAccrualGroupPath(groupName);
        if (path == null) {
            return false;
        }
        int current = Math.max(0, plugin.getConfig().getInt(path + "." + field.configKey, 0));
        plugin.getConfig().set(path + "." + field.configKey, Math.max(0, current + delta));
        plugin.saveConfig();
        validateAccrualGroups();
        return true;
    }

    public Integer getAccrualGroupValue(String groupName, AccrualField field) {
        String path = findAccrualGroupPath(groupName);
        if (path == null) {
            return null;
        }
        return Math.max(0, plugin.getConfig().getInt(path + "." + field.configKey, 0));
    }

    public boolean createAccrualGroup(String groupName, int blocksPerHour, int maxBlocks, int maxClaims, String permission) {
        if (groupName == null || groupName.trim().isEmpty() || isAccrualGroup(groupName)) {
            return false;
        }

        List<Map<String, Object>> groups = getAccrualGroupMaps();
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("name", groupName.trim());
        group.put("blocks-per-hour", Math.max(0, blocksPerHour));
        group.put("max-blocks", Math.max(0, maxBlocks));
        group.put("max-claims", Math.max(0, maxClaims));
        if (permission != null && !permission.trim().isEmpty() && !"*".equals(permission.trim())) {
            group.put("permission", permission.trim());
        }

        groups.add(group);
        sortAccrualGroupsByLuckPermsWeight(groups);
        plugin.getConfig().set("accruals.groups", groups);
        plugin.saveConfig();
        validateAccrualGroups();
        return true;
    }

    public boolean deleteAccrualGroup(String groupName) {
        if (groupName == null) {
            return false;
        }

        List<Map<String, Object>> groups = getAccrualGroupMaps();
        boolean removed = groups.removeIf(group -> groupName.equalsIgnoreCase(String.valueOf(group.get("name"))));
        if (!removed) {
            return false;
        }

        plugin.getConfig().set("accruals.groups", groups);
        plugin.saveConfig();
        validateAccrualGroups();
        return true;
    }

    public int getInheritedAccrualValueBelow(String groupName, AccrualField field) {
        Integer targetWeight = getLuckPermsGroupWeight(groupName);
        if (targetWeight == null) {
            return 0;
        }

        int highest = 0;
        for (AccrualProfile profile : getAccrualProfiles()) {
            Integer weight = getLuckPermsGroupWeight(profile.name);
            if (weight != null && weight < targetWeight) {
                highest = Math.max(highest, profile.getValue(field));
            }
        }
        return highest;
    }

    public boolean hasLuckPermsGroup(String groupName) {
        return getLuckPermsGroup(groupName) != null;
    }

    public Integer getLuckPermsWeight(String groupName) {
        return getLuckPermsGroupWeight(groupName);
    }

    public boolean isAccrualGroup(String groupName) {
        return findAccrualGroupPath(groupName) != null;
    }

    public List<String> getAccrualGroupNames() {
        List<String> names = new ArrayList<>();
        for (AccrualProfile profile : getAccrualProfiles()) {
            names.add(profile.name);
        }
        return names;
    }

    public void validateAccrualGroups() {
        for (AccrualProfile profile : getAccrualProfiles()) {
            if ("default".equalsIgnoreCase(profile.name)) {
                continue;
            }
            if (hasVaultGroup(profile.name) || hasLuckPermsGroup(profile.name) || profile.permission != null) {
                continue;
            }
            String key = profile.name.toLowerCase(Locale.ROOT);
            if (warnedRedundantAccrualGroups.add(key)) {
                String message = plugin.getMessages().getRaw("commands.accruals-redundant-group-warning", "{group}", profile.name)
                    .replaceAll("&[0-9a-fA-Fk-oK-OrR]", "");
                plugin.getLogger().warning(message);
            }
        }
    }

    private boolean matchesAccrualProfile(Player player, AccrualProfile profile) {
        if (hasBaseAccrualPermission(player, profile.name)) {
            return true;
        }
        if (profile.permission != null && player.hasPermission("griefprevention.accruals." + profile.permission)) {
            return true;
        }
        return playerInVaultGroup(player, profile.name);
    }

    private boolean hasBaseAccrualPermission(Player player, String name) {
        return name != null
            && !name.isEmpty()
            && player.hasPermission("griefprevention.accruals." + name);
    }

    private boolean playerInVaultGroup(Player player, String groupName) {
        if (vaultPermission == null || groupName == null || groupName.isEmpty()) {
            return false;
        }

        try {
            World world = player.getWorld();
            String worldName = world != null ? world.getName() : null;
            return vaultPermission.playerInGroup(worldName, player, groupName)
                || vaultPermission.playerInGroup(null, player, groupName);
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean hasVaultGroup(String groupName) {
        if (vaultPermission == null || groupName == null || groupName.isEmpty()) {
            return false;
        }

        try {
            for (String group : vaultPermission.getGroups()) {
                if (groupName.equalsIgnoreCase(group)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            return false;
        }
        return false;
    }

    private List<AccrualProfile> getAccrualProfiles() {
        FileConfiguration config = plugin.getConfig();
        List<?> rawGroups = config.getList("accruals.groups");
        if (rawGroups == null || rawGroups.isEmpty()) {
            return Collections.emptyList();
        }

        List<AccrualProfile> profiles = new ArrayList<>();
        for (int i = 0; i < rawGroups.size(); i++) {
            String path = "accruals.groups." + i;
            ConfigurationSection section = config.getConfigurationSection(path);
            if (section == null) {
                continue;
            }

            String name = section.getString("name");
            if (name == null || name.trim().isEmpty()) {
                plugin.getLogger().warning(plugin.getMessages().getRaw("commands.accruals-invalid-group-entry-warning",
                    "{index}", String.valueOf(i)));
                continue;
            }

            String permission = section.getString("permission");
            if (permission != null) {
                permission = permission.trim();
                if (permission.isEmpty()) {
                    permission = null;
                }
            }

            profiles.add(new AccrualProfile(
                name.trim(),
                Math.max(0, section.getInt("blocks-per-hour", 0)),
                Math.max(0, section.getInt("max-blocks", 0)),
                Math.max(0, section.getInt("max-claims", 0)),
                permission,
                plugin.getMessages().getRaw("commands.accruals-source-group")
            ));
        }
        return profiles;
    }

    private String findAccrualGroupPath(String groupName) {
        if (groupName == null) {
            return null;
        }

        FileConfiguration config = plugin.getConfig();
        List<?> rawGroups = config.getList("accruals.groups");
        if (rawGroups == null) {
            return null;
        }

        for (int i = 0; i < rawGroups.size(); i++) {
            String path = "accruals.groups." + i;
            String name = config.getString(path + ".name");
            if (groupName.equalsIgnoreCase(name)) {
                return path;
            }
        }
        return null;
    }

    private List<Map<String, Object>> getAccrualGroupMaps() {
        FileConfiguration config = plugin.getConfig();
        List<?> rawGroups = config.getList("accruals.groups");
        if (rawGroups == null || rawGroups.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (int i = 0; i < rawGroups.size(); i++) {
            ConfigurationSection section = config.getConfigurationSection("accruals.groups." + i);
            if (section == null) {
                continue;
            }
            Map<String, Object> group = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                group.put(key, section.get(key));
            }
            groups.add(group);
        }
        return groups;
    }

    private void sortAccrualGroupsByLuckPermsWeight(List<Map<String, Object>> groups) {
        List<Map<String, Object>> unweighted = new ArrayList<>();
        List<Map<String, Object>> weighted = new ArrayList<>();

        for (Map<String, Object> group : groups) {
            Object name = group.get("name");
            if (name != null && getLuckPermsGroupWeight(String.valueOf(name)) != null) {
                weighted.add(group);
            } else {
                unweighted.add(group);
            }
        }

        weighted.sort((left, right) -> {
            Integer leftWeight = getLuckPermsGroupWeight(String.valueOf(left.get("name")));
            Integer rightWeight = getLuckPermsGroupWeight(String.valueOf(right.get("name")));
            return Integer.compare(leftWeight != null ? leftWeight : 0, rightWeight != null ? rightWeight : 0);
        });

        groups.clear();
        groups.addAll(unweighted);
        groups.addAll(weighted);
    }

    private Object getLuckPermsGroup(String groupName) {
        if (groupName == null || groupName.isEmpty()) {
            return null;
        }

        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            Object groupManager = luckPerms.getClass().getMethod("getGroupManager").invoke(luckPerms);
            return groupManager.getClass().getMethod("getGroup", String.class).invoke(groupManager, groupName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer getLuckPermsGroupWeight(String groupName) {
        Object group = getLuckPermsGroup(groupName);
        if (group == null) {
            return null;
        }

        try {
            Object optionalWeight = group.getClass().getMethod("getWeight").invoke(group);
            boolean present = (Boolean) optionalWeight.getClass().getMethod("isPresent").invoke(optionalWeight);
            if (!present) {
                return null;
            }
            return (Integer) optionalWeight.getClass().getMethod("getAsInt").invoke(optionalWeight);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer optionalInt(FileConfiguration config, String path) {
        return config.contains(path) ? config.getInt(path) : null;
    }
    
    /**
     * Update gpx.player permission children based on config.yml player-commands section
     * This dynamically adds/removes child permissions to gpx.player
     */
    public void updatePlayerCommandPermissions() {
        try {
            FileConfiguration config = plugin.getConfig();
            List<String> playerCommands = config.getStringList("player-commands");
            
            if (playerCommands.isEmpty()) {
                plugin.getLogger().warning("No player-commands found in config.yml, skipping permission update");
                return;
            }
            
            // Get or create gpx.player permission
            org.bukkit.permissions.Permission gpxPlayerPerm = Bukkit.getPluginManager().getPermission("gpx.player");
            if (gpxPlayerPerm == null) {
                // Create the permission if it doesn't exist
                gpxPlayerPerm = new org.bukkit.permissions.Permission("gpx.player", 
                    "Base permission for GPExpansion player commands (children permissions are dynamically managed)",
                    PermissionDefault.TRUE);
                Bukkit.getPluginManager().addPermission(gpxPlayerPerm);
                plugin.getLogger().info("Created gpx.player permission");
            }
            
            // Build list of full permission names (with griefprevention. prefix).
            // Tolerate malformed YAML where multiple perms ended up on the same list line
            // (e.g. "claim.toggleglobal.anywhere - claim.toggleglobal.1") by splitting
            // on whitespace/hyphens and treating each token as a separate permission.
            List<String> childPermissions = new ArrayList<>();
            for (String rawEntry : playerCommands) {
                if (rawEntry == null) continue;
                String trimmed = rawEntry.trim();
                if (trimmed.isEmpty()) continue;

                String[] tokens = trimmed.split("(?:\\s+-\\s+|\\s+)");
                if (tokens.length > 1) {
                    plugin.getLogger().warning("Malformed player-commands entry in config.yml: \""
                            + rawEntry + "\" -> splitting into " + tokens.length
                            + " permissions. Please put each permission on its own '- ' YAML list line.");
                }
                for (String token : tokens) {
                    String perm = token.trim();
                    if (perm.isEmpty()) continue;
                    String fullPerm = "griefprevention." + perm;
                    childPermissions.add(fullPerm);

                    // Ensure the child permission exists
                    org.bukkit.permissions.Permission childPerm = Bukkit.getPluginManager().getPermission(fullPerm);
                    if (childPerm == null) {
                        plugin.getLogger().warning("Permission " + fullPerm + " not found in plugin.yml, skipping");
                    }
                }
            }
            
            // Update children - need to recreate permission with new children map
            java.util.Map<String, Boolean> newChildren = new java.util.HashMap<>();
            
            // Keep non-griefprevention children
            for (java.util.Map.Entry<String, Boolean> entry : gpxPlayerPerm.getChildren().entrySet()) {
                if (!entry.getKey().startsWith("griefprevention.")) {
                    newChildren.put(entry.getKey(), entry.getValue());
                }
            }
            
            // Add all child permissions from config
            for (String child : childPermissions) {
                org.bukkit.permissions.Permission childPerm = Bukkit.getPluginManager().getPermission(child);
                if (childPerm != null) {
                    newChildren.put(child, true);
                } else {
                    plugin.getLogger().warning("Permission " + child + " not found in plugin.yml, skipping");
                }
            }
            
            // Calculate changes for logging (before removing permission)
            long oldGriefPreventionChildren = gpxPlayerPerm.getChildren().keySet().stream()
                .filter(k -> k.startsWith("griefprevention.")).count();
            long newGriefPreventionChildren = newChildren.keySet().stream()
                .filter(k -> k.startsWith("griefprevention.")).count();
            
            int added = (int) (newGriefPreventionChildren - oldGriefPreventionChildren);
            int removed = (int) (oldGriefPreventionChildren - newGriefPreventionChildren);
            
            // Recreate permission with updated children
            Bukkit.getPluginManager().removePermission(gpxPlayerPerm);
            org.bukkit.permissions.Permission newGpxPlayerPerm = new org.bukkit.permissions.Permission(
                "gpx.player",
                "Base permission for GPExpansion player commands (children permissions are dynamically managed)",
                PermissionDefault.TRUE,
                newChildren
            );
            Bukkit.getPluginManager().addPermission(newGpxPlayerPerm);
            
            if (added > 0 || removed > 0) {
                plugin.getLogger().info("Updated gpx.player permission: added " + added + 
                    " children, removed " + removed + " children");
            } else {
                plugin.getLogger().fine("gpx.player permission children are up to date");
            }
        } catch (Exception e) {
            // Don't let permission update failures block server startup (especially on Purpur)
            plugin.getLogger().warning("Failed to update player command permissions (non-critical): " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Reload permission manager (called on /gpx reload)
     */
    public void reload() {
        warnedRedundantAccrualGroups.clear();
        updatePlayerCommandPermissions();
        validateAccrualGroups();
    }

    public enum AccrualField {
        PER_HOUR("per-hour", "blocks-per-hour"),
        MAX_BLOCKS("max-blocks", "max-blocks"),
        MAX_CLAIMS("max-claims", "max-claims");

        private final String commandName;
        private final String configKey;

        AccrualField(String commandName, String configKey) {
            this.commandName = commandName;
            this.configKey = configKey;
        }

        public String getCommandName() {
            return commandName;
        }

        public static AccrualField fromCommandName(String value) {
            for (AccrualField field : values()) {
                if (field.commandName.equalsIgnoreCase(value)) {
                    return field;
                }
            }
            return null;
        }
    }

    public static final class AccrualProfile {
        private final String name;
        private final int blocksPerHour;
        private final int maxBlocks;
        private final int maxClaims;
        private final String permission;
        private final String source;

        private AccrualProfile(String name, int blocksPerHour, int maxBlocks, int maxClaims, String permission, String source) {
            this.name = name;
            this.blocksPerHour = blocksPerHour;
            this.maxBlocks = maxBlocks;
            this.maxClaims = maxClaims;
            this.permission = permission;
            this.source = source;
        }

        private AccrualProfile withOverride(AccrualOverride override, String source) {
            return new AccrualProfile(
                name,
                override.blocksPerHour != null ? Math.max(0, override.blocksPerHour) : blocksPerHour,
                override.maxBlocks != null ? Math.max(0, override.maxBlocks) : maxBlocks,
                override.maxClaims != null ? Math.max(0, override.maxClaims) : maxClaims,
                permission,
                source
            );
        }

        public String getName() {
            return name;
        }

        public int getBlocksPerHour() {
            return blocksPerHour;
        }

        public int getMaxBlocks() {
            return maxBlocks;
        }

        public int getMaxClaims() {
            return maxClaims;
        }

        private int getValue(AccrualField field) {
            switch (field) {
                case PER_HOUR:
                    return blocksPerHour;
                case MAX_BLOCKS:
                    return maxBlocks;
                case MAX_CLAIMS:
                    return maxClaims;
                default:
                    return 0;
            }
        }

        public String getPermission() {
            return permission;
        }

        public String getSource() {
            return source;
        }
    }

    public static final class AccrualOverride {
        private final Integer blocksPerHour;
        private final Integer maxBlocks;
        private final Integer maxClaims;

        private AccrualOverride(Integer blocksPerHour, Integer maxBlocks, Integer maxClaims) {
            this.blocksPerHour = blocksPerHour;
            this.maxBlocks = maxBlocks;
            this.maxClaims = maxClaims;
        }

        private static AccrualOverride empty() {
            return new AccrualOverride(null, null, null);
        }

        public boolean isEmpty() {
            return blocksPerHour == null && maxBlocks == null && maxClaims == null;
        }

        public Integer getBlocksPerHour() {
            return blocksPerHour;
        }

        public Integer getMaxBlocks() {
            return maxBlocks;
        }

        public Integer getMaxClaims() {
            return maxClaims;
        }
    }
}
