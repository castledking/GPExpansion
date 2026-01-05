package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stores additional claim data like public listing status, icons, and descriptions.
 */
public class ClaimDataStore {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration config;
    
    // In-memory cache
    private final Map<String, ClaimData> claimData = new HashMap<>();
    
    public static class ClaimData {
        public boolean publicListed = false;
        public Material icon = null; // null means default (GRASS_BLOCK or based on type)
        public String description = null; // max 32 chars
        
        public ClaimData() {}
        
        public ClaimData(boolean publicListed, Material icon, String description) {
            this.publicListed = publicListed;
            this.icon = icon;
            this.description = description;
        }
    }
    
    public ClaimDataStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "claimdata.yml");
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
            plugin.getLogger().warning("Failed creating claimdata.yml: " + e.getMessage());
        }
        
        config = YamlConfiguration.loadConfiguration(file);
        claimData.clear();
        
        if (config.isConfigurationSection("claims")) {
            for (String claimId : config.getConfigurationSection("claims").getKeys(false)) {
                String path = "claims." + claimId + ".";
                ClaimData data = new ClaimData();
                data.publicListed = config.getBoolean(path + "public", false);
                
                String iconName = config.getString(path + "icon");
                if (iconName != null) {
                    data.icon = Material.matchMaterial(iconName);
                }
                
                data.description = config.getString(path + "description");
                if (data.description != null && data.description.length() > 32) {
                    data.description = data.description.substring(0, 32);
                }
                
                claimData.put(claimId, data);
            }
        }
        
        plugin.getLogger().info("Loaded " + claimData.size() + " claim data entries.");
    }
    
    public void save() {
        if (config == null) config = new YamlConfiguration();
        config.set("claims", null);
        
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            String path = "claims." + entry.getKey() + ".";
            ClaimData data = entry.getValue();
            
            config.set(path + "public", data.publicListed);
            if (data.icon != null) {
                config.set(path + "icon", data.icon.name());
            }
            if (data.description != null) {
                config.set(path + "description", data.description);
            }
        }
        
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed saving claimdata.yml: " + e.getMessage());
        }
    }
    
    public ClaimData get(String claimId) {
        return claimData.computeIfAbsent(claimId, k -> new ClaimData());
    }
    
    public void set(String claimId, ClaimData data) {
        claimData.put(claimId, data);
    }
    
    public boolean isPublicListed(String claimId) {
        ClaimData data = claimData.get(claimId);
        return data != null && data.publicListed;
    }
    
    public void setPublicListed(String claimId, boolean listed) {
        get(claimId).publicListed = listed;
    }
    
    public Optional<Material> getIcon(String claimId) {
        ClaimData data = claimData.get(claimId);
        return data != null ? Optional.ofNullable(data.icon) : Optional.empty();
    }
    
    public void setIcon(String claimId, Material icon) {
        get(claimId).icon = icon;
    }
    
    public Optional<String> getDescription(String claimId) {
        ClaimData data = claimData.get(claimId);
        return data != null ? Optional.ofNullable(data.description) : Optional.empty();
    }
    
    public void setDescription(String claimId, String description) {
        if (description != null && description.length() > 32) {
            description = description.substring(0, 32);
        }
        get(claimId).description = description;
    }
    
    /**
     * Get all publicly listed claim IDs.
     */
    public Set<String> getPublicListedClaims() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            if (entry.getValue().publicListed) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
    
    public void remove(String claimId) {
        claimData.remove(claimId);
    }
    
    /**
     * Count how many global (public listed) claims a player owns.
     * Uses GPBridge to check claim ownership.
     */
    public int countGlobalClaimsForPlayer(UUID playerId) {
        if (playerId == null) return 0;
        
        dev.towki.gpexpansion.gp.GPBridge gp = new dev.towki.gpexpansion.gp.GPBridge();
        int count = 0;
        
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            if (entry.getValue().publicListed) {
                String claimId = entry.getKey();
                // Check if this claim is owned by the player
                java.util.Optional<Object> claimOpt = gp.findClaimById(claimId);
                if (claimOpt.isPresent()) {
                    UUID ownerId = gp.getClaimOwner(claimOpt.get());
                    if (playerId.equals(ownerId)) {
                        count++;
                    }
                }
            }
        }
        
        return count;
    }
}
