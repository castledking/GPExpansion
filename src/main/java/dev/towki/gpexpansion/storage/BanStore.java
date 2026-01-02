package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class BanStore {
    private final GPExpansionPlugin plugin;
    private final File file;
    private FileConfiguration config;

    public static class BanEntry {
        public boolean banPublic = false;
        public final Set<UUID> players = new HashSet<>();
        public final Map<UUID, String> names = new HashMap<>();
    }

    private final Map<String, BanEntry> bans = new HashMap<>();

    public BanStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bans.yml");
    }

    public void load() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            if (!file.exists()) file.createNewFile();
        } catch (IOException e) {
            plugin.getLogger().warning("Failed creating bans.yml: " + e.getMessage());
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        bans.clear();
        if (config.isConfigurationSection("bans")) {
            for (String claimId : Objects.requireNonNull(config.getConfigurationSection("bans")).getKeys(false)) {
                BanEntry entry = new BanEntry();
                entry.banPublic = config.getBoolean("bans." + claimId + ".public", false);
                List<String> list = config.getStringList("bans." + claimId + ".players");
                for (String s : list) {
                    try { entry.players.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
                }
                if (config.isConfigurationSection("bans." + claimId + ".playersNames")) {
                    for (String key : Objects.requireNonNull(config.getConfigurationSection("bans." + claimId + ".playersNames")).getKeys(false)) {
                        try {
                            UUID u = UUID.fromString(key);
                            String name = config.getString("bans." + claimId + ".playersNames." + key, null);
                            if (name != null) entry.names.put(u, name);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                bans.put(claimId, entry);
            }
        }
    }

    public void save() {
        if (config == null) config = new YamlConfiguration();
        config.set("bans", null);
        for (Map.Entry<String, BanEntry> e : bans.entrySet()) {
            String base = "bans." + e.getKey();
            config.set(base + ".public", e.getValue().banPublic);
            List<String> uuids = new ArrayList<>();
            for (UUID u : e.getValue().players) uuids.add(u.toString());
            config.set(base + ".players", uuids);
            if (!e.getValue().names.isEmpty()) {
                Map<String, String> out = new HashMap<>();
                for (Map.Entry<UUID, String> n : e.getValue().names.entrySet()) {
                    out.put(n.getKey().toString(), n.getValue());
                }
                config.createSection(base + ".playersNames", out);
            } else {
                config.set(base + ".playersNames", null);
            }
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed saving bans.yml: " + e.getMessage());
        }
    }

    public BanEntry get(String claimId) {
        return bans.computeIfAbsent(claimId, k -> new BanEntry());
    }

    public void setPublic(String claimId, boolean value) {
        get(claimId).banPublic = value;
    }

    public boolean isPublicBanned(String claimId) {
        return get(claimId).banPublic;
    }

    public void add(String claimId, UUID uuid) {
        BanEntry e = get(claimId);
        e.players.add(uuid);
        try {
            String name = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
            if (name != null) e.names.put(uuid, name);
        } catch (Throwable ignored) {}
    }

    public void remove(String claimId, UUID uuid) {
        BanEntry e = get(claimId);
        e.players.remove(uuid);
        e.names.remove(uuid);
    }

    public Set<UUID> getPlayers(String claimId) {
        return Collections.unmodifiableSet(get(claimId).players);
    }

    public Map<UUID, String> getNames(String claimId) {
        return Collections.unmodifiableMap(get(claimId).names);
    }
}
