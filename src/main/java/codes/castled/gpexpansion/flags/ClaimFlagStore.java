package codes.castled.gpexpansion.flags;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import codes.castled.gpexpansion.GPExpansionPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Per-claim flag values, stored in {@code claimflags.yml} keyed by claim ID.
 *
 * <p>Flag checks run on hot events (fluid flow, growth, spawns) and, on Folia, from many region
 * threads, so reads never lock: each claim's values are replaced as a whole on change, and a
 * per-flag count of claims that set it lets callers skip the claim lookup for unused flags.
 */
public final class ClaimFlagStore {

    /** A filter flag's setting: the mode and its entries (materials, entity types, commands...). */
    public record FilterSetting(ClaimFlag.FilterMode mode, Set<String> entries) {
        public FilterSetting {
            entries = Collections.unmodifiableSet(new LinkedHashSet<>(entries));
        }

        public boolean contains(String entry) {
            return entry != null && entries.contains(entry.toLowerCase(Locale.ROOT));
        }

        /** Whether {@code entry} passes: listed on a whitelist, or not listed on a blacklist. */
        public boolean allows(String entry) {
            boolean listed = contains(entry);
            return mode == ClaimFlag.FilterMode.WHITELIST ? listed : !listed;
        }
    }

    private final GPExpansionPlugin plugin;
    private final File file;
    private final Map<String, Map<ClaimFlag, Boolean>> values = new ConcurrentHashMap<>();
    private final Map<String, Map<ClaimFlag, FilterSetting>> filters = new ConcurrentHashMap<>();
    private final AtomicIntegerArray usage = new AtomicIntegerArray(ClaimFlag.values().length);

    public ClaimFlagStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "claimflags.yml");
    }

    public synchronized void load() {
        values.clear();
        filters.clear();
        for (int i = 0; i < usage.length(); i++) {
            usage.set(i, 0);
        }
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection claims = yaml.getConfigurationSection("claims");
        if (claims == null) {
            return;
        }
        for (String claimId : claims.getKeys(false)) {
            ConfigurationSection section = claims.getConfigurationSection(claimId);
            if (section == null) continue;
            Map<ClaimFlag, Boolean> claimValues = new EnumMap<>(ClaimFlag.class);
            Map<ClaimFlag, FilterSetting> claimFilters = new EnumMap<>(ClaimFlag.class);
            for (String key : section.getKeys(false)) {
                ClaimFlag flag = ClaimFlag.fromId(key);
                if (flag == null) continue;
                if (flag.isFilter()) {
                    ConfigurationSection filterSection = section.getConfigurationSection(key);
                    if (filterSection == null) continue;
                    ClaimFlag.FilterMode mode = parseMode(filterSection.getString("mode"), flag.defaultFilterMode());
                    Set<String> entries = new LinkedHashSet<>();
                    for (String entry : filterSection.getStringList("entries")) {
                        if (entry != null && !entry.isBlank()) entries.add(entry.trim().toLowerCase(Locale.ROOT));
                    }
                    claimFilters.put(flag, new FilterSetting(mode, entries));
                } else if (section.isBoolean(key)) {
                    claimValues.put(flag, section.getBoolean(key));
                }
            }
            if (!claimValues.isEmpty()) {
                values.put(claimId, claimValues);
                claimValues.keySet().forEach(flag -> usage.incrementAndGet(flag.ordinal()));
            }
            if (!claimFilters.isEmpty()) {
                filters.put(claimId, claimFilters);
                claimFilters.keySet().forEach(flag -> usage.incrementAndGet(flag.ordinal()));
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "GPExpansion claim flags, set through /claim flags.",
            "true = allowed, false = denied; filter flags store a mode and entries.",
            "Claims without an entry use the server defaults in config.yml (claim-flags.defaults)."));
        Set<String> claimIds = new LinkedHashSet<>(values.keySet());
        claimIds.addAll(filters.keySet());
        for (String claimId : claimIds) {
            String base = "claims." + claimId + ".";
            Map<ClaimFlag, Boolean> claimValues = values.get(claimId);
            if (claimValues != null) {
                claimValues.forEach((flag, value) -> yaml.set(base + flag.id(), value));
            }
            Map<ClaimFlag, FilterSetting> claimFilters = filters.get(claimId);
            if (claimFilters != null) {
                claimFilters.forEach((flag, setting) -> {
                    yaml.set(base + flag.id() + ".mode", setting.mode().name());
                    yaml.set(base + flag.id() + ".entries", new ArrayList<>(setting.entries()));
                });
            }
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed saving claimflags.yml: " + e.getMessage());
        }
    }

    /** The claim's own value: true allowed, false denied, null when the claim hasn't set it. */
    public Boolean get(String claimId, ClaimFlag flag) {
        Map<ClaimFlag, Boolean> claimValues = claimId == null ? null : values.get(claimId);
        return claimValues == null ? null : claimValues.get(flag);
    }

    public FilterSetting getFilter(String claimId, ClaimFlag flag) {
        Map<ClaimFlag, FilterSetting> claimFilters = claimId == null ? null : filters.get(claimId);
        return claimFilters == null ? null : claimFilters.get(flag);
    }

    /** Set or clear (null) a claim's value for a flag. */
    public synchronized void set(String claimId, ClaimFlag flag, Boolean value) {
        Map<ClaimFlag, Boolean> current = values.get(claimId);
        boolean hadValue = current != null && current.containsKey(flag);
        Map<ClaimFlag, Boolean> next = new EnumMap<>(ClaimFlag.class);
        if (current != null) next.putAll(current);
        if (value == null) {
            next.remove(flag);
        } else {
            next.put(flag, value);
        }
        if (next.isEmpty()) {
            values.remove(claimId);
        } else {
            values.put(claimId, next);
        }
        adjustUsage(flag, hadValue, value != null);
    }

    public synchronized void setFilter(String claimId, ClaimFlag flag, FilterSetting setting) {
        Map<ClaimFlag, FilterSetting> current = filters.get(claimId);
        boolean hadValue = current != null && current.containsKey(flag);
        Map<ClaimFlag, FilterSetting> next = new EnumMap<>(ClaimFlag.class);
        if (current != null) next.putAll(current);
        if (setting == null) {
            next.remove(flag);
        } else {
            next.put(flag, setting);
        }
        if (next.isEmpty()) {
            filters.remove(claimId);
        } else {
            filters.put(claimId, next);
        }
        adjustUsage(flag, hadValue, setting != null);
    }

    /** Forget every flag on a claim (it was deleted). */
    public synchronized boolean removeClaim(String claimId) {
        boolean changed = false;
        Map<ClaimFlag, Boolean> claimValues = values.remove(claimId);
        if (claimValues != null) {
            claimValues.keySet().forEach(flag -> usage.decrementAndGet(flag.ordinal()));
            changed = true;
        }
        Map<ClaimFlag, FilterSetting> claimFilters = filters.remove(claimId);
        if (claimFilters != null) {
            claimFilters.keySet().forEach(flag -> usage.decrementAndGet(flag.ordinal()));
            changed = true;
        }
        return changed;
    }

    /** How many flags a claim sets itself. */
    public int countSet(String claimId) {
        Map<ClaimFlag, Boolean> claimValues = values.get(claimId);
        Map<ClaimFlag, FilterSetting> claimFilters = filters.get(claimId);
        return (claimValues == null ? 0 : claimValues.size()) + (claimFilters == null ? 0 : claimFilters.size());
    }

    /** True when at least one claim sets this flag. */
    public boolean isUsed(ClaimFlag flag) {
        return usage.get(flag.ordinal()) > 0;
    }

    private void adjustUsage(ClaimFlag flag, boolean hadValue, boolean hasValue) {
        if (!hadValue && hasValue) {
            usage.incrementAndGet(flag.ordinal());
        } else if (hadValue && !hasValue) {
            usage.decrementAndGet(flag.ordinal());
        }
    }

    private static ClaimFlag.FilterMode parseMode(String raw, ClaimFlag.FilterMode fallback) {
        if (raw == null) return fallback;
        try {
            return ClaimFlag.FilterMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
