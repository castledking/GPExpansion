package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class NameStore {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration config;

    private final Map<String, String> names = new HashMap<>();

    public NameStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "names.yml");
    }

    public void load() {
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            if (!file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed creating names.yml: " + e.getMessage());
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        names.clear();
        if (config.isConfigurationSection("names")) {
            for (String key : config.getConfigurationSection("names").getKeys(false)) {
                String val = config.getString("names." + key);
                if (val != null) names.put(key, val);
            }
        }
    }

    public void save() {
        if (config == null) config = new YamlConfiguration();
        config.set("names", null); // reset section
        for (Map.Entry<String, String> e : names.entrySet()) {
            config.set("names." + e.getKey(), e.getValue());
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed saving names.yml: " + e.getMessage());
        }
    }

    public Optional<String> get(String claimId) {
        return Optional.ofNullable(names.get(claimId));
    }

    public void set(String claimId, String visual) {
        if (visual == null || visual.isEmpty()) {
            names.remove(claimId);
        } else {
            names.put(claimId, visual);
        }
    }

    public void remove(String claimId) {
        names.remove(claimId);
    }
}
