package codes.castled.gpexpansion.startup;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.platform.PlatformDetection;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles parsing and display of the startup banner from {@code startups.txt}.
 *
 * <p>Format: each entry is keyed by {@code {name}{number}[...content...]} (e.g. {@code p1[},
 * {@code p2[}). The combined startup text is built from language messages
 * ({@code startup.tag-author}, {@code startup.tag-plugin}, {@code startup.details},
 * {@code startup.boot-finished}) and details rows (platform, storage, counts, feature toggles),
 * then inserted via the {@code ${startup}} placeholder. {@code ${version}} / {@code ${project.version}}
 * are replaced with the plugin version.
 *
 * <p>Mirrors GriefPrevention3D's {@code StartupHeader} minus its localized entry selection (this
 * plugin ships a single {@code lang.yml}, not per-locale message files).
 */
public class StartupHeader {

    private static final Pattern ENTRY_KEY_PATTERN = Pattern.compile("^(\\w+?)\\d+\\[$");
    private static final Pattern CLOSING_BRACKET = Pattern.compile("^\\]$");
    private static final Random RANDOM = new Random();

    private final GPExpansionPlugin plugin;

    public StartupHeader(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Gets a random startup header with all replacements applied.
     *
     * @return the formatted header string, or null if no header could be loaded
     */
    public @Nullable String getRandomHeader() {
        InputStream stream = plugin.getResource("startups.txt");
        if (stream == null) return null;

        List<String> entries = parseStartups(stream);
        if (entries.isEmpty()) return null;

        String header = entries.get(RANDOM.nextInt(entries.size()));
        header = applyReplacements(header);
        header = translate(header);
        return "\n" + header;
    }

    /**
     * Parses startups.txt into a flat list of content blocks.
     */
    private List<String> parseStartups(InputStream stream) {
        List<String> result = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder currentContent = new StringBuilder();
            boolean insideEntry = false;

            String line;
            while ((line = reader.readLine()) != null) {
                if (!insideEntry) {
                    Matcher keyMatcher = ENTRY_KEY_PATTERN.matcher(line.trim());
                    if (keyMatcher.matches()) {
                        currentContent.setLength(0);
                        insideEntry = true;
                    }
                } else {
                    if (CLOSING_BRACKET.matcher(line.trim()).matches()) {
                        if (currentContent.length() > 0) {
                            result.add(currentContent.toString());
                        }
                        insideEntry = false;
                    } else {
                        currentContent.append(line).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse startups.txt: " + e.getMessage());
        }

        return result;
    }

    /**
     * Applies all ${...} template replacements to the header content and builds the
     * combined ${startup} placeholder.
     */
    private String applyReplacements(String header) {
        String version = plugin.getDescription().getVersion();
        header = header.replace("${project.version}", version);
        header = header.replace("${version}", version);

        String startup = buildStartupBlock();
        header = header.replace("${startup}", startup);

        return header;
    }

    /**
     * Builds the combined startup block from language messages:
     * \n + AuthorTag + \n + PluginTag + \n + StartupDetails + \n + details body + \n + BootFinished
     */
    private String buildStartupBlock() {
        String authorTag = msg("startup.tag-author");
        String pluginTag = msg("startup.tag-plugin");
        String bootTime = "&a" + (System.currentTimeMillis() - plugin.getStartupStartTime()) + "ms";
        String bootFinished = plugin.getMessages().getRaw("startup.boot-finished", "{boot}", bootTime);

        String detailsBody = buildDetailsBody();

        StringBuilder sb = new StringBuilder();
        sb.append(authorTag).append("\n&r\n");
        sb.append(pluginTag).append("\n&r\n");
        sb.append(msg("startup.details")).append("\n&r\n");
        sb.append(detailsBody).append("\n&r\n");
        sb.append(bootFinished);
        return sb.toString();
    }

    private String msg(String path) {
        return plugin.getMessages().getRaw(path);
    }

    /**
     * Builds the startup detail block with platform, storage, counts and feature toggles.
     */
    private String buildDetailsBody() {
        String yes = "&aON";
        String no = "&cOFF";

        String storageType = "File";

        String claimsLoaded;
        me.ryanhamshire.GriefPrevention.GriefPrevention gp =
            me.ryanhamshire.GriefPrevention.GriefPrevention.instance;
        if (gp != null && gp.dataStore != null) {
            claimsLoaded = "&a" + gp.dataStore.getClaims().size();
        } else {
            claimsLoaded = "&7n/a";
        }

        String messagesCount = "&a" + plugin.getMessages().getMessageCount();
        String langVersion = "&av" + plugin.getMessages().getLangVersion();

        String platformVersion = PlatformDetection.getServerVersion();
        String pluginVersion = "&a" + plugin.getDescription().getVersion();

        String rentSigns = plugin.getConfigManager().areRentSignsEnabled() ? yes : no;
        String sellSigns = plugin.getConfigManager().areSellSignsEnabled() ? yes : no;
        String mailboxes = plugin.getConfigManager().areMailboxSignsEnabled() ? yes : no;
        String claimFly = plugin.getConfigManager().isClaimFlightEnabled() ? yes : no;
        String waypoints = plugin.getConfigManager().areClaimWaypointsEnabled() ? yes : no;
        String placeholders = org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") ? yes : no;

        StringBuilder details = new StringBuilder();
        details.append(formatLine("Platform:", "&a" + platformVersion, "Storage:", "&a" + storageType));
        details.append("\n");
        details.append(formatLine("Version:", pluginVersion, "Claims Loaded:", claimsLoaded));
        details.append("\n");
        details.append(formatLine("Rent Signs:", rentSigns, "Sell Signs:", sellSigns));
        details.append("\n");
        details.append(formatLine("Mailboxes:", mailboxes, "Claim Fly:", claimFly));
        details.append("\n");
        details.append(formatLine("Waypoints:", waypoints, "PlaceholderAPI:", placeholders));
        details.append("\n");
        details.append(formatLine("Messages loaded:", messagesCount, "Lang File:", langVersion));

        return details.toString();
    }

    private static final int COL_WIDTH = 26;

    /**
     * Formats a two-column detail line with consistent alignment.
     */
    private static String formatLine(String leftLabel, String leftValue,
                                     String rightLabel, String rightValue) {
        String leftText = leftLabel + " " + leftValue;
        String paddedLeft = pad(leftText, COL_WIDTH);
        StringBuilder sb = new StringBuilder();
        sb.append("&7").append(paddedLeft);
        if (rightLabel != null && !rightLabel.isEmpty()) {
            sb.append(" &7").append(rightLabel).append(" ").append(rightValue);
        }
        return sb.toString();
    }

    /**
     * Pads a string to the specified width by appending spaces.
     * Color codes are excluded from width calculation.
     */
    private static String pad(String text, int width) {
        String stripped = text.replaceAll("[&§][0-9a-fk-orx]", "")
                              .replaceAll("&#[0-9a-fA-F]{6}", "")
                              .replaceAll("#[0-9a-fA-F]{6}", "");
        int padding = width - stripped.length();
        if (padding <= 0) return text;
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < padding; i++) sb.append(' ');
        return sb.toString();
    }

    /**
     * Converts legacy {@code &}-codes into {@code §}-codes for console output.
     */
    private static String translate(String text) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }
}