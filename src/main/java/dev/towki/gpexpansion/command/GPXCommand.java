package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.permission.SignLimitManager;
import dev.towki.gpexpansion.scheduler.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
            dev.towki.gpexpansion.gp.GPBridge.setDebug(!current);
            if (!current) {
                sender.sendMessage(plugin.getMessages().get("admin.debug-enabled"));
            } else {
                sender.sendMessage(plugin.getMessages().get("admin.debug-disabled"));
            }
            return true;
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
        
        if (!type.equals("sell") && !type.equals("rent") && !type.equals("mailbox") && !type.equals("globals")) {
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
        
        // Process the command
        String limitType = sell ? "sell sign" : (rent ? "rent sign" : (globals ? "global claim" : "mailbox sign"));
        
        switch (action) {
            case "add":
                if (sell) {
                    signLimitManager.addSellLimit(target, amount);
                } else if (rent) {
                    signLimitManager.addRentLimit(target, amount);
                } else if (globals) {
                    signLimitManager.addGlobalClaimLimit(target, amount);
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
        int currentGlobals = signLimitManager.getGlobalClaimLimit(target);
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-header",
            "{player}", target.getName()));
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-sell",
            "{count}", String.valueOf(currentSell)));
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-rent",
            "{count}", String.valueOf(currentRent)));
        sender.sendMessage(plugin.getMessages().get("commands.gpx-max-current-mailbox",
            "{count}", String.valueOf(currentMailbox)));
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
    }
    
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Only allow tab completion for admins
        if (!sender.hasPermission("griefprevention.admin")) {
            return new ArrayList<>();
        }
        
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            // First argument: subcommands
            for (String sub : Arrays.asList("reload", "debug", "max")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("max")) {
            // Second argument: "sell", "rent", "mailbox", or "globals"
            for (String type : Arrays.asList("sell", "rent", "mailbox", "globals")) {
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
}
