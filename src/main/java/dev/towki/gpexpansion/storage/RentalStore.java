package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RentalStore {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration conf;

    // in-memory cache
    private final Map<String, Entry> rentals = new HashMap<>(); // claimId -> entry

    public static class Entry {
        public UUID renter;
        public long expiry;
        // when the current rental period started (for percentage milestones)
        public long start;
        // bitmask of reminders sent (see ReminderService for mapping)
        public int reminders;
        // true if renter should be notified on next join that it expired while offline
        public boolean pendingExpiryNotice;
        // true if renter is being evicted and cannot renew
        public boolean beingEvicted;
        public Entry(UUID renter, long expiry) {
            this.renter = renter;
            this.expiry = expiry;
            this.start = System.currentTimeMillis();
            this.reminders = 0;
            this.pendingExpiryNotice = false;
            this.beingEvicted = false;
        }
        public Entry(UUID renter, long expiry, long start, int reminders, boolean pendingExpiryNotice, boolean beingEvicted) {
            this.renter = renter;
            this.expiry = expiry;
            this.start = start;
            this.reminders = reminders;
            this.pendingExpiryNotice = pendingExpiryNotice;
            this.beingEvicted = beingEvicted;
        }
    }

    public RentalStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rentals.yml");
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        conf = YamlConfiguration.loadConfiguration(file);
        rentals.clear();
        if (conf.isConfigurationSection("rentals")) {
            for (String claimId : conf.getConfigurationSection("rentals").getKeys(false)) {
                String path = "rentals." + claimId + ".";
                String renterStr = conf.getString(path + "renter");
                long expiry = conf.getLong(path + "expiry", 0L);
                long start = conf.getLong(path + "start", 0L);
                int reminders = conf.getInt(path + "reminders", 0);
                boolean pending = conf.getBoolean(path + "pendingExpiryNotice", false);
                try {
                    if (renterStr != null) {
                        UUID renter = UUID.fromString(renterStr);
                        rentals.put(claimId, new Entry(renter, expiry, start, reminders, pending, false));
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void save() {
        if (conf == null) conf = new YamlConfiguration();
        conf.set("rentals", null);
        for (Map.Entry<String, Entry> e : rentals.entrySet()) {
            String base = "rentals." + e.getKey() + ".";
            conf.set(base + "renter", e.getValue().renter.toString());
            conf.set(base + "expiry", e.getValue().expiry);
            conf.set(base + "start", e.getValue().start);
            conf.set(base + "reminders", e.getValue().reminders);
            conf.set(base + "pendingExpiryNotice", e.getValue().pendingExpiryNotice);
            conf.set(base + "beingEvicted", e.getValue().beingEvicted);
        }
        try {
            conf.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save rentals.yml: " + e.getMessage());
        }
    }

    public java.util.Set<String> getRentedClaimIds() {
        return rentals.keySet();
    }

    public void set(String claimId, UUID renter, long expiry) {
        rentals.put(claimId, new Entry(renter, expiry));
    }

    public void update(String claimId, Entry entry) {
        rentals.put(claimId, entry);
    }

    public void clear(String claimId) {
        rentals.remove(claimId);
    }

    public Map<String, Entry> all() { return rentals; }
    
    public boolean isRented(String claimId) {
        Entry entry = rentals.get(claimId);
        if (entry == null) return false;
        return entry.expiry > System.currentTimeMillis();
    }
    
    public Entry get(String claimId) {
        return rentals.get(claimId);
    }
}
