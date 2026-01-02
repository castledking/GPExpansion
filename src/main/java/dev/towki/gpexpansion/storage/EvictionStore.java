package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores pending evictions for rented claims.
 * When an owner initiates eviction via /claim evict, the renter has 14 days notice
 * before they can be removed from the claim.
 */
public class EvictionStore {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration conf;

    // 14 days in milliseconds
    public static final long EVICTION_NOTICE_PERIOD = 14L * 24 * 60 * 60 * 1000;

    // in-memory cache: claimId -> eviction entry
    private final Map<String, EvictionEntry> evictions = new HashMap<>();

    public static class EvictionEntry {
        public UUID ownerId;       // The claim owner who initiated eviction
        public UUID renterId;      // The renter being evicted
        public long initiatedAt;   // When eviction was initiated
        public long effectiveAt;   // When eviction becomes effective (initiatedAt + 14 days)
        
        public EvictionEntry(UUID ownerId, UUID renterId, long initiatedAt) {
            this.ownerId = ownerId;
            this.renterId = renterId;
            this.initiatedAt = initiatedAt;
            this.effectiveAt = initiatedAt + EVICTION_NOTICE_PERIOD;
        }
        
        public EvictionEntry(UUID ownerId, UUID renterId, long initiatedAt, long effectiveAt) {
            this.ownerId = ownerId;
            this.renterId = renterId;
            this.initiatedAt = initiatedAt;
            this.effectiveAt = effectiveAt;
        }
        
        public boolean isEffective() {
            return System.currentTimeMillis() >= effectiveAt;
        }
        
        public long getRemainingTime() {
            return Math.max(0, effectiveAt - System.currentTimeMillis());
        }
    }

    public EvictionStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "evictions.yml");
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        conf = YamlConfiguration.loadConfiguration(file);
        evictions.clear();
        if (conf.isConfigurationSection("evictions")) {
            for (String claimId : conf.getConfigurationSection("evictions").getKeys(false)) {
                String path = "evictions." + claimId + ".";
                String ownerStr = conf.getString(path + "owner");
                String renterStr = conf.getString(path + "renter");
                long initiatedAt = conf.getLong(path + "initiatedAt", 0L);
                long effectiveAt = conf.getLong(path + "effectiveAt", 0L);
                try {
                    if (ownerStr != null && renterStr != null) {
                        UUID owner = UUID.fromString(ownerStr);
                        UUID renter = UUID.fromString(renterStr);
                        evictions.put(claimId, new EvictionEntry(owner, renter, initiatedAt, effectiveAt));
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void save() {
        if (conf == null) conf = new YamlConfiguration();
        conf.set("evictions", null);
        for (Map.Entry<String, EvictionEntry> e : evictions.entrySet()) {
            String base = "evictions." + e.getKey() + ".";
            conf.set(base + "owner", e.getValue().ownerId.toString());
            conf.set(base + "renter", e.getValue().renterId.toString());
            conf.set(base + "initiatedAt", e.getValue().initiatedAt);
            conf.set(base + "effectiveAt", e.getValue().effectiveAt);
        }
        try {
            conf.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save evictions.yml: " + e.getMessage());
        }
    }

    /**
     * Initiate an eviction for a claim. The eviction becomes effective after 14 days.
     */
    public void initiateEviction(String claimId, UUID ownerId, UUID renterId) {
        evictions.put(claimId, new EvictionEntry(ownerId, renterId, System.currentTimeMillis()));
        save();
    }

    /**
     * Cancel a pending eviction.
     */
    public void cancelEviction(String claimId) {
        evictions.remove(claimId);
        save();
    }

    /**
     * Check if a claim has a pending eviction.
     */
    public boolean hasPendingEviction(String claimId) {
        return evictions.containsKey(claimId);
    }

    /**
     * Get the eviction entry for a claim, or null if none exists.
     */
    public EvictionEntry getEviction(String claimId) {
        return evictions.get(claimId);
    }

    /**
     * Check if an eviction is now effective (14 days have passed).
     */
    public boolean isEvictionEffective(String claimId) {
        EvictionEntry entry = evictions.get(claimId);
        return entry != null && entry.isEffective();
    }

    /**
     * Get all pending evictions.
     */
    public Map<String, EvictionEntry> all() {
        return evictions;
    }

    /**
     * Remove completed evictions (those that are effective).
     */
    public void cleanupCompletedEvictions() {
        evictions.entrySet().removeIf(e -> e.getValue().isEffective());
        save();
    }
}
