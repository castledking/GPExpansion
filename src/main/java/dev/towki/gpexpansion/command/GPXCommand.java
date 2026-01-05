package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.permission.SignLimitManager;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
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
            sender.sendMessage(plugin.getMessages().get("general.reload-success"));
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
            sender.sendMessage(Component.text("Usage: /gpx max <sell|rent|mailbox|globals> <add|take|set> <player> <amount>", NamedTextColor.RED));
            return true;
        }
        
        String type = args[1].toLowerCase();
        String action = args[2].toLowerCase();
        String playerName = args[3];
        
        if (!type.equals("sell") && !type.equals("rent") && !type.equals("mailbox") && !type.equals("globals")) {
            sender.sendMessage(Component.text("Invalid type. Use 'sell', 'rent', 'mailbox', or 'globals'.", NamedTextColor.RED));
            return true;
        }
        
        if (!action.equals("add") && !action.equals("take") && !action.equals("set")) {
            sender.sendMessage(Component.text("Invalid action. Use 'add', 'take', or 'set'.", NamedTextColor.RED));
            return true;
        }
        
        if (args.length < 5) {
            sender.sendMessage(Component.text("Please specify an amount.", NamedTextColor.RED));
            return true;
        }
        
        int amount;
        try {
            amount = Integer.parseInt(args[4]);
            if (amount < 0) {
                sender.sendMessage(Component.text("Amount must be positive.", NamedTextColor.RED));
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid amount.", NamedTextColor.RED));
            return true;
        }
        
        // Get target player
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("Player '" + playerName + "' is not online.", NamedTextColor.RED));
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
                sender.sendMessage(Component.text("Added " + amount + " to " + target.getName() + "'s " + limitType + " limit.", NamedTextColor.GREEN));
                target.sendMessage(Component.text("Your " + limitType + " limit has been increased by " + amount + ".", NamedTextColor.YELLOW));
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
                sender.sendMessage(Component.text("Removed " + amount + " from " + target.getName() + "'s " + limitType + " limit.", NamedTextColor.GREEN));
                target.sendMessage(Component.text("Your " + limitType + " limit has been decreased by " + amount + ".", NamedTextColor.YELLOW));
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
                sender.sendMessage(Component.text("Set " + target.getName() + "'s " + limitType + " limit to " + amount + ".", NamedTextColor.GREEN));
                target.sendMessage(Component.text("Your " + limitType + " limit has been set to " + amount + ".", NamedTextColor.YELLOW));
                break;
        }
        
        // Show current limits
        int currentSell = signLimitManager.getSellLimit(target);
        int currentRent = signLimitManager.getRentLimit(target);
        int currentMailbox = signLimitManager.getMailboxLimit(target);
        int currentGlobals = signLimitManager.getGlobalClaimLimit(target);
        sender.sendMessage(Component.text(target.getName() + "'s current limits:", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  Sell signs: " + currentSell, NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  Rent signs: " + currentRent, NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  Mailbox signs: " + currentMailbox, NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  Global claims: " + currentGlobals, NamedTextColor.AQUA));
        
        // Check for permission desync and notify
        if (signLimitManager.hasPermissionDesync(target)) {
            sender.sendMessage(Component.text("", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("Warning: Permission desync detected for " + target.getName() + "!", NamedTextColor.YELLOW));
            if (signLimitManager.isPermissionCleanupSupported()) {
                sender.sendMessage(Component.text("Permissions will be automatically cleaned up using " + 
                    signLimitManager.getSupportedPermissionPlugin() + ".", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("No supported permission plugin found for automatic cleanup.", NamedTextColor.RED));
                sender.sendMessage(Component.text("Please manually remove duplicate permissions and set the highest one.", NamedTextColor.RED));
            }
        }
        
        return true;
    }
    
    private void showHelp(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().get("admin.gpx-help-header"));
        sender.sendMessage(plugin.getMessages().get("admin.gpx-reload"));
        sender.sendMessage(plugin.getMessages().get("admin.gpx-debug"));
        sender.sendMessage(Component.text("/gpx max <sell|rent|mailbox|globals> <add|take|set> <player> <amount>", NamedTextColor.YELLOW)
            .append(Component.text(" - Manage limits", NamedTextColor.GRAY)));
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
