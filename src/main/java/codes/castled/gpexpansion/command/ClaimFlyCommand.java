package codes.castled.gpexpansion.command;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.claimfly.ClaimFlyManager;
import codes.castled.gpexpansion.gp.GPBridge;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class ClaimFlyCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ADMIN_ACTIONS = Arrays.asList("add", "check", "reset", "take", "set");
    private final ClaimFlyManager manager;
    private final GPBridge gpBridge;

    public ClaimFlyCommand(GPExpansionPlugin plugin) {
        this.manager = plugin.getClaimFlyManager();
        this.gpBridge = new GPBridge();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && ADMIN_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            return handleAdmin(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cUsage: /claimfly <add|check|reset|take|set> <players> [time]");
            return true;
        }

        if (!player.hasPermission("griefprevention.claimfly.use")) {
            player.sendMessage("§cYou do not have permission to use claim flight.");
            return true;
        }

        UUID uuid = player.getUniqueId();
        if (!manager.hasFlightAccess(uuid)) {
            player.sendMessage("§cYou do not have any claim flight time available.");
            manager.setEnabled(uuid, false);
            return true;
        }

        boolean enabled = manager.toggle(uuid);
        applyImmediateFlightState(player, enabled);
        player.sendMessage(enabled
                ? "§aClaim flight enabled. " + formatModeSuffix(uuid)
                : "§eClaim flight disabled. " + formatModeSuffix(uuid));
        return true;
    }

    private String formatModeSuffix(UUID uuid) {
        if (manager.isPassiveClaimFlightEnabled()) {
            return "§7Passive mode is active.";
        }
        return "Remaining: §e" + ClaimFlyManager.formatDuration(manager.getRemainingMillis(uuid));
    }

    private void applyImmediateFlightState(Player player, boolean enabled) {
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;

        if (!enabled) {
            player.setFlying(false);
            player.setAllowFlight(false);
            return;
        }

        Object claim = gpBridge.getClaimAt(player.getLocation(), player).orElse(null);
        if (claim == null) {
            player.sendMessage("§eClaim flight is enabled, but you are not standing in a claim.");
            return;
        }

        if (!gpBridge.hasBuildOrInventoryTrust(claim, player.getUniqueId())) {
            player.sendMessage("§eClaim flight is enabled, but you do not have access to this claim.");
            return;
        }

        player.setAllowFlight(true);
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gpx.admin")) {
            sender.sendMessage("§cYou do not have permission to manage claim flight.");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /claimfly " + action + " <players>" + (requiresTime(action) ? " <time>" : ""));
            return true;
        }
        if (requiresTime(action) && args.length < 3) {
            sender.sendMessage("§cTime is required. Example: 1h20m12s, 30m, 3600s");
            return true;
        }

        long millis = 0L;
        if (requiresTime(action)) {
            millis = parseDurationMillis(args[2]);
            if (millis <= 0L) {
                sender.sendMessage("§cInvalid time. Example: 1h20m12s, 30m, 3600s");
                return true;
            }
        }

        List<Player> targets = resolvePlayers(args[1]);
        if (targets.isEmpty()) {
            sender.sendMessage("§cNo matching online players found.");
            return true;
        }

        for (Player target : targets) {
            UUID uuid = target.getUniqueId();
            switch (action) {
                case "add" -> manager.addTime(uuid, millis);
                case "take" -> manager.takeTime(uuid, millis);
                case "set" -> manager.setTime(uuid, millis);
                case "reset" -> manager.reset(uuid);
                case "check" -> { }
                default -> {
                    sender.sendMessage("§cUnknown action: " + action);
                    return true;
                }
            }
            sender.sendMessage("§a" + target.getName() + " claim flight: §e" + ClaimFlyManager.formatDuration(manager.getRemainingMillis(uuid)) + " §7(" + (manager.hasTime(uuid) ? "yes" : "no") + ")");
            if (!action.equals("check")) {
                target.sendMessage("§eYour claim flight time is now §6" + ClaimFlyManager.formatDuration(manager.getRemainingMillis(uuid)) + "§e.");
            }
        }
        return true;
    }

    private boolean requiresTime(String action) {
        return action.equals("add") || action.equals("take") || action.equals("set");
    }

    private List<Player> resolvePlayers(String input) {
        if (input.equalsIgnoreCase("*") || input.equalsIgnoreCase("all")) {
            return new ArrayList<>(Bukkit.getOnlinePlayers());
        }
        List<Player> result = new ArrayList<>();
        for (String name : input.split(",")) {
            Player player = Bukkit.getPlayerExact(name.trim());
            if (player != null) result.add(player);
        }
        return result;
    }

    public static long parseDurationMillis(String input) {
        if (input == null || input.isBlank()) return 0L;
        String value = input.toLowerCase(Locale.ROOT).replace(" ", "");
        long totalSeconds = 0L;
        int index = 0;
        while (index < value.length()) {
            int start = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) index++;
            if (start == index) return 0L;
            long amount;
            try {
                amount = Long.parseLong(value.substring(start, index));
            } catch (NumberFormatException e) {
                return 0L;
            }
            if (index >= value.length()) {
                totalSeconds += amount;
                break;
            }
            char unit = value.charAt(index++);
            switch (unit) {
                case 'd':
                    totalSeconds += amount * 86400L;
                    break;
                case 'h':
                    totalSeconds += amount * 3600L;
                    break;
                case 'm':
                    totalSeconds += amount * 60L;
                    break;
                case 's':
                    totalSeconds += amount;
                    break;
                default:
                    return 0L;
            }
        }
        return totalSeconds * 1000L;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            if (!sender.hasPermission("gpx.admin")) return Collections.emptyList();
            return filter(ADMIN_ACTIONS, args[0]);
        }
        if (args.length == 2 && sender.hasPermission("gpx.admin") && ADMIN_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toCollection(ArrayList::new));
            names.add("all");
            names.add("*");
            return filter(names, args[1]);
        }
        if (args.length == 3 && sender.hasPermission("gpx.admin") && requiresTime(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Arrays.asList("30m", "1h", "2h", "1d"), args[2]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lowered)).collect(Collectors.toList());
    }
}
