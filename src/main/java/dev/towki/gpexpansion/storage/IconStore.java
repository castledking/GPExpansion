package dev.towki.gpexpansion.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Stores claim icons (material names) mapped by claim ID.
 */
public class IconStore {
    
    private final File file;
    private FileConfiguration config;
    private final Map<String, String> icons = new HashMap<>();
    
    public IconStore(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "icons.yml");
        load();
    }
    
    public void load() {
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        config = YamlConfiguration.loadConfiguration(file);
        icons.clear();
        
        for (String key : config.getKeys(false)) {
            String value = config.getString(key);
            if (value != null) {
                icons.put(key, value);
            }
        }
    }
    
    public void save() {
        for (Map.Entry<String, String> entry : icons.entrySet()) {
            config.set(entry.getKey(), entry.getValue());
        }
        
        // Remove deleted entries
        for (String key : config.getKeys(false)) {
            if (!icons.containsKey(key)) {
                config.set(key, null);
            }
        }
        
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public Optional<String> get(String claimId) {
        return Optional.ofNullable(icons.get(claimId));
    }
    
    public void set(String claimId, String materialName) {
        icons.put(claimId, materialName);
    }
    
    public void remove(String claimId) {
        icons.remove(claimId);
    }
    
    public Map<String, String> all() {
        return new HashMap<>(icons);
    }
}
