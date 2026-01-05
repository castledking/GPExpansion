package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.setup.SetupSession.SetupType;
import dev.towki.gpexpansion.setup.SetupWizardManager;
import dev.towki.gpexpansion.util.Messages;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command: /mailbox [id]
 * Starts the mailbox setup wizard or shows help.
 */
public class MailboxCommand implements CommandExecutor, TabCompleter {
    
    private final GPExpansionPlugin plugin;
    private SetupWizardManager wizardManager;
    private final GPBridge gp;
    
    public MailboxCommand(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.gp = new GPBridge();
    }
    
    /**
     * Set the wizard manager (called after plugin initialization)
     */
    public void setWizardManager(SetupWizardManager wizardManager) {
        this.wizardManager = wizardManager;
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // If not a player or no wizard manager, show help
        if (!(sender instanceof Player player) || wizardManager == null) {
            showHelp(sender);
            return true;
        }
        
        // Check permission
        if (!player.hasPermission("griefprevention.sign.create.mailbox")) {
            Messages messages = plugin.getMessages();
            messages.send(player, "permissions.create-sign-denied", "signtype", "mailbox");
            messages.send(player, "permissions.create-sign-denied-detail", "permission", "griefprevention.sign.create.mailbox");
            return true;
        }
        
        // If "help" argument, show help
        if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
            showHelp(sender);
            return true;
        }
        
        String claimId = null;
        
        // Check if ID was provided as argument
        if (args.length > 0) {
            claimId = args[0];
        }
        // Note: Mailbox doesn't auto-resolve from location since it requires a 1x1x1 3D subdivision
        
        // Start the wizard
        wizardManager.startSession(player, SetupType.MAILBOX, claimId);
        return true;
    }
    
    private void showHelp(CommandSender sender) {
        Messages messages = plugin.getMessages();
        if (sender instanceof Player player) {
            messages.send(player, "commands.mailbox-help-title");
            messages.send(player, "commands.mailbox-help-step1");
            messages.send(player, "commands.mailbox-help-step2");
            messages.send(player, "commands.mailbox-help-step3");
            messages.send(player, "commands.mailbox-help-step4");
            player.sendMessage("");
            messages.send(player, "commands.mailbox-commands-title");
            messages.send(player, "commands.mailbox-cmd-setup");
            messages.send(player, "commands.mailbox-cmd-help");
        } else {
            // Console fallback
            sender.sendMessage(messages.getRaw("commands.mailbox-help-title"));
            sender.sendMessage(messages.getRaw("commands.mailbox-help-step1"));
            sender.sendMessage(messages.getRaw("commands.mailbox-help-step2"));
            sender.sendMessage(messages.getRaw("commands.mailbox-help-step3"));
            sender.sendMessage(messages.getRaw("commands.mailbox-help-step4"));
            sender.sendMessage("");
            sender.sendMessage(messages.getRaw("commands.mailbox-commands-title"));
            sender.sendMessage(messages.getRaw("commands.mailbox-cmd-setup"));
            sender.sendMessage(messages.getRaw("commands.mailbox-cmd-help"));
        }
    }
    
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        
        if (args.length == 1) {
            // Suggest claim IDs owned by the player plus "help"
            List<String> suggestions = gp.getClaimsFor(player).stream()
                .map(claim -> gp.getClaimId(claim).orElse(""))
                .filter(id -> !id.isEmpty())
                .filter(id -> id.startsWith(args[0]))
                .collect(Collectors.toList());
            
            if ("help".startsWith(args[0].toLowerCase())) {
                suggestions.add("help");
            }
            
            return suggestions;
        }
        
        return Collections.emptyList();
    }
}
