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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Command: /sellclaim [id]
 * Starts the sell sign setup wizard.
 */
public class SellClaimCommand implements CommandExecutor, TabCompleter {
    
    private final SetupWizardManager wizardManager;
    private final GPBridge gp;
    
    public SellClaimCommand(GPExpansionPlugin plugin, SetupWizardManager wizardManager) {
        this.wizardManager = wizardManager;
        this.gp = new GPBridge();
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return true;
        }
        
        // Check permission
        if (!player.hasPermission("griefprevention.sign.create.buy")) {
            player.sendMessage(Component.text("You don't have permission to create sell signs.", NamedTextColor.RED));
            return true;
        }
        
        String claimId = null;
        
        // Check if ID was provided as argument
        if (args.length > 0) {
            claimId = args[0];
        } else {
            // Try to resolve from player's current location
            Optional<Object> claimAtLocation = gp.getClaimAt(player.getLocation());
            if (claimAtLocation.isPresent()) {
                Object claim = claimAtLocation.get();
                // Check if player owns this claim
                if (gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin")) {
                    claimId = gp.getClaimId(claim).orElse(null);
                }
            }
        }
        
        // Start the wizard
        wizardManager.startSession(player, SetupType.SELL, claimId);
        return true;
    }
    
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        
        if (args.length == 1) {
            // Suggest claim IDs owned by the player
            return gp.getClaimsFor(player).stream()
                .map(claim -> gp.getClaimId(claim).orElse(""))
                .filter(id -> !id.isEmpty())
                .filter(id -> id.startsWith(args[0]))
                .collect(Collectors.toList());
        }
        
        return Collections.emptyList();
    }
}
