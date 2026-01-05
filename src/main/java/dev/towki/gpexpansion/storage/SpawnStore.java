package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores custom teleport spawn points for claims.
 * Format: claimId -> "world,x,y,z,yaw,pitch"
 */
public class SpawnStore {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration config;

    private final Map<String, Location> spawns = new HashMap<>();

    public SpawnStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spawns.yml");
    }

    public void load() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed creating spawns.yml: " + e.getMessage());
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        spawns.clear();
        if (config.isConfigurationSection("spawns")) {
            for (String claimId : config.getConfigurationSection("spawns").getKeys(false)) {
                String val = config.getString("spawns." + claimId);
                if (val != null) {
                    Location loc = deserializeLocation(val);
                    if (loc != null) {
                        spawns.put(claimId, loc);
                    }
                }
            }
        }
        plugin.getLogger().info("Loaded " + spawns.size() + " claim spawn points.");
    }

    public void save() {
        if (config == null) config = new YamlConfiguration();
        config.set("spawns", null); // reset section
        for (Map.Entry<String, Location> e : spawns.entrySet()) {
            config.set("spawns." + e.getKey(), serializeLocation(e.getValue()));
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed saving spawns.yml: " + e.getMessage());
        }
    }

    public Optional<Location> get(String claimId) {
        return Optional.ofNullable(spawns.get(claimId));
    }

    public void set(String claimId, Location location) {
        if (location == null) {
            spawns.remove(claimId);
        } else {
            spawns.put(claimId, location.clone());
        }
    }

    public void remove(String claimId) {
        spawns.remove(claimId);
    }

    private String serializeLocation(Location loc) {
        return loc.getWorld().getName() + "," +
               loc.getX() + "," +
               loc.getY() + "," +
               loc.getZ() + "," +
               loc.getYaw() + "," +
               loc.getPitch();
    }

    private Location deserializeLocation(String str) {
        try {
            String[] parts = str.split(",");
            if (parts.length < 4) return null;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0f;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (Exception e) {
            return null;
        }
    }
}
