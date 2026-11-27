package codes.castled.gpexpansion.gui;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Registry of GPFlags flags that require or support parameters and therefore open a dedicated
 * sub-menu from {@link ClaimFlagsGUI} instead of doing a bare boolean toggle.
 */
final class GPFlagSubmenuRegistry {

    private static final Set<String> SUPPORTED = Set.of(
            // Single-choice menus
            "playergamemode",
            "playertime",
            "playerweather",
            // Multi-select menus
            "noopendoors",
            "nopotioneffects",
            "spawnreasonwhitelist",
            "nomobspawnstype",
            // Numeric / preset menus
            "healthregen",
            "buyaccesstrust",
            "buybuildtrust",
            "buycontainertrust",
            "buysubclaim",
            "spleefarena",
            // Location menus
            "respawnlocation",
            "trappeddestination",
            // Player list menu
            "noenterplayer",
            // Messages / actionbars
            "entermessage",
            "exitmessage",
            "enteractionbar",
            "exitactionbar",
            // Commands / command lists
            "entercommand",
            "entercommand-members",
            "entercommand-owner",
            "enterplayercommand",
            "exitcommand",
            "exitcommand-members",
            "exitcommand-owner",
            "exitplayercommand",
            "netherportalconsolecommand",
            "netherportalplayercommand",
            "commandblacklist",
            "commandwhitelist"
    );

    private GPFlagSubmenuRegistry() {}

    static boolean hasSubmenu(String flagName) {
        return flagName != null && SUPPORTED.contains(flagName.toLowerCase(Locale.ROOT));
    }

    static String actionHint(String flagName) {
        if (flagName == null) return "&eClick to toggle";
        return switch (flagName.toLowerCase(Locale.ROOT)) {
            case "playergamemode" -> "&eClick to choose gamemode";
            case "playertime" -> "&eClick to choose time";
            case "playerweather" -> "&eClick to choose weather";
            case "noopendoors" -> "&eClick to choose door types";
            case "nopotioneffects" -> "&eClick to choose potion effects";
            case "spawnreasonwhitelist" -> "&eClick to choose spawn reasons";
            case "nomobspawnstype" -> "&eClick to choose mob types";
            case "healthregen" -> "&eClick to set regen amount";
            case "buyaccesstrust", "buybuildtrust", "buycontainertrust", "buysubclaim" -> "&eClick to set price";
            case "spleefarena" -> "&eClick to configure arena";
            case "respawnlocation", "trappeddestination" -> "&eClick to set location";
            case "noenterplayer" -> "&eClick to manage blocked players";
            case "entermessage", "exitmessage", "enteractionbar", "exitactionbar" -> "&eClick to set message";
            case "entercommand", "entercommand-members", "entercommand-owner", "enterplayercommand",
                 "exitcommand", "exitcommand-members", "exitcommand-owner", "exitplayercommand",
                 "netherportalconsolecommand", "netherportalplayercommand",
                 "commandblacklist", "commandwhitelist" -> "&eClick to configure commands";
            default -> "&eClick to toggle";
        };
    }

    static String formatParameterLine(String flagName, boolean enabled, String parameters) {
        String lower = flagName == null ? "" : flagName.toLowerCase(Locale.ROOT);
        String trimmed = parameters != null ? parameters.trim() : "";
        if (trimmed.isEmpty()) {
            if (enabled && "noopendoors".equals(lower)) {
                return "&7Blocks: &fAll (Doors, Trapdoors, Gates)";
            }
            return null;
        }
        return switch (lower) {
            case "playergamemode" -> "&7Mode: &f" + GPFlagChoiceGUI.formatLabel(trimmed);
            case "playertime" -> "&7Time: &f" + GPFlagChoiceGUI.formatLabel(trimmed);
            case "playerweather" -> "&7Weather: &f" + GPFlagChoiceGUI.formatLabel(trimmed);
            case "healthregen" -> "&7Heal Amount: &f" + trimmed;
            case "buyaccesstrust", "buybuildtrust", "buycontainertrust", "buysubclaim" -> "&7Price: &f" + trimmed;
            case "respawnlocation", "trappeddestination" -> "&7Location: &f" + trimmed;
            case "noenterplayer" -> "&7Blocked: &f" + trimmed;
            case "noopendoors" -> "&7Blocks: &f" + trimmed;
            default -> "&7Parameters: &f" + trimmed;
        };
    }

    static BaseGUI createSubmenu(
            GUIManager manager,
            Player player,
            Object claim,
            String claimId,
            String flagName,
            int returnPage
    ) {
        String lower = flagName.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "playergamemode" -> new PlayerGamemodeFlagGUI(manager, player, claim, claimId, returnPage);

            case "playertime" -> new GPFlagChoiceGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lPlayer Time",
                    "Choose the time of day for players inside this claim.",
                    List.of(
                            new GPFlagChoiceGUI.Choice("day", "Day", Material.SUNFLOWER, 10,
                                    List.of("&7Locks player time to morning/day.")),
                            new GPFlagChoiceGUI.Choice("noon", "Noon", Material.GOLD_BLOCK, 12,
                                    List.of("&7Locks player time to high noon.")),
                            new GPFlagChoiceGUI.Choice("night", "Night", Material.CLOCK, 14,
                                    List.of("&7Locks player time to night.")),
                            new GPFlagChoiceGUI.Choice("midnight", "Midnight", Material.OBSIDIAN, 16,
                                    List.of("&7Locks player time to midnight."))
                    ),
                    returnPage
            );

            case "playerweather" -> new GPFlagChoiceGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lPlayer Weather",
                    "Choose the weather shown to players inside this claim.",
                    List.of(
                            new GPFlagChoiceGUI.Choice("sun", "Sun", Material.SUNFLOWER, 11,
                                    List.of("&7Clear sunny skies inside this claim.")),
                            new GPFlagChoiceGUI.Choice("rain", "Rain", Material.WATER_BUCKET, 15,
                                    List.of("&7Downfall / rain inside this claim."))
                    ),
                    returnPage
            );

            case "noopendoors" -> new GPFlagMultiSelectGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lNo Open Doors",
                    "Choose which openable blocks untrusted players cannot open.",
                    ",", false,
                    List.of(
                            new GPFlagMultiSelectGUI.Option("doors", "Doors", Material.OAK_DOOR, "Blocks opening doors."),
                            new GPFlagMultiSelectGUI.Option("trapdoors", "Trapdoors", Material.OAK_TRAPDOOR, "Blocks opening trapdoors."),
                            new GPFlagMultiSelectGUI.Option("gates", "Fence Gates", Material.OAK_FENCE_GATE, "Blocks opening fence gates.")
                    ),
                    new GPFlagMultiSelectGUI.AllOption("", "All (Default)", Material.IRON_DOOR,
                            "Blocks doors, trapdoors, and fence gates."),
                    null, null, returnPage
            );

            case "nopotioneffects" -> new GPFlagMultiSelectGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lNo Potion Effects",
                    "Block all potion effects or pick specific effects to block.",
                    " ", true,
                    potionEffectOptions(),
                    new GPFlagMultiSelectGUI.AllOption("all", "All Effects", Material.MILK_BUCKET,
                            "Blocks and removes every potion effect in this claim."),
                    "'all' or space-separated potion names (e.g. POISON WITHER)",
                    null, returnPage
            );

            case "spawnreasonwhitelist" -> new GPFlagMultiSelectGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lSpawn Reason Whitelist",
                    "Only allow mobs spawned by the selected spawn reasons.",
                    " ", true,
                    spawnReasonOptions(),
                    null,
                    "space-separated spawn reasons (e.g. BREEDING SPAWNER_EGG)",
                    null, returnPage
            );

            case "nomobspawnstype" -> new GPFlagMultiSelectGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lNo Mob Spawns Type",
                    "Choose specific mob types to block from spawning in this claim.",
                    ";", false,
                    mobTypeOptions(),
                    null,
                    "semicolon-separated mob types (e.g. CREEPER;ZOMBIE)",
                    "gpflags.flag.nomobspawnstype.", returnPage
            );

            case "healthregen" -> new GPFlagParameterGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lHealth Regen",
                    "Sets how much health players regenerate per check in this claim.",
                    GPFlagParameterGUI.Kind.PRESETS,
                    "a positive whole number (e.g. 1, 2, 4)",
                    false,
                    List.of(
                            new GPFlagParameterGUI.Preset("1", "1 HP", Material.APPLE, 10, "Regenerate 1 HP per tick interval."),
                            new GPFlagParameterGUI.Preset("2", "2 HP (1 Heart)", Material.GOLDEN_APPLE, 11, "Regenerate 1 full heart."),
                            new GPFlagParameterGUI.Preset("4", "4 HP (2 Hearts)", Material.GOLDEN_APPLE, 12, "Regenerate 2 hearts."),
                            new GPFlagParameterGUI.Preset("6", "6 HP (3 Hearts)", Material.ENCHANTED_GOLDEN_APPLE, 13, "Regenerate 3 hearts."),
                            new GPFlagParameterGUI.Preset("10", "10 HP (5 Hearts)", Material.ENCHANTED_GOLDEN_APPLE, 14, "Regenerate 5 hearts.")
                    ),
                    returnPage
            );

            case "buyaccesstrust", "buybuildtrust", "buycontainertrust", "buysubclaim" -> new GPFlagParameterGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&l" + flagName,
                    "Sets the economy cost for players to purchase this option.",
                    GPFlagParameterGUI.Kind.PRESETS,
                    "a non-negative price (e.g. 500 or 250.50)",
                    false,
                    List.of(
                            new GPFlagParameterGUI.Preset("100", "100", Material.GOLD_NUGGET, 10, "Set price to 100."),
                            new GPFlagParameterGUI.Preset("500", "500", Material.GOLD_INGOT, 11, "Set price to 500."),
                            new GPFlagParameterGUI.Preset("1000", "1,000", Material.GOLD_BLOCK, 12, "Set price to 1,000."),
                            new GPFlagParameterGUI.Preset("5000", "5,000", Material.EMERALD, 13, "Set price to 5,000."),
                            new GPFlagParameterGUI.Preset("10000", "10,000", Material.DIAMOND, 14, "Set price to 10,000.")
                    ),
                    returnPage
            );

            case "spleefarena" -> new GPFlagParameterGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lSpleef Arena",
                    "Configures the arena floor block, support block, and reset ticks.",
                    GPFlagParameterGUI.Kind.PRESETS,
                    "<block> <support> <ticks> (e.g. SNOW_BLOCK DIRT 200)",
                    true,
                    List.of(
                            new GPFlagParameterGUI.Preset("SNOW_BLOCK DIRT 200", "Snow / Dirt (200t)", Material.SNOW_BLOCK, 11,
                                    "Snow floor supported by dirt, resets in 200 ticks."),
                            new GPFlagParameterGUI.Preset("SNOW_BLOCK STONE 200", "Snow / Stone (200t)", Material.STONE, 13,
                                    "Snow floor supported by stone, resets in 200 ticks.")
                    ),
                    returnPage
            );

            case "respawnlocation" -> new GPFlagParameterGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lRespawn Location",
                    "Sets where players respawn after dying inside this claim.",
                    GPFlagParameterGUI.Kind.LOCATION_PRECISE,
                    "<world> <x> <y> <z> [yaw] [pitch]",
                    true,
                    List.of(),
                    returnPage
            );

            case "trappeddestination" -> new GPFlagParameterGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lTrapped Destination",
                    "Sets where /trapped teleports players from this claim.",
                    GPFlagParameterGUI.Kind.LOCATION_BLOCK,
                    "<world> <x> <y> <z> (whole numbers)",
                    true,
                    List.of(),
                    returnPage
            );

            case "noenterplayer" -> new GPFlagParameterGUI(
                    manager, player, claim, claimId, flagName,
                    "&b&lNo Enter Player",
                    "Blocks specific players from entering this claim.",
                    GPFlagParameterGUI.Kind.PLAYER_LIST,
                    "space-separated player name(s)",
                    true,
                    List.of(),
                    returnPage
            );

            case "entermessage" -> textMenu(manager, player, claim, claimId, flagName,
                    "Sends a chat message when entering this claim.",
                    "an enter message (supports %owner%, %name%, & colors)", returnPage);
            case "exitmessage" -> textMenu(manager, player, claim, claimId, flagName,
                    "Sends a chat message when exiting this claim.",
                    "an exit message (supports %owner%, %name%, & colors)", returnPage);
            case "enteractionbar" -> textMenu(manager, player, claim, claimId, flagName,
                    "Shows an actionbar message when entering this claim.",
                    "an enter actionbar message (supports %owner%, %name%, & colors)", returnPage);
            case "exitactionbar" -> textMenu(manager, player, claim, claimId, flagName,
                    "Shows an actionbar message when exiting this claim.",
                    "an exit actionbar message (supports %owner%, %name%, & colors)", returnPage);

            case "entercommand", "entercommand-members", "entercommand-owner",
                 "exitcommand", "exitcommand-members", "exitcommand-owner",
                 "netherportalconsolecommand" -> textMenu(manager, player, claim, claimId, flagName,
                    "Runs console command(s) when triggered on this claim.",
                    "console command(s) without leading /, separate multiple with ; (supports %name%, %uuid%)",
                    returnPage);

            case "enterplayercommand", "exitplayercommand", "netherportalplayercommand" -> textMenu(
                    manager, player, claim, claimId, flagName,
                    "Runs player command(s) when triggered on this claim.",
                    "player command(s) without leading /, separate multiple with ; (supports %name%, %uuid%)",
                    returnPage);

            case "commandblacklist" -> textMenu(manager, player, claim, claimId, flagName,
                    "Blocks the specified command(s) inside this claim.",
                    "command(s) without leading /, separated by ; (e.g. sethome;tpa)", returnPage);

            case "commandwhitelist" -> textMenu(manager, player, claim, claimId, flagName,
                    "Allows only the specified command(s) inside this claim.",
                    "command(s) without leading /, separated by ; (e.g. spawn;msg)", returnPage);

            default -> null;
        };
    }

    private static GPFlagParameterGUI textMenu(
            GUIManager manager,
            Player player,
            Object claim,
            String claimId,
            String flagName,
            String summary,
            String promptHint,
            int returnPage
    ) {
        return new GPFlagParameterGUI(
                manager, player, claim, claimId, flagName,
                "&b&l" + flagName,
                summary,
                GPFlagParameterGUI.Kind.TEXT,
                promptHint,
                false,
                List.of(),
                returnPage
        );
    }

    @SuppressWarnings("deprecation")
    static List<GPFlagMultiSelectGUI.Option> potionEffectOptions() {
        List<GPFlagMultiSelectGUI.Option> out = new ArrayList<>();
        try {
            for (PotionEffectType type : PotionEffectType.values()) {
                if (type == null) continue;
                String name = type.getName();
                if (name == null || name.isBlank()) continue;
                String token = name.toUpperCase(Locale.ROOT);
                out.add(new GPFlagMultiSelectGUI.Option(
                        token,
                        prettifyEnum(token),
                        Material.POTION,
                        "Blocks " + prettifyEnum(token)
                ));
            }
        } catch (Throwable ignored) {
            for (String fallback : List.of("INVISIBILITY", "POISON", "REGENERATION", "SLOWNESS", "SPEED", "STRENGTH", "WITHER")) {
                out.add(new GPFlagMultiSelectGUI.Option(
                        fallback,
                        prettifyEnum(fallback),
                        Material.POTION,
                        "Blocks " + prettifyEnum(fallback)
                ));
            }
        }
        out.sort(Comparator.comparing(GPFlagMultiSelectGUI.Option::displayName));
        return out;
    }

    static List<GPFlagMultiSelectGUI.Option> spawnReasonOptions() {
        List<GPFlagMultiSelectGUI.Option> out = new ArrayList<>();
        for (CreatureSpawnEvent.SpawnReason reason : CreatureSpawnEvent.SpawnReason.values()) {
            out.add(new GPFlagMultiSelectGUI.Option(
                    reason.name(),
                    prettifyEnum(reason.name()),
                    Material.SPAWNER,
                    "Allow " + prettifyEnum(reason.name()) + " spawns"
            ));
        }
        out.sort(Comparator.comparing(GPFlagMultiSelectGUI.Option::displayName));
        return out;
    }

    static List<GPFlagMultiSelectGUI.Option> mobTypeOptions() {
        List<GPFlagMultiSelectGUI.Option> out = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            if (type == EntityType.UNKNOWN || type == EntityType.PLAYER || type == EntityType.ARMOR_STAND) continue;
            if (!type.isAlive() || !type.isSpawnable()) continue;
            Material egg = Material.matchMaterial(type.name() + "_SPAWN_EGG");
            Material icon = egg != null ? egg : Material.ZOMBIE_HEAD;
            out.add(new GPFlagMultiSelectGUI.Option(
                    type.name(),
                    prettifyEnum(type.name()),
                    icon,
                    "Blocks " + prettifyEnum(type.name()) + " spawns"
            ));
        }
        out.sort(Comparator.comparing(GPFlagMultiSelectGUI.Option::displayName));
        return out;
    }

    static String prettifyEnum(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
