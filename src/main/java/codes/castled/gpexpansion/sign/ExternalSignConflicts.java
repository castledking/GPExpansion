package codes.castled.gpexpansion.sign;

import codes.castled.gpexpansion.GPExpansionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Detects conflicting {@code enabledSigns} entries (specifically {@code buy} and {@code sell})
 * configured in Essentials or Allium (including Allium recode) so GPExpansion automatically
 * drops its short {@code [buy]} / {@code [sell]} input headers and yields to those plugins.
 *
 * <p>Longer claim-specific headers such as {@code [sell claim]} and {@code [buy claim]} never
 * collide and always remain active.</p>
 */
public final class ExternalSignConflicts {

    private static final String BUY_HEADER = "[buy]";
    private static final String SELL_HEADER = "[sell]";

    private record CachedFileSigns(long lastModified, long length, Set<String> headers) {}

    private static final ConcurrentMap<String, CachedFileSigns> FILE_CACHE = new ConcurrentHashMap<>();

    private ExternalSignConflicts() {}

    /**
     * Return the normalized lowercase headers (e.g. {@code "[buy]"}, {@code "[sell]"}) that
     * GPExpansion should drop because Essentials or Allium has them enabled under
     * {@code enabledSigns}.
     */
    public static Set<String> conflictedHeaders(GPExpansionPlugin plugin) {
        if (plugin == null || plugin.getConfigManager() == null
                || !plugin.getConfigManager().shouldYieldToExternalEnabledSigns()) {
            return Set.of();
        }
        PluginManager pm = Bukkit.getServer() != null ? Bukkit.getPluginManager() : null;
        if (pm == null) {
            return Set.of();
        }
        Set<String> conflicted = new LinkedHashSet<>(2);
        collectFromEssentials(pm.getPlugin("Essentials"), conflicted);
        collectFromAllium(pm.getPlugin("Allium"), conflicted);
        return conflicted.isEmpty() ? Set.of() : Collections.unmodifiableSet(conflicted);
    }

    /**
     * Check whether a raw or color-coded header matches a conflicted external sign header.
     */
    public static boolean isConflictedHeader(GPExpansionPlugin plugin, String header) {
        if (header == null || header.isBlank()) return false;
        Set<String> conflicted = conflictedHeaders(plugin);
        if (conflicted.isEmpty()) return false;
        return conflicted.contains(normalizeHeader(header));
    }

    /**
     * Pick the preferred configured sell input header, skipping any headers that are currently
     * yielded to Essentials/Allium {@code enabledSigns}.
     */
    public static String preferredSellInputHeader(GPExpansionPlugin plugin, boolean hanging, String fallback) {
        Set<String> conflicted = conflictedHeaders(plugin);
        if (plugin != null) {
            FileConfiguration cfg = plugin.getConfig();
            if (hanging) {
                String match = firstAllowed(cfg.getStringList("signs.sell.hanging-sign-formats.inputs"), conflicted);
                if (match != null) return match;
            }
            String match = firstAllowed(cfg.getStringList("signs.sell.sign-formats.inputs"), conflicted);
            if (match != null) return match;
        }
        if (fallback != null && !fallback.isBlank() && !conflicted.contains(normalizeHeader(fallback))) {
            return fallback;
        }
        if (!conflicted.contains(SELL_HEADER)) return "[sell]";
        if (!conflicted.contains(BUY_HEADER)) return "[buy]";
        return "[sell claim]";
    }

    /**
     * Log an informational notice when external {@code enabledSigns} conflicts are active.
     */
    public static void logActiveConflicts(GPExpansionPlugin plugin) {
        if (plugin == null) return;
        Set<String> conflicted = conflictedHeaders(plugin);
        if (conflicted.isEmpty()) return;
        plugin.getLogger().info("Detected enabledSigns " + conflicted
                + " in Essentials/Allium; dropping conflicting short claim-sell sign syntax "
                + "(use [sell claim] or [buy claim] instead).");
    }

    /**
     * Map raw {@code enabledSigns} values (e.g. {@code "buy"}, {@code "sell"}) to the
     * normalized GPExpansion input headers they collide with.
     */
    public static Set<String> conflictedHeadersFromEnabledSigns(Collection<String> enabledSigns) {
        if (enabledSigns == null || enabledSigns.isEmpty()) {
            return Set.of();
        }
        Set<String> headers = new LinkedHashSet<>(2);
        for (String raw : enabledSigns) {
            if (raw == null) continue;
            String token = raw.trim().toLowerCase(Locale.ROOT);
            if (token.startsWith("[") && token.endsWith("]") && token.length() >= 2) {
                token = token.substring(1, token.length() - 1).trim();
            }
            if ("buy".equals(token)) {
                headers.add(BUY_HEADER);
            } else if ("sell".equals(token)) {
                headers.add(SELL_HEADER);
            }
        }
        return headers.isEmpty() ? Set.of() : Collections.unmodifiableSet(headers);
    }

    /**
     * Parse {@code enabledSigns} directly from an Essentials or Allium {@code config.yml} file.
     * When {@code checkAlliumSignsModule} is {@code true}, returns empty if {@code modules.signs}
     * is explicitly {@code false}.
     */
    public static Set<String> readConflictedHeadersFromConfigFile(File configFile, boolean checkAlliumSignsModule) {
        if (configFile == null || !configFile.isFile()) {
            return Set.of();
        }
        String cacheKey = configFile.getAbsolutePath() + "#" + checkAlliumSignsModule;
        long lastModified = configFile.lastModified();
        long length = configFile.length();
        CachedFileSigns cached = FILE_CACHE.get(cacheKey);
        if (cached != null && cached.lastModified() == lastModified && cached.length() == length) {
            return cached.headers();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        Set<String> parsed;
        if (checkAlliumSignsModule && !yaml.getBoolean("modules.signs", true)) {
            parsed = Set.of();
        } else {
            parsed = conflictedHeadersFromEnabledSigns(yaml.getStringList("enabledSigns"));
        }
        FILE_CACHE.put(cacheKey, new CachedFileSigns(lastModified, length, parsed));
        return parsed;
    }

    private static void collectFromEssentials(Plugin ess, Set<String> out) {
        if (ess == null || !ess.isEnabled()) {
            return;
        }
        // Essentials stores enabledSigns in its custom Settings/EssentialsConfiguration rather
        // than JavaPlugin#getConfig(), so inspect live settings first and fall back to disk.
        try {
            Method getSettings = ess.getClass().getMethod("getSettings");
            Object settings = getSettings.invoke(ess);
            if (settings != null) {
                Method enabledSigns = settings.getClass().getMethod("enabledSigns");
                Object result = enabledSigns.invoke(settings);
                if (result instanceof Iterable<?> signs) {
                    Set<String> names = new LinkedHashSet<>();
                    for (Object sign : signs) {
                        if (sign == null) continue;
                        Method getName = sign.getClass().getMethod("getName");
                        Object name = getName.invoke(sign);
                        if (name != null) {
                            names.add(name.toString());
                        }
                    }
                    out.addAll(conflictedHeadersFromEnabledSigns(names));
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall back to Essentials' config.yml below.
        }
        out.addAll(readConflictedHeadersFromConfigFile(new File(ess.getDataFolder(), "config.yml"), false));
    }

    private static void collectFromAllium(Plugin allium, Set<String> out) {
        if (allium == null || !allium.isEnabled()) {
            return;
        }
        try {
            FileConfiguration cfg = allium.getConfig();
            if (cfg != null) {
                if (!cfg.getBoolean("modules.signs", true)) {
                    return;
                }
                out.addAll(conflictedHeadersFromEnabledSigns(cfg.getStringList("enabledSigns")));
                return;
            }
        } catch (RuntimeException ignored) {
            // Fall back to Allium's config.yml on disk below.
        }
        out.addAll(readConflictedHeadersFromConfigFile(new File(allium.getDataFolder(), "config.yml"), true));
    }

    private static String firstAllowed(List<String> inputs, Set<String> conflicted) {
        if (inputs == null) return null;
        for (String input : inputs) {
            if (input == null || input.isBlank()) continue;
            if (!conflicted.contains(normalizeHeader(input))) {
                return input;
            }
        }
        return null;
    }

    static String normalizeHeader(String input) {
        if (input == null) return "";
        return input.replaceAll("(?i)[§&][0-9A-FK-OR]", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
