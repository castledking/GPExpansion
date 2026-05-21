package codes.castled.gpexpansion.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.permission.PermissionManager;
import codes.castled.gpexpansion.permission.SignLimitManager;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * GPX command handler for managing sign limits
 */
public class GPXCommand implements CommandExecutor, TabCompleter {
    
    private final GPExpansionPlugin plugin;
    private final SignLimitManager signLimitManager;
    
    public GPXCommand(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.signLimitManager = plugin.getSignLimitManager();
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Check permission
        if (!sender.hasPermission("griefprevention.admin")) {
            sender.sendMessage(plugin.getMessages().get("admin.no-permission"));
            return true;
        }
        
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        // Handle reload subcommand
        if (subCommand.equals("reload")) {
            plugin.reloadAll();
            // Delay success message to let migration logs appear first
            SchedulerAdapter.runLaterGlobal(plugin, () ->
                sender.sendMessage(plugin.getMessages().get("general.reload-success")), 20L);
            return true;
        }
        
        // Handle debug subcommand
        if (subCommand.equals("debug")) {
            boolean current = plugin.getConfig().getBoolean("debug.enabled", false);
            plugin.getConfig().set("debug.enabled", !current);
            plugin.saveConfig();
            codes.castled.gpexpansion.gp.GPBridge.setDebug(!current);
            if (!current) {
                sender.sendMessage(plugin.getMessages().get("admin.debug-enabled"));
            } else {
                sender.sendMessage(plugin.getMessages().get("admin.debug-disabled"));
            }
            return true;
        }

        if (subCommand.equals("accruals")) {
            return handleAccruals(sender, args);
        }
        
        // Handle max subcommand
        if (!subCommand.equals("max")) {
            showHelp(sender);
            return true;
        }
        
        if (args.length < 5) {
            sender.sendMessage(plugin.getMessages().get("commands.gpx-max-usage"));
            return true;
        }
        
        String type = args[1].toLowerCase();
        String action = args[2].toLowerCase();
        String playerName = args[3];
        
        if (!type.equals("sell") && !type.equals("rent") && !type.equals("mailbox") && !type.equals("self-mailboxes") && !type.equals("globals")) {
            sender.sendMessage(plugin.getMessages().get("commands.gpx-max-invalid-type"));
            return true;
        }
        
        if (!action.equals("add") && !action.equals("take") && !action.equals("set")) {
            sender.sendMessage(plugin.getMessages().get("commands.gpx-max-invalid-action"));
            return true;
        }
        
        if (args.length < 5) {
            sender.sendMessage(plugin.getMessages().get("commands.gpx-max-amount-required"));
            return true;
        }
        
        int amount;
        try {
            amount = Integer.parseInt(args[4]);
            if (amount < 0) {
                sender.sendMessage(plugin.getMessages().get("commands.gpx-max-amount-positive"));
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().get("commands.gpx-max-amount-invalid"));
            return true;
        }
        
        // Get target player
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().get("commands.player-not-online", "{player}", playerName));
            return true;
        }
        
        // Determine type
        boolean sell = type.equals("sell");
        boolean rent = type.equals("rent");
        boolean globals = type.equals("globals");
        boolean selfMailboxes = type.equals("self-mailboxes");
        
        // Process the command
        String limitType = sell ? "sell sign" : (rent ? "rent sign" : (globals ? "global claim" : (type.equals("self-mailboxes") ? "self mailbox" : "mailbox sign")));
        
        switch (action) {
            case "add":
                if (sell) {
                    signLimitManager.addSellLimit(target, amount);
                } else if (rent) {
                    signLimitManager.addRentLimit(target, amount);
                } else if (globals) {
                    signLimitManager.addGlobalClaimLimit(target, amount);
                } else if (selfMailboxes) {
                    signLimitManager.addSelfMailboxLimit(target, amount);
                } else {
                    signLimitManager.addMailboxLimit(target, amount);
                }
                sender.sendMessage(plugin.getMessages().get("commands.gpx-max-added",
                    "{amount}", String.valueOf(amount),
                    "{player}", target.getName(),
                    "{type}", limitType));
                target.sendMessage(plugin.getMessages().get("commands.gpx-max-added-player",
                    "{amount}", String.valueOf(amount),
                    "{type}", limitType));
                break;
                
            case "take":
                if (sell) {
                    signLimitManager.takeSellLimit(target, amount);
                } else if (rent) {
                    signLimitManager.takeRentLimit(target, amount);
                } else if (globals) {
                    signLimitManager.takeGlobalClaimLimit(target, amount);
                } else if (selfMailboxes) {
                    signLimitManager.takeSelfMailboxLimit(target, amount);
                } else {
                    signLimitManager.takeMailboxLimit(target, amount);
                }
                sender.sendMessage(plugin.getMessages().get("commands.gpx-max-removed",
                    "{amount}", String.valueOf(amount),
                    "{player}", target.getName(),
                    "{type}", limitType));
                target.sendMessage(plugin.getMessages().get("commands.gpx-max-removed-player",
                    "{amount}", String.valueOf(amount),
                    "{type}", limitType));
                break;
                
            case "set":
                if (sell) {
                    signLimitManager.setSellLimit(target, amount);
                } else if (rent) {
                    signLimitManager.setRentLimit(target, amount);
                } else if (globals) {
                    signLimitManager.setGlobalClaimLimit(target, amount);
                } else if (selfMailboxes) {
                    signLimitManager.setSelfMailboxLimit(target, amount);
                } else {
                    signLimitManager.setMailboxLimit(target, amount);
                }
                sender.sendMessage(plugin.getMessages().get("commands.gpx-max-set",
                    "{amount}", String.valueOf(amount),
                    "{player}", target.getName(),
                    "{type}", limitType));
                target.sendMessage(plugin.getMessages().get("commands.gpx-max-set-player",
                    "{amount}", String.valueOf(amount),
                    "{type}", limitType));
                break;
        }
        
        // Show current limits
        int currentSell = signLimitManager.getSellLimit(target);
        int currentRent = signLimitManager.getRentLimit(target);
        int currentMailbox = signLimitManager.getMailboxLimit(target);
        int currentSelfMailbox = signLimitManager.getSelfMailboxLimit(target);
        int currentGlobals = signLimitManager.getGlobalClaimLimit(target);
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-header",
            "{player}", target.getName()));
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-sell",
            "{count}", String.valueOf(currentSell)));
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-rent",
            "{count}", String.valueOf(currentRent)));
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-mailbox",
            "{count}", String.valueOf(currentMailbox)));
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-self-mailbox",
            "{count}", String.valueOf(currentSelfMailbox)));
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-globals",
            "{count}", String.valueOf(currentGlobals)));
        
        // Check for permission desync and notify
        if (signLimitManager.hasPermissionDesync(target)) {
            sender.sendMessage(plugin.getMessages().get("general.empty-line"));
            sender.sendMessage(plugin.getMessages().get("commands.gpx-max-desync-warning",
                "{player}", target.getName()));
            if (signLimitManager.isPermissionCleanupSupported()) {
                sender.sendMessage(plugin.getMessages().get("commands.gpx-max-desync-cleanup",
                    "{plugin}", signLimitManager.getSupportedPermissionPlugin()));
            } else {
                sender.sendMessage(plugin.getMessages().get("commands.gpx-max-desync-unsupported"));
                sender.sendMessage(plugin.getMessages().get("commands.gpx-max-desync-manual"));
            }
        }
        
        return true;
    }
    
    private void showHelp(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().get("admin.gpx-help-header"));
        sender.sendMessage(plugin.getMessages().get("admin.gpx-reload"));
        sender.sendMessage(plugin.getMessages().get("admin.gpx-debug"));
        sender.sendMessage(plugin.getMessages().get("admin.gpx-max"));
        sender.sendMessage(plugin.getMessages().get("admin.gpx-accruals"));
    }

    private boolean handleAccruals(CommandSender sender, String[] args) {
        PermissionManager permissionManager = plugin.getPermissionManager();
        if (permissionManager == null) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-manager-unavailable"));
            return true;
        }

        if (args.length < 2) {
            sendAccrualsUsage(sender);
            return true;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "check":
                return handleAccrualsCheck(sender, args, permissionManager);
            case "group":
                return handleAccrualsGroup(sender, args, permissionManager);
            case "player":
                return handleAccrualsPlayer(sender, args, permissionManager);
            case "creategroup":
                return handleAccrualsCreateGroup(sender, args, permissionManager);
            case "deletegroup":
                return handleAccrualsDeleteGroup(sender, args, permissionManager);
            default:
                sender.sendMessage(plugin.getMessages().get("commands.accruals-unknown-action"));
                return true;
        }
    }

    private void sendAccrualsUsage(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().get("commands.accruals-usage"));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-usage-check"));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-usage-group"));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-usage-player"));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-usage-creategroup"));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-usage-deletegroup"));
    }

    private boolean handleAccrualsCheck(CommandSender sender, String[] args, PermissionManager permissionManager) {
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(plugin.getMessages().get("commands.accruals-check-online-required", "{player}", args[2]));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-check-console-usage"));
            return true;
        }

        PermissionManager.AccrualProfile profile = permissionManager.resolveAccrualProfile(target);
        PermissionManager.AccrualOverride override = permissionManager.getPlayerAccrualOverride(target.getUniqueId());
        sender.sendMessage(plugin.getMessages().get("commands.accruals-check-header", "{player}", target.getName()));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-check-group", "{group}", profile.getName(), "{source}", profile.getSource()));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-check-blocks", "{amount}", String.valueOf(profile.getBlocksPerHour())));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-check-max-blocks", "{amount}", String.valueOf(profile.getMaxBlocks())));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-check-max-claims", "{amount}", formatMaxClaims(profile.getMaxClaims(), true)));
        sender.sendMessage(plugin.getMessages().get("commands.accruals-check-override", "{override}", (override.isEmpty() ? plugin.getMessages().getRaw("commands.accruals-none") : describeOverride(override))));
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean handleAccrualsGroup(CommandSender sender, String[] args, PermissionManager permissionManager) {
        if (args.length < 6) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-group-usage"));
            return true;
        }

        String groupName = args[2];
        if (!permissionManager.isAccrualGroup(groupName)) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-unknown-group", "{group}", groupName));
            return true;
        }

        String mode = args[3].toLowerCase();
        if (!mode.equals("set") && !mode.equals("add") && !mode.equals("remove")) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-invalid-group-action"));
            return true;
        }

        List<PermissionManager.AccrualField> fields = parseAccrualFields(args[4]);
        if (fields.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-invalid-field"));
            return true;
        }

        Integer amount = parseNonNegativeAmount(sender, args[5]);
        if (amount == null) {
            return true;
        }

        for (PermissionManager.AccrualField field : fields) {
            if (mode.equals("set")) {
                permissionManager.setAccrualGroupValue(groupName, field, amount);
            } else {
                int delta = mode.equals("add") ? amount : -amount;
                permissionManager.adjustAccrualGroupValue(groupName, field, delta);
            }
        }

        sender.sendMessage(plugin.getMessages().get("commands.accruals-group-updated",
            "{group}", groupName,
            "{fields}", describeFields(fields),
            "{action}", mode,
            "{amount}", String.valueOf(amount)));
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean handleAccrualsPlayer(CommandSender sender, String[] args, PermissionManager permissionManager) {
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-player-usage"));
            return true;
        }

        Player online = Bukkit.getPlayer(args[2]);
        org.bukkit.OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[2]);
        if (target.getName() == null && !target.hasPlayedBefore()) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-unknown-player", "{player}", args[2]));
            return true;
        }

        String mode = args[3].toLowerCase();
        if (mode.equals("reset")) {
            List<PermissionManager.AccrualField> fields = args.length >= 5 ? parseAccrualFields(args[4]) : parseAccrualFields("all");
            if (fields.isEmpty()) {
                sender.sendMessage(plugin.getMessages().get("commands.accruals-invalid-field"));
                return true;
            }
            boolean changed = false;
            for (PermissionManager.AccrualField field : fields) {
                changed |= permissionManager.clearPlayerAccrualOverride(target, field);
            }
            sender.sendMessage(plugin.getMessages().get(changed ? "commands.accruals-player-reset" : "commands.accruals-player-reset-none",
                "{fields}", describeFields(fields),
                "{player}", displayName(target, args[2])));
            return true;
        }

        if (!mode.equals("set") && !mode.equals("add") && !mode.equals("remove")) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-invalid-player-action"));
            return true;
        }
        if (args.length < 6) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-player-action-usage", "{action}", mode));
            return true;
        }

        List<PermissionManager.AccrualField> fields = parseAccrualFields(args[4]);
        if (fields.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-invalid-field"));
            return true;
        }

        Integer amount = parseNonNegativeAmount(sender, args[5]);
        if (amount == null) {
            return true;
        }

        for (PermissionManager.AccrualField field : fields) {
            int value = amount;
            if (!mode.equals("set")) {
                value = getCurrentPlayerAccrualValue(permissionManager, online, target.getUniqueId(), field);
                value = Math.max(0, value + (mode.equals("add") ? amount : -amount));
            }
            permissionManager.setPlayerAccrualOverride(target, field, value);
        }

        sender.sendMessage(plugin.getMessages().get("commands.accruals-player-updated",
            "{player}", displayName(target, args[2]),
            "{fields}", describeFields(fields),
            "{action}", mode,
            "{amount}", String.valueOf(amount)));
        if (online != null) {
            PermissionManager.AccrualProfile profile = permissionManager.resolveAccrualProfile(online);
            sender.sendMessage(plugin.getMessages().get("commands.accruals-effective-now",
                "{blocks}", String.valueOf(profile.getBlocksPerHour()),
                "{maxBlocks}", String.valueOf(profile.getMaxBlocks()),
                "{maxClaims}", formatMaxClaims(profile.getMaxClaims(), false)));
        }
        return true;
    }

    private boolean handleAccrualsCreateGroup(CommandSender sender, String[] args, PermissionManager permissionManager) {
        if (args.length < 3) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-creategroup-usage"));
            return true;
        }

        String groupName = args[2];
        if (permissionManager.isAccrualGroup(groupName)) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-group-exists", "{group}", groupName));
            return true;
        }

        Integer blocksPerHour = parseCreateValue(sender, permissionManager, groupName, PermissionManager.AccrualField.PER_HOUR, args, 3);
        Integer maxBlocks = parseCreateValue(sender, permissionManager, groupName, PermissionManager.AccrualField.MAX_BLOCKS, args, 4);
        Integer maxClaims = parseCreateValue(sender, permissionManager, groupName, PermissionManager.AccrualField.MAX_CLAIMS, args, 5);
        if (blocksPerHour == null || maxBlocks == null || maxClaims == null) {
            return true;
        }

        String permission = args.length >= 7 ? args[6] : null;
        if (permission != null && permission.trim().isEmpty()) {
            permission = null;
        }

        boolean luckPermsGroup = permissionManager.hasLuckPermsGroup(groupName);
        Integer luckPermsWeight = permissionManager.getLuckPermsWeight(groupName);
        if (!permissionManager.createAccrualGroup(groupName, blocksPerHour, maxBlocks, maxClaims, permission)) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-group-create-failed", "{group}", groupName));
            return true;
        }

        plugin.reloadAll();
        sender.sendMessage(plugin.getMessages().get("commands.accruals-group-created",
            "{group}", groupName,
            "{blocks}", String.valueOf(blocksPerHour),
            "{maxBlocks}", String.valueOf(maxBlocks),
            "{maxClaims}", formatMaxClaims(maxClaims, false)));
        if (luckPermsGroup && luckPermsWeight != null) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-luckperms-weight", "{weight}", String.valueOf(luckPermsWeight)));
        } else if (luckPermsGroup) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-luckperms-no-weight"));
        }
        return true;
    }

    private boolean handleAccrualsDeleteGroup(CommandSender sender, String[] args, PermissionManager permissionManager) {
        if (args.length < 3) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-deletegroup-usage"));
            return true;
        }

        if (!permissionManager.deleteAccrualGroup(args[2])) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-unknown-group", "{group}", args[2]));
            return true;
        }

        plugin.reloadAll();
        sender.sendMessage(plugin.getMessages().get("commands.accruals-group-deleted", "{group}", args[2]));
        return true;
    }

    private Integer parseCreateValue(CommandSender sender, PermissionManager permissionManager, String groupName,
                                     PermissionManager.AccrualField field, String[] args, int index) {
        if (args.length <= index) {
            return 0;
        }
        if ("*".equals(args[index])) {
            return permissionManager.getInheritedAccrualValueBelow(groupName, field);
        }
        return parseNonNegativeAmount(sender, args[index]);
    }

    private Integer parseNonNegativeAmount(CommandSender sender, String raw) {
        try {
            int amount = Integer.parseInt(raw);
            if (amount < 0) {
                sender.sendMessage(plugin.getMessages().get("commands.accruals-amount-nonnegative"));
                return null;
            }
            return amount;
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().get("commands.accruals-amount-number"));
            return null;
        }
    }

    private List<PermissionManager.AccrualField> parseAccrualFields(String raw) {
        if ("all".equalsIgnoreCase(raw)) {
            return Arrays.asList(PermissionManager.AccrualField.PER_HOUR,
                PermissionManager.AccrualField.MAX_BLOCKS,
                PermissionManager.AccrualField.MAX_CLAIMS);
        }

        PermissionManager.AccrualField field = PermissionManager.AccrualField.fromCommandName(raw);
        if (field == null) {
            return new ArrayList<>();
        }
        return Arrays.asList(field);
    }

    private int getCurrentPlayerAccrualValue(PermissionManager permissionManager, Player online, java.util.UUID playerId,
                                             PermissionManager.AccrualField field) {
        PermissionManager.AccrualOverride override = permissionManager.getPlayerAccrualOverride(playerId);
        switch (field) {
            case PER_HOUR:
                if (override.getBlocksPerHour() != null) {
                    return override.getBlocksPerHour();
                }
                return online != null ? permissionManager.resolveAccrualProfile(online).getBlocksPerHour() : 0;
            case MAX_BLOCKS:
                if (override.getMaxBlocks() != null) {
                    return override.getMaxBlocks();
                }
                return online != null ? permissionManager.resolveAccrualProfile(online).getMaxBlocks() : 0;
            case MAX_CLAIMS:
                if (override.getMaxClaims() != null) {
                    return override.getMaxClaims();
                }
                return online != null ? permissionManager.resolveAccrualProfile(online).getMaxClaims() : 0;
            default:
                return 0;
        }
    }

    private String displayName(org.bukkit.OfflinePlayer player, String fallback) {
        return player.getName() != null ? player.getName() : fallback;
    }

    private String describeFields(List<PermissionManager.AccrualField> fields) {
        return fields.stream()
            .map(PermissionManager.AccrualField::getCommandName)
            .collect(Collectors.joining(", "));
    }

    private String formatMaxClaims(int maxClaims, boolean disabledLabel) {
        if (maxClaims > 0) {
            return String.valueOf(maxClaims);
        }
        return plugin.getMessages().getRaw(disabledLabel ? "commands.accruals-disabled" : "commands.accruals-unlimited");
    }

    private String describeOverride(PermissionManager.AccrualOverride override) {
        List<String> parts = new ArrayList<>();
        if (override.getBlocksPerHour() != null) {
            parts.add("per-hour=" + override.getBlocksPerHour());
        }
        if (override.getMaxBlocks() != null) {
            parts.add("max-blocks=" + override.getMaxBlocks());
        }
        if (override.getMaxClaims() != null) {
            parts.add("max-claims=" + override.getMaxClaims());
        }
        return String.join(", ", parts);
    }
    
    @Override
    @SuppressWarnings("all")
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Only allow tab completion for admins
        if (!sender.hasPermission("griefprevention.admin")) {
            return new ArrayList<>();
        }
        
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            // First argument: subcommands
            for (String sub : Arrays.asList("reload", "debug", "max", "accruals")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("accruals")) {
            addMatching(completions, args[1], "check", "group", "player", "creategroup", "deletegroup");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("accruals")) {
            String partial = args[2].toLowerCase();
            if (("group".equalsIgnoreCase(args[1]) || "deletegroup".equalsIgnoreCase(args[1])) && plugin.getPermissionManager() != null) {
                for (String group : plugin.getPermissionManager().getAccrualGroupNames()) {
                    if (group.toLowerCase().startsWith(partial)) {
                        completions.add(group);
                    }
                }
            }
            if ("player".equalsIgnoreCase(args[1]) || "check".equalsIgnoreCase(args[1])) {
                completions.addAll(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList()));
            }
            if ("creategroup".equalsIgnoreCase(args[1])) {
                addMatching(completions, args[2], "[name]");
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("accruals")) {
            if ("group".equalsIgnoreCase(args[1])) {
                addMatching(completions, args[3], "set", "add", "remove");
            } else if ("player".equalsIgnoreCase(args[1])) {
                addMatching(completions, args[3], "set", "add", "remove", "reset");
            } else if ("creategroup".equalsIgnoreCase(args[1])) {
                addMatching(completions, args[3], "[blocks-per-hour]", "*", "0", "20", "50", "100");
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("accruals")) {
            if ("group".equalsIgnoreCase(args[1]) || "player".equalsIgnoreCase(args[1])) {
                addMatching(completions, args[4], "per-hour", "max-blocks", "max-claims", "all");
            } else if ("creategroup".equalsIgnoreCase(args[1])) {
                addMatching(completions, args[4], "[max-blocks]", "*", "0", "80000", "250000", "1000000");
            }
        } else if (args.length == 6 && args[0].equalsIgnoreCase("accruals")) {
            if (("group".equalsIgnoreCase(args[1]) || "player".equalsIgnoreCase(args[1])) && !"reset".equalsIgnoreCase(args[3])) {
                addMatching(completions, args[5], "0", "10", "20", "50", "100", "1000", "80000");
            } else if ("creategroup".equalsIgnoreCase(args[1])) {
                addMatching(completions, args[5], "[max-claims]", "*", "0", "10", "20");
            }
        } else if (args.length == 7 && args[0].equalsIgnoreCase("accruals") && "creategroup".equalsIgnoreCase(args[1])) {
            addMatching(completions, args[6], "[permission]");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("max")) {
            // Second argument: "sell", "rent", "mailbox", "self-mailboxes", or "globals"
            for (String type : Arrays.asList("sell", "rent", "mailbox", "self-mailboxes", "globals")) {
                if (type.startsWith(args[1].toLowerCase())) {
                    completions.add(type);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("max")) {
            // Third argument: "add", "take", or "set"
            for (String action : Arrays.asList("add", "take", "set")) {
                if (action.startsWith(args[2].toLowerCase())) {
                    completions.add(action);
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("max")) {
            // Fourth argument: player names
            String partial = args[3].toLowerCase();
            completions = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
        } else if (args.length == 5 && args[0].equalsIgnoreCase("max")) {
            // Fifth argument: amount - suggest common values
            String partial = args[4].toLowerCase();
            for (String amount : Arrays.asList("1", "5", "10", "25", "50", "100")) {
                if (amount.startsWith(partial)) {
                    completions.add(amount);
                }
            }
        }
        
        return completions;
    }

    private void addMatching(List<String> completions, String partial, String... candidates) {
        String normalized = partial.toLowerCase();
        for (String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(normalized)) {
                completions.add(candidate);
            }
        }
    }
}
