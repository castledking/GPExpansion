package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.setup.SetupWizardManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Command: /cancelsetup
 * Cancels pending auto-paste mode from the setup wizard.
 */
public class CancelSetupCommand implements CommandExecutor, TabCompleter {
    
    private final SetupWizardManager wizardManager;
    
    public CancelSetupCommand(SetupWizardManager wizardManager) {
        this.wizardManager = wizardManager;
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return true;
        }
        
        // Try to cancel pending auto-paste first
        if (wizardManager.cancelPendingAutoPaste(player.getUniqueId())) {
            player.sendMessage(Component.text("Auto-paste mode cancelled. You can now place signs normally.", NamedTextColor.YELLOW));
            return true;
        }
        
        // Also try to cancel active wizard session
        if (wizardManager.hasActiveSession(player.getUniqueId())) {
            wizardManager.cancelSession(player.getUniqueId());
            player.sendMessage(Component.text("Setup wizard cancelled.", NamedTextColor.YELLOW));
            return true;
        }
        
        player.sendMessage(Component.text("You don't have an active setup or auto-paste mode to cancel.", NamedTextColor.GRAY));
        return true;
    }
    
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
