package codes.castled.gpexpansion.claimfly;

import codes.castled.gpexpansion.GPExpansionPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClaimFlyManager {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration data;
    private final ConcurrentHashMap<UUID, Long> remainingMillis = new ConcurrentHashMap<>();
    private final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();

    public ClaimFlyManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "claim-flight.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Could not create claim-flight.yml: " + e.getMessage());
            }
        }

        data = YamlConfiguration.loadConfiguration(file);
        remainingMillis.clear();
        enabledPlayers.clear();

        if (data.isConfigurationSection("players")) {
            for (String key : data.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    long remaining = Math.max(0L, data.getLong("players." + key + ".remaining-millis", 0L));
                    boolean enabled = data.getBoolean("players." + key + ".enabled", true);
                    if (data.contains("players." + key + ".remaining-millis")) {
                        remainingMillis.put(uuid, remaining);
                    }
                    if (enabled) {
                        enabledPlayers.add(uuid);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public void save() {
        for (String key : new HashSet<>(data.getKeys(false))) {
            if (key.equals("players")) {
                data.set(key, null);
            }
        }

        Set<UUID> uuids = new HashSet<>();
        uuids.addAll(remainingMillis.keySet());
        uuids.addAll(enabledPlayers);

        for (UUID uuid : uuids) {
            String path = "players." + uuid;
            data.set(path + ".remaining-millis", getRemainingMillis(uuid));
            data.set(path + ".enabled", enabledPlayers.contains(uuid));
        }

        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save claim-flight.yml: " + e.getMessage());
        }
    }

    public long getRemainingMillis(UUID uuid) {
        return capMillis(Math.max(0L, remainingMillis.getOrDefault(uuid, 0L)));
    }

    public boolean hasTime(UUID uuid) {
        return ensureDefaultTime(uuid) > 0L;
    }

    public boolean isPassiveClaimFlightEnabled() {
        return plugin.getConfigManager().isPassiveClaimFlightEnabled();
    }

    public boolean isClaimFlightEnabled() {
        return plugin.getConfigManager().isClaimFlightEnabled();
    }

    public boolean hasFlightAccess(UUID uuid) {
        return isPassiveClaimFlightEnabled() || hasTime(uuid);
    }

    public boolean isEnabled(UUID uuid) {
        return !plugin.getConfigManager().isClaimFlightToggleRequired() || enabledPlayers.contains(uuid);
    }

    public boolean canUseClaimFlight(Player player) {
        UUID uuid = player.getUniqueId();
        return isClaimFlightEnabled()
            && player.hasPermission("griefprevention.claimfly.use")
            && isEnabled(uuid)
            && hasFlightAccess(uuid);
    }

    public boolean toggle(UUID uuid) {
        if (enabledPlayers.contains(uuid)) {
            enabledPlayers.remove(uuid);
            save();
            return false;
        }
        enabledPlayers.add(uuid);
        save();
        return true;
    }

    public void setEnabled(UUID uuid, boolean enabled) {
        if (enabled) {
            enabledPlayers.add(uuid);
        } else {
            enabledPlayers.remove(uuid);
        }
        save();
    }

    public void addTime(UUID uuid, long millis) {
        if (millis <= 0L) return;
        remainingMillis.put(uuid, capMillis(getRemainingMillis(uuid) + millis));
        enabledPlayers.add(uuid);
        save();
    }

    public void takeTime(UUID uuid, long millis) {
        if (millis <= 0L) return;
        setTime(uuid, Math.max(0L, getRemainingMillis(uuid) - millis));
    }

    public void setTime(UUID uuid, long millis) {
        long normalized = capMillis(Math.max(0L, millis));
        remainingMillis.put(uuid, normalized);
        if (normalized > 0L) {
            enabledPlayers.add(uuid);
        }
        save();
    }

    public void reset(UUID uuid) {
        remainingMillis.remove(uuid);
        enabledPlayers.remove(uuid);
        save();
    }

    public long consume(UUID uuid, long millis) {
        if (isPassiveClaimFlightEnabled()) return getRemainingMillis(uuid);
        if (millis <= 0L) return getRemainingMillis(uuid);
        long remaining = capMillis(Math.max(0L, ensureDefaultTime(uuid) - millis));
        remainingMillis.put(uuid, remaining);
        save();
        return remaining;
    }

    public boolean shouldConsumeFlightTime(Player player) {
        return !isPassiveClaimFlightEnabled()
            && (plugin.getConfigManager().isClaimFlightTimeConsumedInCreative()
                || isSurvivalLike(player))
            && (plugin.getConfigManager().isClaimFlightTimeConsumedWhileHovering() || player.isFlying());
    }

    private long capMillis(long millis) {
        long max = plugin.getConfigManager().getClaimFlightMaxMillis();
        return max > 0L ? Math.min(millis, max) : millis;
    }

    private long ensureDefaultTime(UUID uuid) {
        Long stored = remainingMillis.get(uuid);
        if (stored != null) {
            return capMillis(Math.max(0L, stored));
        }
        long defaultMillis = capMillis(plugin.getConfigManager().getClaimFlightDefaultMillis());
        if (defaultMillis > 0L) {
            remainingMillis.put(uuid, defaultMillis);
            save();
        }
        return defaultMillis;
    }

    private boolean isSurvivalLike(Player player) {
        return switch (player.getGameMode()) {
            case SURVIVAL, ADVENTURE -> true;
            default -> false;
        };
    }

    public static String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder out = new StringBuilder();
        if (hours > 0L) out.append(hours).append("h");
        if (minutes > 0L) {
            if (out.length() > 0) out.append(' ');
            out.append(minutes).append("m");
        }
        if (seconds > 0L || out.length() == 0) {
            if (out.length() > 0) out.append(' ');
            out.append(seconds).append("s");
        }
        return out.toString();
    }
}
