package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.scheduler.SchedulerAdapter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PaperCommandWrapper extends Command {
    private final CommandExecutor executor;
    private final TabCompleter completer;
    private final JavaPlugin plugin;

    public PaperCommandWrapper(@NotNull JavaPlugin plugin,
                             @NotNull String name,
                             String description,
                             String usageMessage,
                             List<String> aliases,
                             @NotNull CommandExecutor executor,
                             TabCompleter completer) {
        super(name, description, usageMessage, aliases);
        this.plugin = plugin;
        this.executor = executor;
        this.completer = completer;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player) {
            // For players, run on the correct region thread
            Player player = (Player) sender;
            if (SchedulerAdapter.isFolia()) {
                player.getScheduler().execute(plugin, () -> 
                    executor.onCommand(sender, this, label, args), null, 1L);
            } else {
                // Non-Folia fallback
                SchedulerAdapter.runGlobal(plugin, () -> 
                    executor.onCommand(sender, this, label, args));
            }
        } else {
            // Console or command block - execute directly
            return executor.onCommand(sender, this, label, args);
        }
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        if (completer == null) {
            return super.tabComplete(sender, alias, args);
        }

        List<String> res = completer.onTabComplete(sender, this, alias, args);
        return res != null ? res : super.tabComplete(sender, alias, args);
    }
}
