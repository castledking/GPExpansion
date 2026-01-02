package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PendingRentStore {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration conf;

    // in-memory cache
    private final Map<String, PendingRentEntry> pendingRents = new HashMap<>(); // claimId -> entry

    public static class PendingRentEntry {
        public UUID owner;
        public String renterName;
        public String claimId;
        public String kind; // MONEY, EXPERIENCE, CLAIMBLOCKS, ITEM
        public String amount;
        public long timestamp;
        public boolean isPurchase; // true for purchase, false for rental

        public PendingRentEntry(UUID owner, String renterName, String claimId, String kind, String amount, long timestamp, boolean isPurchase) {
            this.owner = owner;
            this.renterName = renterName;
            this.claimId = claimId;
            this.kind = kind;
            this.amount = amount;
            this.timestamp = timestamp;
            this.isPurchase = isPurchase;
        }
    }

    public PendingRentStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending_rents.yml");
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        conf = YamlConfiguration.loadConfiguration(file);
        pendingRents.clear();
        if (conf.isConfigurationSection("pending")) {
            for (String claimId : conf.getConfigurationSection("pending").getKeys(false)) {
                String path = "pending." + claimId + ".";
                String ownerStr = conf.getString(path + "owner");
                String renterName = conf.getString(path + "renterName");
                String kind = conf.getString(path + "kind");
                String amount = conf.getString(path + "amount");
                long timestamp = conf.getLong(path + "timestamp", 0L);
                boolean isPurchase = conf.getBoolean(path + "isPurchase", false);

                try {
                    if (ownerStr != null && renterName != null && kind != null && amount != null) {
                        UUID owner = UUID.fromString(ownerStr);
                        pendingRents.put(claimId, new PendingRentEntry(owner, renterName, claimId, kind, amount, timestamp, isPurchase));
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void save() {
        if (conf == null) conf = new YamlConfiguration();
        conf.set("pending", null);
        for (Map.Entry<String, PendingRentEntry> e : pendingRents.entrySet()) {
            String base = "pending." + e.getKey() + ".";
            conf.set(base + "owner", e.getValue().owner.toString());
            conf.set(base + "renterName", e.getValue().renterName);
            conf.set(base + "claimId", e.getValue().claimId);
            conf.set(base + "kind", e.getValue().kind);
            conf.set(base + "amount", e.getValue().amount);
            conf.set(base + "timestamp", e.getValue().timestamp);
            conf.set(base + "isPurchase", e.getValue().isPurchase);
        }
        try {
            conf.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save pending_rents.yml: " + e.getMessage());
        }
    }

    public void addPendingRent(String claimId, UUID owner, String renterName, String kind, String amount, boolean isPurchase) {
        pendingRents.put(claimId, new PendingRentEntry(owner, renterName, claimId, kind, amount, System.currentTimeMillis(), isPurchase));
    }

    public PendingRentEntry getPendingRent(String claimId) {
        return pendingRents.get(claimId);
    }

    public void removePendingRent(String claimId) {
        pendingRents.remove(claimId);
    }

    public Map<String, PendingRentEntry> all() { return pendingRents; }
}
