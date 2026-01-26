package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Consolidated claim data storage that combines icons, bans, names, rentals, mailboxes, evictions, and spawns
 * into a single smart YAML structure organized by claim ID.
 */
public class ClaimDataStore {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration config;
    
    // In-memory cache
    private final Map<String, ClaimData> claimData = new HashMap<>();
    
    public static class ClaimData {
        // Basic claim info
        public boolean publicListed = false;
        public Material icon = null;
        public String description = null;
        public String customName = null;
        
        // Ban data
        public BanData bans = new BanData();
        
        // Rental data
        public RentalData rental = null;
        
        // Mailbox data
        public MailboxData mailbox = null;
        
        // Eviction data
        public EvictionData eviction = null;

        // Pending rent/buy payout data
        public PendingRentData pendingRent = null;
        
        // Spawn data
        public Location spawn = null;
        
        public ClaimData() {}
    }
    
    public static class BanData {
        public boolean publicBanned = false;
        public final Set<UUID> bannedPlayers = new HashSet<>();
        public final Map<UUID, String> playerNames = new HashMap<>();
    }
    
    public static class RentalData {
        public UUID renter;
        public long expiry;
        public long start;
        public final Set<Long> reminders = new HashSet<>();
        public boolean pendingPayment = false;
        public boolean paymentFailed = false;
        
        public RentalData(UUID renter, long expiry, long start) {
            this.renter = renter;
            this.expiry = expiry;
            this.start = start;
        }
    }
    
    public static class MailboxData {
        public UUID owner;
        public Location signLocation;
        
        public MailboxData(UUID owner) {
            this.owner = owner;
        }
    }
    
    public static class EvictionData {
        public UUID ownerId;
        public UUID renterId;
        public long initiatedAt;
        public long effectiveAt;
        
        public EvictionData(UUID ownerId, UUID renterId, long initiatedAt, long effectiveAt) {
            this.ownerId = ownerId;
            this.renterId = renterId;
            this.initiatedAt = initiatedAt;
            this.effectiveAt = effectiveAt;
        }
    }

    public static class PendingRentData {
        public UUID ownerId;
        public String renterName;
        public String kind;
        public String amount;
        public long timestamp;
        public boolean isPurchase;

        public PendingRentData(UUID ownerId, String renterName, String kind, String amount, long timestamp, boolean isPurchase) {
            this.ownerId = ownerId;
            this.renterName = renterName;
            this.kind = kind;
            this.amount = amount;
            this.timestamp = timestamp;
            this.isPurchase = isPurchase;
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
                // Initialize with basic structure
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write("# GPExpansion Consolidated Claim Data\n");
                    writer.write("# This file stores all claim-related data in one place\n\n");
                    writer.write("claims: {}\n");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed creating claimdata.yml: " + e.getMessage());
        }
        
        config = YamlConfiguration.loadConfiguration(file);
        claimData.clear();
        
        if (config.isConfigurationSection("claims")) {
            for (String claimId : config.getConfigurationSection("claims").getKeys(false)) {
                ClaimData data = loadClaimData(claimId);
                claimData.put(claimId, data);
            }
        }
        
        plugin.getLogger().info("Loaded " + claimData.size() + " claim data entries.");
        
        // Always check for old files and show safe-to-delete messages
        checkForOldFiles();
        
        // Check if we need to migrate old files (only when config version is 0.1.2)
        checkAndMigrateOldFiles();
    }
    
    /**
     * Reload the claim data store
     */
    public void reload() {
        load();
    }
    
    private ClaimData loadClaimData(String claimId) {
        String path = "claims." + claimId + ".";
        ClaimData data = new ClaimData();
        
        // Basic data
        data.publicListed = config.getBoolean(path + "public", false);
        String iconName = config.getString(path + "icon");
        if (iconName != null) {
            data.icon = Material.matchMaterial(iconName);
        }
        data.description = config.getString(path + "description");
        data.customName = config.getString(path + "name");
        if (data.description != null && data.description.length() > 32) {
            data.description = data.description.substring(0, 32);
        }
        
        // Ban data
        String banPath = path + "bans.";
        data.bans.publicBanned = config.getBoolean(banPath + "public", false);
        List<String> bannedUuids = config.getStringList(banPath + "players");
        for (String uuidStr : bannedUuids) {
            try {
                data.bans.bannedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {}
        }
        if (config.isConfigurationSection(banPath + "names")) {
            for (String key : config.getConfigurationSection(banPath + "names").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String name = config.getString(banPath + "names." + key);
                    if (name != null) {
                        data.bans.playerNames.put(uuid, name);
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
        
        // Rental data
        if (config.contains(path + "rental")) {
            String rentalPath = path + "rental.";
            String renterStr = config.getString(rentalPath + "renter");
            if (renterStr != null) {
                try {
                    UUID renter = UUID.fromString(renterStr);
                    long expiry = config.getLong(rentalPath + "expiry", 0L);
                    long start = config.getLong(rentalPath + "start", 0L);
                    data.rental = new RentalData(renter, expiry, start);
                    
                    List<Long> reminders = config.getLongList(rentalPath + "reminders");
                    data.rental.reminders.addAll(reminders);
                    data.rental.pendingPayment = config.getBoolean(rentalPath + "pendingPayment", false);
                    data.rental.paymentFailed = config.getBoolean(rentalPath + "paymentFailed", false);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        
        // Mailbox data
        if (config.contains(path + "mailbox")) {
            String mailboxPath = path + "mailbox.";
            String ownerStr = config.getString(mailboxPath + "owner");
            if (ownerStr != null) {
                try {
                    UUID owner = UUID.fromString(ownerStr);
                    data.mailbox = new MailboxData(owner);
                    
                    // Load sign location
                    String worldName = config.getString(mailboxPath + "signWorld");
                    if (worldName != null) {
                        World world = Bukkit.getWorld(worldName);
                        if (world != null) {
                            int x = config.getInt(mailboxPath + "signX");
                            int y = config.getInt(mailboxPath + "signY");
                            int z = config.getInt(mailboxPath + "signZ");
                            data.mailbox.signLocation = new Location(world, x, y, z);
                        }
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
        
        // Eviction data
        if (config.contains(path + "eviction")) {
            String evictionPath = path + "eviction.";
            String ownerStr = config.getString(evictionPath + "owner");
            String renterStr = config.getString(evictionPath + "renter");
            if (ownerStr != null && renterStr != null) {
                try {
                    UUID owner = UUID.fromString(ownerStr);
                    UUID renter = UUID.fromString(renterStr);
                    long initiatedAt = config.getLong(evictionPath + "initiatedAt", 0L);
                    long effectiveAt = config.getLong(evictionPath + "effectiveAt", 0L);
                    data.eviction = new EvictionData(owner, renter, initiatedAt, effectiveAt);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // Pending rent data
        if (config.contains(path + "pendingRent")) {
            String pendingPath = path + "pendingRent.";
            String ownerStr = config.getString(pendingPath + "owner");
            String renterName = config.getString(pendingPath + "renterName");
            String kind = config.getString(pendingPath + "kind");
            String amount = config.getString(pendingPath + "amount");
            long timestamp = config.getLong(pendingPath + "timestamp", 0L);
            boolean isPurchase = config.getBoolean(pendingPath + "isPurchase", false);
            if (ownerStr != null && renterName != null && kind != null && amount != null) {
                try {
                    UUID owner = UUID.fromString(ownerStr);
                    data.pendingRent = new PendingRentData(owner, renterName, kind, amount, timestamp, isPurchase);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        
        // Spawn data
        String spawnStr = config.getString(path + "spawn");
        if (spawnStr != null) {
            data.spawn = deserializeLocation(spawnStr);
        }
        
        return data;
    }
    
    public void save() {
        if (config == null) config = new YamlConfiguration();
        config.set("claims", null);
        
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            String path = "claims." + entry.getKey() + ".";
            ClaimData data = entry.getValue();
            
            // Basic data
            config.set(path + "public", data.publicListed);
            if (data.icon != null) {
                config.set(path + "icon", data.icon.name());
            }
            if (data.description != null) {
                config.set(path + "description", data.description);
            }
            if (data.customName != null) {
                config.set(path + "name", data.customName);
            }
            
            // Ban data
            String banPath = path + "bans.";
            config.set(banPath + "public", data.bans.publicBanned);
            List<String> bannedUuids = data.bans.bannedPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());
            config.set(banPath + "players", bannedUuids);
            
            if (!data.bans.playerNames.isEmpty()) {
                Map<String, String> namesMap = data.bans.playerNames.entrySet().stream()
                    .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        Map.Entry::getValue
                    ));
                config.createSection(banPath + "names", namesMap);
            }
            
            // Rental data
            if (data.rental != null) {
                String rentalPath = path + "rental.";
                config.set(rentalPath + "renter", data.rental.renter.toString());
                config.set(rentalPath + "expiry", data.rental.expiry);
                config.set(rentalPath + "start", data.rental.start);
                config.set(rentalPath + "reminders", new ArrayList<>(data.rental.reminders));
                config.set(rentalPath + "pendingPayment", data.rental.pendingPayment);
                config.set(rentalPath + "paymentFailed", data.rental.paymentFailed);
            }
            
            // Mailbox data
            if (data.mailbox != null) {
                String mailboxPath = path + "mailbox.";
                config.set(mailboxPath + "owner", data.mailbox.owner.toString());
                if (data.mailbox.signLocation != null && data.mailbox.signLocation.getWorld() != null) {
                    config.set(mailboxPath + "signWorld", data.mailbox.signLocation.getWorld().getName());
                    config.set(mailboxPath + "signX", data.mailbox.signLocation.getBlockX());
                    config.set(mailboxPath + "signY", data.mailbox.signLocation.getBlockY());
                    config.set(mailboxPath + "signZ", data.mailbox.signLocation.getBlockZ());
                }
            }
            
            // Eviction data
            if (data.eviction != null) {
                String evictionPath = path + "eviction.";
                config.set(evictionPath + "owner", data.eviction.ownerId.toString());
                config.set(evictionPath + "renter", data.eviction.renterId.toString());
                config.set(evictionPath + "initiatedAt", data.eviction.initiatedAt);
                config.set(evictionPath + "effectiveAt", data.eviction.effectiveAt);
            }

            // Pending rent data
            if (data.pendingRent != null) {
                String pendingPath = path + "pendingRent.";
                config.set(pendingPath + "owner", data.pendingRent.ownerId.toString());
                config.set(pendingPath + "renterName", data.pendingRent.renterName);
                config.set(pendingPath + "kind", data.pendingRent.kind);
                config.set(pendingPath + "amount", data.pendingRent.amount);
                config.set(pendingPath + "timestamp", data.pendingRent.timestamp);
                config.set(pendingPath + "isPurchase", data.pendingRent.isPurchase);
            }
            
            // Spawn data
            if (data.spawn != null) {
                config.set(path + "spawn", serializeLocation(data.spawn));
            }
        }
        
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed saving claimdata.yml: " + e.getMessage());
        }
    }
    
    /**
     * Check for old storage files and show safe-to-delete messages
     */
    private void checkForOldFiles() {
        String[] oldFiles = {
            "icons.yml", "bans.yml", "names.yml", "rentals.yml", 
            "mailboxes.yml", "evictions.yml", "spawns.yml", "pending_rents.yml"
        };
        
        for (String fileName : oldFiles) {
            File oldFile = new File(plugin.getDataFolder(), fileName);
            if (oldFile.exists()) {
                plugin.getLogger().info("Deprecated file found: " + fileName + " - This file is safe to delete.");
            }
        }
        
        // Also check for deprecated GUI files
        File guisFolder = new File(plugin.getDataFolder(), "guis");
        if (guisFolder.exists()) {
            File claimOptionsFile = new File(guisFolder, "claim-options.yml");
            if (claimOptionsFile.exists()) {
                plugin.getLogger().info("Deprecated file found: guis/claim-options.yml - This file is safe to delete.");
            }
        }
    }
    
    private void checkAndMigrateOldFiles() {
        // Check if config version is 0.1.2 (old format) - only migrate then
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (configFile.exists()) {
            try {
                String configContent = Files.readString(configFile.toPath());
                boolean hasVersionSection = configContent.contains("version:") && configContent.contains("config-version:");
                
                if (!hasVersionSection) {
                    // Config is old format (0.1.2), migrate all old files
                    plugin.getLogger().info("Detected old config format, migrating all storage files to claimdata.yml...");
                    
                    boolean migrated = false;
                    
                    // Migrate all old files
                    if (migrateIcons()) migrated = true;
                    if (migrateBans()) migrated = true;
                    if (migrateNames()) migrated = true;
                    if (migrateRentals()) migrated = true;
                    if (migrateMailboxes()) migrated = true;
                    if (migrateEvictions()) migrated = true;
                    if (migrateSpawns()) migrated = true;
                    if (migratePendingRents()) migrated = true;
                    
                    if (migrated) {
                        // Save the migrated data
                        save();
                        plugin.getLogger().info("Successfully migrated all old storage files to consolidated claimdata.yml");
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to read config file for migration check: " + e.getMessage());
            }
        }
    }
    
    private boolean migrateIcons() {
        File iconsFile = new File(plugin.getDataFolder(), "icons.yml");
        if (iconsFile.exists()) {
            FileConfiguration iconsConfig = YamlConfiguration.loadConfiguration(iconsFile);
            int migratedCount = 0;
            for (String claimId : iconsConfig.getKeys(false)) {
                String iconName = iconsConfig.getString(claimId);
                if (iconName != null) {
                    Material material = Material.matchMaterial(iconName);
                    if (material != null) {
                        ClaimData data = claimData.computeIfAbsent(claimId, k -> new ClaimData());
                        data.icon = material;
                        migratedCount++;
                    }
                }
            }
            if (migratedCount > 0) {
                plugin.getLogger().info("Migrated " + migratedCount + " icons from icons.yml");
                return true;
            }
        }
        return false;
    }
    
    private boolean migrateBans() {
        File bansFile = new File(plugin.getDataFolder(), "bans.yml");
        if (bansFile.exists()) {
            FileConfiguration bansConfig = YamlConfiguration.loadConfiguration(bansFile);
            if (bansConfig.isConfigurationSection("bans")) {
                int migratedCount = 0;
                for (String claimId : bansConfig.getConfigurationSection("bans").getKeys(false)) {
                    ClaimData data = claimData.computeIfAbsent(claimId, k -> new ClaimData());
                    String path = "bans." + claimId + ".";
                    
                    data.bans.publicBanned = bansConfig.getBoolean(path + "public", false);
                    
                    List<String> players = bansConfig.getStringList(path + "players");
                    for (String playerStr : players) {
                        try {
                            data.bans.bannedPlayers.add(UUID.fromString(playerStr));
                        } catch (IllegalArgumentException ignored) {}
                    }
                    
                    if (bansConfig.isConfigurationSection(path + "playersNames")) {
                        for (String key : bansConfig.getConfigurationSection(path + "playersNames").getKeys(false)) {
                            try {
                                UUID uuid = UUID.fromString(key);
                                String name = bansConfig.getString(path + "playersNames." + key);
                                if (name != null) {
                                    data.bans.playerNames.put(uuid, name);
                                }
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                    migratedCount++;
                }
                if (migratedCount > 0) {
                    plugin.getLogger().info("Migrated bans from bans.yml");
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean migrateNames() {
        File namesFile = new File(plugin.getDataFolder(), "names.yml");
        if (namesFile.exists()) {
            FileConfiguration namesConfig = YamlConfiguration.loadConfiguration(namesFile);
            if (namesConfig.isConfigurationSection("names")) {
                int migratedCount = 0;
                for (String claimId : namesConfig.getConfigurationSection("names").getKeys(false)) {
                    String name = namesConfig.getString("names." + claimId);
                    if (name != null) {
                        ClaimData data = claimData.computeIfAbsent(claimId, k -> new ClaimData());
                        data.customName = name;
                        migratedCount++;
                    }
                }
                if (migratedCount > 0) {
                    plugin.getLogger().info("Migrated " + migratedCount + " names from names.yml");
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean migrateRentals() {
        File rentalsFile = new File(plugin.getDataFolder(), "rentals.yml");
        if (rentalsFile.exists()) {
            FileConfiguration rentalsConfig = YamlConfiguration.loadConfiguration(rentalsFile);
            if (rentalsConfig.isConfigurationSection("rentals")) {
                int migratedCount = 0;
                for (String claimId : rentalsConfig.getConfigurationSection("rentals").getKeys(false)) {
                    String path = "rentals." + claimId + ".";
                    String renterStr = rentalsConfig.getString(path + "renter");
                    if (renterStr != null) {
                        try {
                            UUID renter = UUID.fromString(renterStr);
                            long expiry = rentalsConfig.getLong(path + "expiry", 0L);
                            long start = rentalsConfig.getLong(path + "start", 0L);
                            
                            ClaimData data = claimData.computeIfAbsent(claimId, k -> new ClaimData());
                            data.rental = new RentalData(renter, expiry, start);
                            
                            List<Long> reminders = rentalsConfig.getLongList(path + "reminders");
                            data.rental.reminders.addAll(reminders);
                            data.rental.pendingPayment = rentalsConfig.getBoolean(path + "pending", false);
                            data.rental.paymentFailed = rentalsConfig.getBoolean(path + "paymentFailed", false);
                            migratedCount++;
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                if (migratedCount > 0) {
                    plugin.getLogger().info("Migrated rentals from rentals.yml");
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean migrateMailboxes() {
        File mailboxesFile = new File(plugin.getDataFolder(), "mailboxes.yml");
        if (mailboxesFile.exists()) {
            FileConfiguration mailboxesConfig = YamlConfiguration.loadConfiguration(mailboxesFile);
            if (mailboxesConfig.isConfigurationSection("mailboxes")) {
                int migratedCount = 0;
                for (String claimId : mailboxesConfig.getConfigurationSection("mailboxes").getKeys(false)) {
                    String path = "mailboxes." + claimId + ".";
                    String ownerStr = mailboxesConfig.getString(path + "owner");
                    if (ownerStr != null) {
                        try {
                            UUID owner = UUID.fromString(ownerStr);
                            ClaimData data = claimData.computeIfAbsent(claimId, k -> new ClaimData());
                            data.mailbox = new MailboxData(owner);
                            
                            // Load sign location
                            String worldName = mailboxesConfig.getString(path + "signWorld");
                            if (worldName != null) {
                                World world = Bukkit.getWorld(worldName);
                                if (world != null) {
                                    int x = mailboxesConfig.getInt(path + "signX");
                                    int y = mailboxesConfig.getInt(path + "signY");
                                    int z = mailboxesConfig.getInt(path + "signZ");
                                    data.mailbox.signLocation = new Location(world, x, y, z);
                                }
                            }
                            migratedCount++;
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                if (migratedCount > 0) {
                    plugin.getLogger().info("Migrated mailboxes from mailboxes.yml");
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean migrateEvictions() {
        File evictionsFile = new File(plugin.getDataFolder(), "evictions.yml");
        if (evictionsFile.exists()) {
            FileConfiguration evictionsConfig = YamlConfiguration.loadConfiguration(evictionsFile);
            if (evictionsConfig.isConfigurationSection("evictions")) {
                int migratedCount = 0;
                for (String claimId : evictionsConfig.getConfigurationSection("evictions").getKeys(false)) {
                    String path = "evictions." + claimId + ".";
                    String ownerStr = evictionsConfig.getString(path + "owner");
                    String renterStr = evictionsConfig.getString(path + "renter");
                    if (ownerStr != null && renterStr != null) {
                        try {
                            UUID owner = UUID.fromString(ownerStr);
                            UUID renter = UUID.fromString(renterStr);
                            long initiatedAt = evictionsConfig.getLong(path + "initiatedAt", 0L);
                            long effectiveAt = evictionsConfig.getLong(path + "effectiveAt", 0L);
                            
                            ClaimData data = claimData.computeIfAbsent(claimId, k -> new ClaimData());
                            data.eviction = new EvictionData(owner, renter, initiatedAt, effectiveAt);
                            migratedCount++;
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                if (migratedCount > 0) {
                    plugin.getLogger().info("Migrated evictions from evictions.yml");
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean migrateSpawns() {
        File spawnsFile = new File(plugin.getDataFolder(), "spawns.yml");
        if (spawnsFile.exists()) {
            FileConfiguration spawnsConfig = YamlConfiguration.loadConfiguration(spawnsFile);
            if (spawnsConfig.isConfigurationSection("spawns")) {
                int migratedCount = 0;
                for (String claimId : spawnsConfig.getConfigurationSection("spawns").getKeys(false)) {
                    String locationStr = spawnsConfig.getString("spawns." + claimId);
                    if (locationStr != null) {
                        Location location = deserializeLocation(locationStr);
                        if (location != null) {
                            ClaimData data = claimData.computeIfAbsent(claimId, k -> new ClaimData());
                            data.spawn = location;
                            migratedCount++;
                        }
                    }
                }
                if (migratedCount > 0) {
                    plugin.getLogger().info("Migrated spawns from spawns.yml");
                    return true;
                }
            }
        }
        return false;
    }

    private boolean migratePendingRents() {
        File pendingFile = new File(plugin.getDataFolder(), "pending_rents.yml");
        if (pendingFile.exists()) {
            FileConfiguration pendingConfig = YamlConfiguration.loadConfiguration(pendingFile);
            if (pendingConfig.isConfigurationSection("pending")) {
                int migratedCount = 0;
                for (String claimId : pendingConfig.getConfigurationSection("pending").getKeys(false)) {
                    String path = "pending." + claimId + ".";
                    String ownerStr = pendingConfig.getString(path + "owner");
                    String renterName = pendingConfig.getString(path + "renterName");
                    String kind = pendingConfig.getString(path + "kind");
                    String amount = pendingConfig.getString(path + "amount");
                    long timestamp = pendingConfig.getLong(path + "timestamp", 0L);
                    boolean isPurchase = pendingConfig.getBoolean(path + "isPurchase", false);
                    if (ownerStr != null && renterName != null && kind != null && amount != null) {
                        try {
                            UUID owner = UUID.fromString(ownerStr);
                            ClaimData data = claimData.computeIfAbsent(claimId, k -> new ClaimData());
                            data.pendingRent = new PendingRentData(owner, renterName, kind, amount, timestamp, isPurchase);
                            migratedCount++;
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                if (migratedCount > 0) {
                    plugin.getLogger().info("Migrated pending rents from pending_rents.yml");
                    return true;
                }
            }
        }
        return false;
    }
    
    private String serializeLocation(Location loc) {
        return loc.getWorld().getName() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ() + "," + loc.getYaw() + "," + loc.getPitch();
    }
    
    private Location deserializeLocation(String str) {
        String[] parts = str.split(",");
        if (parts.length >= 4) {
            try {
                World world = Bukkit.getWorld(parts[0]);
                if (world != null) {
                    double x = Double.parseDouble(parts[1]);
                    double y = Double.parseDouble(parts[2]);
                    double z = Double.parseDouble(parts[3]);
                    float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0f;
                    float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0f;
                    return new Location(world, x, y, z, yaw, pitch);
                }
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }
    
    // Public API methods that delegate to the appropriate data
    
    public ClaimData get(String claimId) {
        return claimData.computeIfAbsent(claimId, k -> new ClaimData());
    }
    
    public void set(String claimId, ClaimData data) {
        claimData.put(claimId, data);
    }
    
    public void remove(String claimId) {
        claimData.remove(claimId);
    }
    
    // Basic claim data methods
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
    
    public Optional<String> getCustomName(String claimId) {
        ClaimData data = claimData.get(claimId);
        return data != null ? Optional.ofNullable(data.customName) : Optional.empty();
    }
    
    public void setCustomName(String claimId, String name) {
        get(claimId).customName = name;
    }
    
    // Ban methods
    public BanData getBans(String claimId) {
        return get(claimId).bans;
    }
    
    public boolean isPublicBanned(String claimId) {
        return get(claimId).bans.publicBanned;
    }
    
    public void setPublicBanned(String claimId, boolean banned) {
        get(claimId).bans.publicBanned = banned;
    }
    
    public void addBannedPlayer(String claimId, UUID player) {
        BanData bans = get(claimId).bans;
        bans.bannedPlayers.add(player);
        try {
            String name = Bukkit.getOfflinePlayer(player).getName();
            if (name != null) {
                bans.playerNames.put(player, name);
            }
        } catch (Throwable ignored) {}
    }
    
    public void removeBannedPlayer(String claimId, UUID player) {
        BanData bans = get(claimId).bans;
        bans.bannedPlayers.remove(player);
        bans.playerNames.remove(player);
    }
    
    public Set<UUID> getBannedPlayers(String claimId) {
        return Collections.unmodifiableSet(get(claimId).bans.bannedPlayers);
    }
    
    // Rental methods
    public Optional<RentalData> getRental(String claimId) {
        ClaimData data = claimData.get(claimId);
        return data != null ? Optional.ofNullable(data.rental) : Optional.empty();
    }
    
    public void setRental(String claimId, UUID renter, long expiry, long start) {
        get(claimId).rental = new RentalData(renter, expiry, start);
    }
    
    public void clearRental(String claimId) {
        get(claimId).rental = null;
    }
    
    public boolean isRented(String claimId) {
        RentalData rental = getRental(claimId).orElse(null);
        return rental != null && rental.expiry > System.currentTimeMillis();
    }
    
    // Mailbox methods
    public Optional<MailboxData> getMailbox(String claimId) {
        ClaimData data = claimData.get(claimId);
        return data != null ? Optional.ofNullable(data.mailbox) : Optional.empty();
    }

    public boolean isMailbox(String claimId) {
        return getMailbox(claimId).isPresent();
    }

    public Optional<UUID> getMailboxOwner(String claimId) {
        return getMailbox(claimId).map(mailbox -> mailbox.owner);
    }

    public Optional<Location> getMailboxSignLocation(String claimId) {
        return getMailbox(claimId).map(mailbox -> mailbox.signLocation);
    }
    
    public void setMailbox(String claimId, UUID owner) {
        get(claimId).mailbox = new MailboxData(owner);
    }
    
    public void setMailboxSignLocation(String claimId, Location location) {
        MailboxData mailbox = get(claimId).mailbox;
        if (mailbox != null) {
            mailbox.signLocation = location;
        }
    }
    
    public void clearMailbox(String claimId) {
        get(claimId).mailbox = null;
    }
    
    // Eviction methods
    public Optional<EvictionData> getEviction(String claimId) {
        ClaimData data = claimData.get(claimId);
        return data != null ? Optional.ofNullable(data.eviction) : Optional.empty();
    }
    
    public void setEviction(String claimId, UUID ownerId, UUID renterId, long initiatedAt, long effectiveAt) {
        get(claimId).eviction = new EvictionData(ownerId, renterId, initiatedAt, effectiveAt);
    }
    
    public void clearEviction(String claimId) {
        get(claimId).eviction = null;
    }

    // Pending rent methods
    public Optional<PendingRentData> getPendingRent(String claimId) {
        ClaimData data = claimData.get(claimId);
        return data != null ? Optional.ofNullable(data.pendingRent) : Optional.empty();
    }

    public void setPendingRent(String claimId, UUID ownerId, String renterName, String kind, String amount, long timestamp, boolean isPurchase) {
        get(claimId).pendingRent = new PendingRentData(ownerId, renterName, kind, amount, timestamp, isPurchase);
    }

    public void clearPendingRent(String claimId) {
        get(claimId).pendingRent = null;
    }

    public Map<String, PendingRentData> getAllPendingRents() {
        Map<String, PendingRentData> result = new HashMap<>();
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            if (entry.getValue().pendingRent != null) {
                result.put(entry.getKey(), entry.getValue().pendingRent);
            }
        }
        return result;
    }
    
    public boolean hasPendingEviction(String claimId) {
        EvictionData eviction = getEviction(claimId).orElse(null);
        return eviction != null && eviction.effectiveAt > System.currentTimeMillis();
    }
    
    // Spawn methods
    public Optional<Location> getSpawn(String claimId) {
        ClaimData data = claimData.get(claimId);
        return data != null ? Optional.ofNullable(data.spawn) : Optional.empty();
    }
    
    public void setSpawn(String claimId, Location location) {
        get(claimId).spawn = location;
    }
    
    public void clearSpawn(String claimId) {
        get(claimId).spawn = null;
    }
    
    // Utility methods
    public Set<String> getPublicListedClaims() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            if (entry.getValue().publicListed) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
    
    public Map<String, UUID> getAllMailboxes() {
        Map<String, UUID> result = new HashMap<>();
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            if (entry.getValue().mailbox != null) {
                result.put(entry.getKey(), entry.getValue().mailbox.owner);
            }
        }
        return result;
    }
    
    public Set<String> getRentedClaimIds() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            if (entry.getValue().rental != null) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
    
    public Map<String, RentalData> getAllRentals() {
        Map<String, RentalData> result = new HashMap<>();
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            if (entry.getValue().rental != null) {
                result.put(entry.getKey(), entry.getValue().rental);
            }
        }
        return result;
    }
    
    public int countGlobalClaimsForPlayer(UUID playerId) {
        if (playerId == null) return 0;
        
        dev.towki.gpexpansion.gp.GPBridge gp = new dev.towki.gpexpansion.gp.GPBridge();
        int count = 0;
        
        for (Map.Entry<String, ClaimData> entry : claimData.entrySet()) {
            if (entry.getValue().publicListed) {
                String claimId = entry.getKey();
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
