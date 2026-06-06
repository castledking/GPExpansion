package codes.castled.gpexpansion.util;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import codes.castled.gpexpansion.GPExpansionPlugin;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ClaimCustomizationUtil {
    private static final Set<Character> COLOR_CODES = Set.of('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f');
    private static final Set<Character> FORMAT_CODES = Set.of('k', 'l', 'm', 'n', 'o', 'r');
    private static final Pattern MINI_MESSAGE_TAG = Pattern.compile("<[^>\\n]{1,64}>");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private ClaimCustomizationUtil() {
    }

    public record TextResult(String value, boolean truncated, boolean rejected) {}

    public static TextResult normalizeName(GPExpansionPlugin plugin, CommandSender sender, String input) {
        Config config = plugin.getConfigManager();
        String value = normalizeText(input,
            config.areClaimNameColorsAllowed(),
            config.areClaimNameFormatsAllowed(),
            config.areClaimNameMiniMessageTagsAllowed(),
            config.isClaimNameObfuscatedStripped());
        value = enforceColorPermissions(sender, value);
        value = toAmpersand(value);
        int maxLength = config.getClaimNameMaxLength();
        if (value.length() > maxLength) {
            return new TextResult(value.substring(0, maxLength), true, false);
        }
        return new TextResult(value, false, false);
    }

    public static TextResult normalizeDescription(GPExpansionPlugin plugin, CommandSender sender, String input) {
        Config config = plugin.getConfigManager();
        if (!config.areClaimDescriptionLinksAllowed() && URL_PATTERN.matcher(input).find()) {
            return new TextResult("", false, true);
        }
        String value = normalizeText(input,
            config.areClaimDescriptionColorsAllowed(),
            config.areClaimDescriptionFormatsAllowed(),
            config.areClaimDescriptionMiniMessageTagsAllowed(),
            true);
        value = enforceColorPermissions(sender, value);
        value = toAmpersand(value);
        int maxLength = config.getClaimDescriptionMaxLength();
        if (value.length() > maxLength) {
            return new TextResult(value.substring(0, maxLength), true, false);
        }
        return new TextResult(value, false, false);
    }

    public static boolean isIconAllowed(GPExpansionPlugin plugin, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (!plugin.getConfigManager().areCustomClaimIconItemsAllowed() && item.hasItemMeta()) {
            return false;
        }
        return isIconMaterialAllowed(plugin, item.getType());
    }

    public static boolean isIconMaterialAllowed(GPExpansionPlugin plugin, Material material) {
        if (material == null || material == Material.AIR) return false;
        if (!plugin.getConfigManager().areClaimIconPlayerHeadsAllowed() && material.name().contains("PLAYER_HEAD")) {
            return false;
        }
        return !getDeniedIconMaterials(plugin).contains(material);
    }

    private static EnumSet<Material> getDeniedIconMaterials(GPExpansionPlugin plugin) {
        EnumSet<Material> denied = EnumSet.noneOf(Material.class);
        for (String raw : plugin.getConfigManager().getDeniedClaimIconMaterials()) {
            if (raw == null || raw.trim().isEmpty()) continue;
            try {
                denied.add(Material.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return denied;
    }

    private static String normalizeText(String input, boolean allowColors, boolean allowFormats, boolean allowMiniMessage, boolean stripObfuscated) {
        if (input == null || input.isEmpty()) return "";
        String value = input.trim();
        if (!allowMiniMessage) {
            value = MINI_MESSAGE_TAG.matcher(value).replaceAll("");
        }
        value = filterLegacyCodes(value, allowColors, allowFormats, stripObfuscated);
        return value.trim();
    }

    private static String filterLegacyCodes(String text, boolean allowColors, boolean allowFormats, boolean stripObfuscated) {
        StringBuilder result = new StringBuilder(text.length());
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if ((c == '&' || c == '\u00A7') && i + 1 < chars.length) {
                char code = Character.toLowerCase(chars[i + 1]);
                if (COLOR_CODES.contains(code)) {
                    if (allowColors) result.append(c).append(chars[i + 1]);
                    i++;
                    continue;
                }
                if (FORMAT_CODES.contains(code)) {
                    boolean obfuscated = code == 'k';
                    if (allowFormats && !(stripObfuscated && obfuscated)) {
                        result.append(c).append(chars[i + 1]);
                    }
                    i++;
                    continue;
                }
                if (code == 'x' && i + 13 < chars.length) {
                    if (allowColors) {
                        for (int j = 0; j < 14; j++) result.append(chars[i + j]);
                    }
                    i += 13;
                    continue;
                }
            }
            result.append(c);
        }
        return result.toString();
    }

    private static String enforceColorPermissions(CommandSender sender, String text) {
        if (text == null || text.isEmpty()) return text;
        if (sender == null) return text;

        boolean hasAllColors = sender.hasPermission("griefprevention.claim.color.*");
        boolean hasAllFormats = sender.hasPermission("griefprevention.claim.format.*");
        if (hasAllColors && hasAllFormats) return text;

        StringBuilder result = new StringBuilder(text.length());
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if ((c == '&' || c == '\u00A7') && i + 1 < chars.length) {
                char code = Character.toLowerCase(chars[i + 1]);
                if (COLOR_CODES.contains(code)) {
                    if (hasAllColors || sender.hasPermission("griefprevention.claim.color." + colorName(code))) {
                        result.append(c).append(chars[i + 1]);
                    }
                    i++;
                    continue;
                }
                if (FORMAT_CODES.contains(code)) {
                    if (hasAllFormats || sender.hasPermission("griefprevention.claim.format." + formatName(code))) {
                        result.append(c).append(chars[i + 1]);
                    }
                    i++;
                    continue;
                }
                if (code == 'x' && i + 13 < chars.length) {
                    if (hasAllColors) {
                        for (int j = 0; j < 14; j++) result.append(chars[i + j]);
                    }
                    i += 13;
                    continue;
                }
            }
            result.append(c);
        }
        return result.toString();
    }

    private static String colorName(char code) {
        return switch (code) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "dark_red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            case 'f' -> "white";
            default -> "";
        };
    }

    private static String formatName(char code) {
        return switch (code) {
            case 'k' -> "obfuscated";
            case 'l' -> "bold";
            case 'm' -> "strikethrough";
            case 'n' -> "underline";
            case 'o' -> "italic";
            case 'r' -> "reset";
            default -> "";
        };
    }

    private static String toAmpersand(String legacy) {
        if (legacy == null || legacy.isEmpty()) return legacy;
        return legacy.replace('\u00A7', '&');
    }
}
