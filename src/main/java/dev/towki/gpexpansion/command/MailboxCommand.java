package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.GPExpansionPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class MailboxCommand implements CommandExecutor, TabCompleter {
    
    public MailboxCommand(GPExpansionPlugin plugin) {
        // No dependencies needed - this is just a help command
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        sender.sendMessage(Component.text("Mailbox Usage:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("1. Create a 1x1x1 3D subdivision claim", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("2. Place a container (chest/barrel) inside it", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("3. Create a [Mailbox] sign anywhere referencing the claim ID", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("4. Right-click the sign to purchase or access the mailbox", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Sign format: [Mailbox];<claimId>;<ecoKind>;<price>", NamedTextColor.YELLOW));
        return true;
    }
    
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
