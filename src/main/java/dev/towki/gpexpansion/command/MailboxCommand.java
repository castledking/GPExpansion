package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.setup.SetupSession.SetupType;
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
import java.util.stream.Collectors;

/**
 * Command: /mailbox [id]
 * Starts the mailbox setup wizard or shows help.
 */
public class MailboxCommand implements CommandExecutor, TabCompleter {
    
    private SetupWizardManager wizardManager;
    private final GPBridge gp;
    
    public MailboxCommand(GPExpansionPlugin plugin) {
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
            player.sendMessage(Component.text("You don't have permission to create mailbox signs.", NamedTextColor.RED));
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
        sender.sendMessage(Component.text("Mailbox Usage:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("1. Create a 1x1x1 3D subdivision claim", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("2. Place a container (chest/barrel) inside it", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("3. Run /mailbox <claimId> to start the setup wizard", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("4. Or create a [Mailbox] sign manually", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Commands:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mailbox <id> - Start setup wizard", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/mailbox help - Show this help", NamedTextColor.GRAY));
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
