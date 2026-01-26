package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.setup.SetupWizardManager;

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
            sender.sendMessage(wizardManager.getPlugin().getMessages().get("general.player-only"));
            return true;
        }
        
        // Try to cancel pending auto-paste first
        if (wizardManager.cancelPendingAutoPaste(player.getUniqueId())) {
            wizardManager.getPlugin().getMessages().send(player, "wizard.auto-paste-cancelled");
            return true;
        }
        
        // Also try to cancel active wizard session
        if (wizardManager.hasActiveSession(player.getUniqueId())) {
            wizardManager.cancelSession(player.getUniqueId());
            wizardManager.getPlugin().getMessages().send(player, "wizard.cancelled");
            return true;
        }
        
        wizardManager.getPlugin().getMessages().send(player, "wizard.cancel-none");
        return true;
    }
    
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
