package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.confirm.ConfirmationService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ConfirmCommand implements CommandExecutor, TabCompleter {

    private final GPExpansionPlugin plugin;

    public ConfirmCommand(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this.");
            return true;
        }
        Player player = (Player) sender;

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <token> <accept|cancel>");
            return true;
        }

        String token = args[0];
        String action = args[1].toLowerCase(Locale.ROOT);
        boolean accept;
        if (action.equals("accept") || action.equals("confirm") || action.equals("yes")) {
            accept = true;
        } else if (action.equals("cancel") || action.equals("deny") || action.equals("no")) {
            accept = false;
        } else {
            sender.sendMessage(ChatColor.RED + "Second argument must be 'accept' or 'cancel'.");
            return true;
        }

        ConfirmationService svc = plugin.getConfirmationService();
        if (svc == null || !svc.handle(player, token, accept)) {
            sender.sendMessage(ChatColor.RED + "Invalid or expired confirmation token.");
            return true;
        }

        // Success path handled by ConfirmationService; nothing else to say here.
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 2) {
            return Arrays.asList("accept", "cancel");
        }
        return Collections.emptyList();
    }
}
