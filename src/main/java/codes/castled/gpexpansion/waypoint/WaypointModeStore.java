package codes.castled.gpexpansion.waypoint;

import codes.castled.gpexpansion.GPExpansionPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player vanilla locator-bar mode, persisted to {@code waypoint-modes.yml}.
 *
 * <p>Follows the {@code claim-flight.yml} pattern: an in-memory map flushed on write. A missing
 * or unknown stored value falls back to {@link WaypointBarMode#BOTH}, so old data can never
 * accidentally blank a player's bar.
 */
public final class WaypointModeStore {

    private final GPExpansionPlugin plugin;
    private final File file;
    private final Map<UUID, WaypointBarMode> modes = new ConcurrentHashMap<>();

    public WaypointModeStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "waypoint-modes.yml");
        load();
    }

    public void load() {
        modes.clear();
        if (!file.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        for (String key : data.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            WaypointBarMode mode = WaypointBarMode.fromString(data.getString(key));
            if (mode != null) modes.put(uuid, mode);
        }
    }

    public void save() {
        try {
            YamlConfiguration data = new YamlConfiguration();
            for (Map.Entry<UUID, WaypointBarMode> entry : modes.entrySet()) {
                data.set(entry.getKey().toString(), entry.getValue().name().toLowerCase(java.util.Locale.ROOT));
            }
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save waypoint-modes.yml: " + e.getMessage());
        }
    }

    public WaypointBarMode get(UUID playerId) {
        return modes.getOrDefault(playerId, WaypointBarMode.BOTH);
    }

    public void set(UUID playerId, WaypointBarMode mode) {
        modes.put(playerId, mode);
        save();
    }
}