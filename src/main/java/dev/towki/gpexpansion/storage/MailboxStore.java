package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MailboxStore {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration conf;
    
    // in-memory cache: claimId -> owner
    private final Map<String, UUID> mailboxes = new HashMap<>();
    // in-memory cache: claimId -> sign location
    private final Map<String, Location> signLocations = new HashMap<>();
    
    public MailboxStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "mailboxes.yml");
        load();
    }
    
    private void load() {
        File configFile = new File(plugin.getDataFolder(), "mailboxes.yml");
        
        // Create the file if it doesn't exist
        if (!configFile.exists()) {
            try {
                // Ensure the data folder exists
                if (!plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }
                
                // Create the file with default content
                configFile.createNewFile();
                
                // Write default YAML content
                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write("# GPExpansion Mailbox Storage\n");
                    writer.write("# This file stores mailbox ownership data\n\n");
                    writer.write("mailboxes: {}\n");
                }
                
                plugin.getLogger().info("Created mailboxes.yml file");
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create mailboxes.yml: " + e.getMessage());
            }
        }
        
        // Load the configuration
        conf = YamlConfiguration.loadConfiguration(configFile);
        
        // Load mailboxes from config
        if (conf.contains("mailboxes")) {
            var mailboxesSection = conf.getConfigurationSection("mailboxes");
            if (mailboxesSection != null) {
                for (String key : mailboxesSection.getKeys(false)) {
                    String ownerUuid = mailboxesSection.getString(key + ".owner");
                    if (ownerUuid != null) {
                        mailboxes.put(key, UUID.fromString(ownerUuid));
                    }
                    // Load sign location if present
                    String worldName = mailboxesSection.getString(key + ".signWorld");
                    if (worldName != null) {
                        World world = Bukkit.getWorld(worldName);
                        if (world != null) {
                            int x = mailboxesSection.getInt(key + ".signX");
                            int y = mailboxesSection.getInt(key + ".signY");
                            int z = mailboxesSection.getInt(key + ".signZ");
                            signLocations.put(key, new Location(world, x, y, z));
                        }
                    }
                }
            }
        }
    }
    
    public void save() {
        conf.set("mailboxes", null);
        for (Map.Entry<String, UUID> entry : mailboxes.entrySet()) {
            String key = entry.getKey();
            conf.set("mailboxes." + key + ".owner", entry.getValue().toString());
            // Save sign location if present
            Location signLoc = signLocations.get(key);
            if (signLoc != null && signLoc.getWorld() != null) {
                conf.set("mailboxes." + key + ".signWorld", signLoc.getWorld().getName());
                conf.set("mailboxes." + key + ".signX", signLoc.getBlockX());
                conf.set("mailboxes." + key + ".signY", signLoc.getBlockY());
                conf.set("mailboxes." + key + ".signZ", signLoc.getBlockZ());
            }
        }
        try {
            conf.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save mailboxes.yml: " + e.getMessage());
        }
    }
    
    public boolean isMailbox(String claimId) {
        return mailboxes.containsKey(claimId);
    }
    
    public UUID getOwner(String claimId) {
        return mailboxes.get(claimId);
    }
    
    public void setOwner(String claimId, UUID owner) {
        mailboxes.put(claimId, owner);
        save();
    }
    
    public void setSignLocation(String claimId, Location location) {
        signLocations.put(claimId, location);
        save();
    }
    
    public Location getSignLocation(String claimId) {
        return signLocations.get(claimId);
    }
    
    public void remove(String claimId) {
        mailboxes.remove(claimId);
        signLocations.remove(claimId);
        save();
    }
    
    public Map<String, UUID> all() {
        return new HashMap<>(mailboxes);
    }
}
