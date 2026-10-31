package codes.castled.gpexpansion.flags;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.util.Mini;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves built-in claim flags: a claim's own value, then its parent claim's, then the server
 * default from {@code claim-flags.defaults}. Also decides who counts as an outsider.
 */
public final class ClaimFlagManager {

    public static final String BYPASS_PERMISSION = "griefprevention.claim.flags.bypass";
    private static final long DENY_MESSAGE_COOLDOWN_MS = 2000L;

    private final GPExpansionPlugin plugin;
    private final ClaimFlagStore store;

    private volatile boolean enabled = true;
    private volatile Map<ClaimFlag, Boolean> defaults = new EnumMap<>(ClaimFlag.class);
    private volatile Set<ClaimFlag> disabled = EnumSet.noneOf(ClaimFlag.class);
    private volatile Set<String> ignoredSpawnReasons = Set.of();
    private volatile String denyMessageMode = "actionbar";
    private final Map<UUID, Long> lastDenyMessage = new ConcurrentHashMap<>();
    private volatile ClaimPermission containerPermission;

    public ClaimFlagManager(GPExpansionPlugin plugin, ClaimFlagStore store) {
        this.plugin = plugin;
        this.store = store;
        reloadSettings();
        registerPermissions();
    }

    public ClaimFlagStore store() {
        return store;
    }

    public void reloadSettings() {
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfigManager().getConfig();
        enabled = config.getBoolean("claim-flags.enabled", true);

        Map<ClaimFlag, Boolean> parsedDefaults = new EnumMap<>(ClaimFlag.class);
        ConfigurationSection section = config.getConfigurationSection("claim-flags.defaults");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ClaimFlag flag = ClaimFlag.fromId(key);
                if (flag == null || flag.isFilter()) {
                    if (flag == null) plugin.getLogger().warning("Unknown flag in claim-flags.defaults: " + key);
                    continue;
                }
                String raw = String.valueOf(section.get(key)).trim().toLowerCase(Locale.ROOT);
                switch (raw) {
                    case "true", "allow", "allowed", "on" -> parsedDefaults.put(flag, true);
                    case "false", "deny", "denied", "off" -> parsedDefaults.put(flag, false);
                    default -> { /* unset */ }
                }
            }
        }
        defaults = parsedDefaults;

        Set<ClaimFlag> parsedDisabled = EnumSet.noneOf(ClaimFlag.class);
        for (String id : config.getStringList("claim-flags.disabled-flags")) {
            ClaimFlag flag = ClaimFlag.fromId(id);
            if (flag != null) parsedDisabled.add(flag);
        }
        disabled = parsedDisabled;

        java.util.Set<String> reasons = new java.util.HashSet<>();
        for (String reason : config.getStringList("claim-flags.spawn-flags-ignore-reasons")) {
            reasons.add(reason.trim().toUpperCase(Locale.ROOT));
        }
        ignoredSpawnReasons = reasons;
        denyMessageMode = config.getString("claim-flags.deny-message", "actionbar").toLowerCase(Locale.ROOT);
    }

    /** Registers griefprevention.claim.flag.<id> (default true) so each flag can be withheld. */
    private void registerPermissions() {
        PluginManager pm = plugin.getServer().getPluginManager();
        Map<String, Boolean> children = new HashMap<>();
        for (ClaimFlag flag : ClaimFlag.values()) {
            children.put(flag.permission(), true);
            if (pm.getPermission(flag.permission()) == null) {
                pm.addPermission(new Permission(flag.permission(),
                    "Allows changing the " + flag.displayName() + " claim flag", PermissionDefault.TRUE));
            }
        }
        if (pm.getPermission("griefprevention.claim.flag.*") == null) {
            pm.addPermission(new Permission("griefprevention.claim.flag.*",
                "Allows changing every built-in claim flag", PermissionDefault.TRUE, children));
        }
    }

    /** The built-in flag system is switched on. */
    public boolean isEnabled() {
        return enabled;
    }

    /** The flag exists on this server (system on, flag not listed in claim-flags.disabled-flags). */
    public boolean isAvailable(ClaimFlag flag) {
        return enabled && !disabled.contains(flag);
    }

    /** Fast path for hot events: false when no claim sets the flag and there's no default. */
    public boolean isActive(ClaimFlag flag) {
        return isAvailable(flag) && (store.isUsed(flag) || defaults.containsKey(flag));
    }

    public boolean isSpawnReasonIgnored(String reason) {
        return reason != null && ignoredSpawnReasons.contains(reason);
    }

    public Boolean serverDefault(ClaimFlag flag) {
        return defaults.get(flag);
    }

    /** The most specific claim at a location (subdivisions win), or null in the wilderness. */
    public Claim claimAt(Location location) {
        if (location == null || location.getWorld() == null) return null;
        GriefPrevention gp = GriefPrevention.instance;
        if (gp == null || gp.dataStore == null) return null;
        try {
            return gp.dataStore.getClaimAt(location, false, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String claimId(Claim claim) {
        Long id = claim == null ? null : claim.getID();
        return id == null ? null : String.valueOf(id);
    }

    /** The claim's effective value: its own, a parent's, or the server default; null when none. */
    public Boolean resolve(ClaimFlag flag, Claim claim) {
        Claim current = claim;
        int depth = 0;
        while (current != null && depth++ < 16) {
            Boolean value = store.get(claimId(current), flag);
            if (value != null) return value;
            current = current.parent;
        }
        return defaults.get(flag);
    }

    /** Where the effective value comes from: "claim", "parent", "default" or null. */
    public String resolveSource(ClaimFlag flag, Claim claim) {
        if (claim != null && (flag.isFilter() ? store.getFilter(claimId(claim), flag) != null : store.get(claimId(claim), flag) != null)) {
            return "claim";
        }
        Claim parent = claim == null ? null : claim.parent;
        int depth = 0;
        while (parent != null && depth++ < 16) {
            if (flag.isFilter() ? store.getFilter(claimId(parent), flag) != null : store.get(claimId(parent), flag) != null) {
                return "parent";
            }
            parent = parent.parent;
        }
        return !flag.isFilter() && defaults.containsKey(flag) ? "default" : null;
    }

    public ClaimFlagStore.FilterSetting resolveFilter(ClaimFlag flag, Claim claim) {
        Claim current = claim;
        int depth = 0;
        while (current != null && depth++ < 16) {
            ClaimFlagStore.FilterSetting setting = store.getFilter(claimId(current), flag);
            if (setting != null) return setting;
            current = current.parent;
        }
        return null;
    }

    /** True when the flag resolves to Deny at the claim. */
    public boolean isDenied(ClaimFlag flag, Claim claim) {
        if (claim == null) return false;
        Boolean value = resolve(flag, claim);
        return value != null && !value;
    }

    /**
     * Whether a player counts as an outsider for a flag's trust level: not the owner, without
     * the bypass permission and without that trust (or better) in the claim.
     */
    public boolean isOutsider(Player player, Claim claim, ClaimFlag.Trust trust) {
        if (player == null) return true;
        if (player.hasPermission(BYPASS_PERMISSION)) return false;
        if (trust == ClaimFlag.Trust.NONE) return true;
        UUID owner = claim.getOwnerID();
        if (owner != null && owner.equals(player.getUniqueId())) return false;
        ClaimPermission permission = switch (trust) {
            case BUILD -> ClaimPermission.Build;
            case CONTAINER -> containerPermission();
            default -> ClaimPermission.Access;
        };
        try {
            return claim.checkPermission(player, permission, null) != null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** GP3D calls container trust "Container"; upstream GriefPrevention calls it "Inventory". */
    private ClaimPermission containerPermission() {
        ClaimPermission resolved = containerPermission;
        if (resolved == null) {
            try {
                resolved = ClaimPermission.valueOf("Container");
            } catch (IllegalArgumentException e) {
                resolved = ClaimPermission.valueOf("Inventory");
            }
            containerPermission = resolved;
        }
        return resolved;
    }

    /** Map GriefPrevention's required permission to the matching trust level, or null for Edit/Manage. */
    public static ClaimFlag.Trust trustFor(ClaimPermission permission) {
        if (permission == null) return null;
        return switch (permission.name()) {
            case "Build" -> ClaimFlag.Trust.BUILD;
            case "Container", "Inventory" -> ClaimFlag.Trust.CONTAINER;
            case "Access" -> ClaimFlag.Trust.ACCESS;
            default -> null;
        };
    }

    /** Tell a player a flag stopped them, at most every couple of seconds. */
    public void notifyDenied(Player player, ClaimFlag flag) {
        if (player == null || "none".equals(denyMessageMode)) return;
        long now = System.currentTimeMillis();
        Long last = lastDenyMessage.get(player.getUniqueId());
        if (last != null && now - last < DENY_MESSAGE_COOLDOWN_MS) return;
        lastDenyMessage.put(player.getUniqueId(), now);
        String raw = plugin.getMessages().getRaw("flags.denied", "{flag}", displayName(flag));
        if ("chat".equals(denyMessageMode)) {
            Mini.send(player, LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
            return;
        }
        String legacy = Mini.toSection(raw);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(legacy));
    }

    public void forget(UUID playerId) {
        lastDenyMessage.remove(playerId);
    }

    /** Display name from guis/flags.yml (flags.<id>.name), falling back to the built-in one. */
    public String displayName(ClaimFlag flag) {
        org.bukkit.configuration.file.FileConfiguration gui = plugin.getGUIManager() != null
            ? plugin.getGUIManager().getGUIConfig("flags") : null;
        String name = gui != null ? gui.getString("flags." + flag.id() + ".name") : null;
        return name != null && !name.isBlank() ? name : flag.displayName();
    }

    public List<ClaimFlag> availableFlags(ClaimFlag.Category category) {
        return ClaimFlag.byCategory(category).stream().filter(this::isAvailable).toList();
    }
}
