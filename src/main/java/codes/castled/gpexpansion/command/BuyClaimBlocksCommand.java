package codes.castled.gpexpansion.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.gui.BuyClaimBlocksConfirmationGUI;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /buyclaimblocks [amount] -> opens the {@link BuyClaimBlocksConfirmationGUI}.
 * Mirrors the former GP3D command flow, but the confirmation GUI lives here now.
 */
public class BuyClaimBlocksCommand implements TabExecutor {

    private static final List<String> SUGGESTED_AMOUNTS = Arrays.asList("10", "50", "100", "500", "1000");

    private final GPExpansionPlugin plugin;

    public BuyClaimBlocksCommand(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        GPBridge gp = new GPBridge();

        if (!(sender instanceof Player player)) {
            sender.sendMessage(gp.getGPMessageOr("CommandRequiresPlayer",
                    "This command can only be used by players."));
            return true;
        }

        if (!gp.isAvailable()) {
            player.sendMessage(ChatColor.RED + "GriefPrevention is not available.");
            return true;
        }
        if (!gp.isClaimBlocksEconomyEnabled()) {
            player.sendMessage(ChatColor.RED + gp.getGPMessageOr("EconomyDisabled",
                    "Economy features are disabled on this server."));
            return true;
        }
        if (!plugin.isEconomyAvailable()) {
            if (!plugin.isVaultPluginInstalled()) {
                player.sendMessage(ChatColor.RED + gp.getGPMessageOr("EconomyNoVault",
                        "Economy support requires Vault (or VaultUnlocked) to be installed."));
            } else {
                // Vault/VaultUnlocked is installed, but no economy plugin has registered a provider.
                player.sendMessage(ChatColor.RED + "Vault is installed, but no economy provider plugin"
                        + " is registered (e.g. TNE, EssentialsX, CMI). Install one and reload.");
            }
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + gp.getGPMessageOr("EconomyBuyBlocksUsage",
                    "Usage: /" + label + " <amount>"));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[0]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + gp.getGPMessageOr("EconomyInvalidAmount",
                    "Please specify a valid positive number of blocks."));
            return true;
        }

        double costPerBlock = gp.getClaimBlocksPurchaseCost();
        if (costPerBlock <= 0) {
            player.sendMessage(ChatColor.RED + "Claim block purchase cost is not configured.");
            return true;
        }
        double totalCost = amount * costPerBlock;

        if (!plugin.hasMoney(player, totalCost)) {
            String fmtCost = plugin.formatMoney(totalCost);
            String fmtBalance = plugin.formatMoney(plugin.getBalance(player));
            player.sendMessage(ChatColor.RED + gp.getGPMessageOr("EconomyNotEnoughMoney",
                    "You don't have enough money. Cost: " + fmtCost + ", Your balance: " + fmtBalance,
                    fmtCost, fmtBalance));
            return true;
        }

        if (plugin.getGUIManager() == null) {
            player.sendMessage(ChatColor.RED + "GUI subsystem is unavailable; cannot open confirmation.");
            return true;
        }
        plugin.getGUIManager().openGUI(player,
                new BuyClaimBlocksConfirmationGUI(plugin.getGUIManager(), player, amount, costPerBlock));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0];
            return SUGGESTED_AMOUNTS.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
