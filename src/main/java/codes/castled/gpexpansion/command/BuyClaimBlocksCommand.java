package codes.castled.gpexpansion.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
            player.sendMessage(Component.text("GriefPrevention is not available.", NamedTextColor.RED));
            return true;
        }
        if (!gp.isClaimBlocksEconomyEnabled()) {
            player.sendMessage(Component.text(gp.getGPMessageOr("EconomyDisabled",
                    "Economy features are disabled on this server."), NamedTextColor.RED));
            return true;
        }
        if (!plugin.getEconomyManager().isEconomyAvailable()) {
            if (!plugin.getEconomyManager().isVaultPluginInstalled()) {
                player.sendMessage(Component.text(gp.getGPMessageOr("EconomyNoVault",
                        "Economy support requires Vault (or VaultUnlocked) to be installed."), NamedTextColor.RED));
            } else {
                // Vault/VaultUnlocked is installed, but no economy plugin has registered a provider.
                player.sendMessage(Component.text("Vault is installed, but no economy provider plugin"
                        + " is registered (e.g. TNE, EssentialsX, CMI). Install one and reload.", NamedTextColor.RED));
            }
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(Component.text(gp.getGPMessageOr("EconomyBuyBlocksUsage",
                    "Usage: /" + label + " <amount>"), NamedTextColor.RED));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[0]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text(gp.getGPMessageOr("EconomyInvalidAmount",
                    "Please specify a valid positive number of blocks."), NamedTextColor.RED));
            return true;
        }

        double costPerBlock = gp.getClaimBlocksPurchaseCost();
        if (costPerBlock <= 0) {
            player.sendMessage(Component.text("Claim block purchase cost is not configured.", NamedTextColor.RED));
            return true;
        }
        double totalCost = amount * costPerBlock;

        if (!plugin.getEconomyManager().hasMoney(player, totalCost)) {
            String fmtCost = plugin.getEconomyManager().formatMoney(totalCost);
            String fmtBalance = plugin.getEconomyManager().formatMoney(plugin.getEconomyManager().getBalance(player));
            player.sendMessage(Component.text(gp.getGPMessageOr("EconomyNotEnoughMoney",
                    "You don't have enough money. Cost: " + fmtCost + ", Your balance: " + fmtBalance,
                    fmtCost, fmtBalance), NamedTextColor.RED));
            return true;
        }

        if (plugin.getGUIManager() == null) {
            player.sendMessage(Component.text("GUI subsystem is unavailable; cannot open confirmation.", NamedTextColor.RED));
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
