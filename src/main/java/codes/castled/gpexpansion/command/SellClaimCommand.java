package codes.castled.gpexpansion.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.setup.SetupWizardManager;
import codes.castled.gpexpansion.setup.SetupSession.SetupType;
import codes.castled.gpexpansion.util.Messages;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Command: /sellclaim [id]
 * Starts the sell sign setup wizard.
 */
public class SellClaimCommand implements CommandExecutor, TabCompleter {

    private static final String SELL_PERMISSION = "griefprevention.sign.create.sell";
    private static final String BUY_PERMISSION = "griefprevention.sign.create.buy";
    
    private final GPExpansionPlugin plugin;
    private final SetupWizardManager wizardManager;
    private final GPBridge gp;
    
    public SellClaimCommand(GPExpansionPlugin plugin, SetupWizardManager wizardManager) {
        this.plugin = plugin;
        this.wizardManager = wizardManager;
        this.gp = new GPBridge();
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().getRaw("general.player-only"));
            return true;
        }

        if (!plugin.getConfigManager().areSellSignsEnabled()) {
            plugin.getMessages().send(player, "sign-creation.type-disabled", "{signtype}", "sell");
            return true;
        }
        
        // Check permission
        if (!hasSellPermission(player)) {
            Messages messages = plugin.getMessages();
            messages.send(player, "permissions.create-sign-denied", "signtype", "sell");
            messages.send(player, "permissions.create-sign-denied-detail", "permission", SELL_PERMISSION + " or " + BUY_PERMISSION);
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
                // Admin-claim managers may prepare signs for ownerless admin claims.
                if (wizardManager.canManageClaimForSignCreation(player, claim)) {
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
            // Suggest owned claims plus admin claims the player is allowed to manage.
            java.util.stream.Stream<Object> claims = gp.getClaimsFor(player).stream();
            if (player.hasPermission("griefprevention.adminclaims")) {
                claims = java.util.stream.Stream.concat(claims, gp.getAdminClaims().stream());
            }
            return claims
                .map(claim -> gp.getClaimId(claim).orElse(""))
                .filter(id -> !id.isEmpty())
                .filter(id -> id.startsWith(args[0]))
                .distinct()
                .collect(Collectors.toList());
        }
        
        return Collections.emptyList();
    }

    public static boolean hasSellPermission(Player player) {
        return player.hasPermission(SELL_PERMISSION) || player.hasPermission(BUY_PERMISSION);
    }
}
