package codes.castled.gpexpansion.economy;

import codes.castled.gpexpansion.GPExpansionPlugin;
import org.bukkit.entity.Player;

public class TaxManager {

    private final GPExpansionPlugin plugin;
    private double taxPercent = 5.0;
    private String taxAccountName = "Tax";
    private boolean enabled = true;
    private String exemptPermission = "griefprevention.tax.exempt";
    private String depositMode = "npc-account";
    private String roundMode = "nearest";
    private double minimumTax = 0D;

    public TaxManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    public enum Context {
        RENT,
        SELL,
        MAILBOX,
        CLAIM_BLOCK_PURCHASE
    }

    public static final class TaxResult {
        public final double gross;
        public final double tax;
        public final double net;

        private TaxResult(double gross, double tax) {
            this.gross = gross;
            this.tax = Math.max(0D, Math.min(gross, tax));
            this.net = Math.max(0D, gross - this.tax);
        }
    }

    public void loadTaxSettings() {
        enabled = plugin.getConfigManager().isTaxEnabled();
        taxPercent = plugin.getConfigManager().getTaxPercent();
        taxAccountName = plugin.getConfigManager().getTaxAccountName();
        exemptPermission = plugin.getConfigManager().getTaxExemptPermission();
        depositMode = plugin.getConfigManager().getTaxDepositMode();
        roundMode = plugin.getConfigManager().getTaxRoundMode();
        minimumTax = plugin.getConfigManager().getMinimumTax();
        if (enabled && taxPercent > 0) {
            plugin.getLogger().info("Tax enabled: " + taxPercent + "% (" + depositMode + ")");
        }
    }

    public double getTaxPercent() {
        return taxPercent;
    }

    public String getTaxAccountName() {
        return taxAccountName;
    }

    public boolean isTaxEnabled() {
        return enabled && taxPercent > 0 && plugin.getEconomyManager().isEconomyAvailable();
    }

    public double calculateTax(double amount) {
        return calculateTax(amount, Context.RENT, null).tax;
    }

    public TaxResult calculateTax(double amount, Context context, Player exemptCandidate) {
        if (amount <= 0D || !isTaxEnabled() || !appliesTo(context) || isExempt(exemptCandidate)) {
            return new TaxResult(Math.max(0D, amount), 0D);
        }
        double tax = amount * (taxPercent / 100.0D);
        if (tax > 0D && minimumTax > 0D) {
            tax = Math.max(tax, minimumTax);
        }
        tax = roundTax(tax);
        return new TaxResult(amount, tax);
    }

    public boolean depositTax(double amount) {
        if (amount <= 0D || !plugin.getEconomyManager().isEconomyAvailable()) return true;
        return switch (depositMode) {
            case "void" -> true;
            case "server-bank" -> plugin.getEconomyManager().depositToBank(taxAccountName, amount)
                || plugin.getEconomyManager().depositToAccount(taxAccountName, amount);
            case "npc-account", "account" -> plugin.getEconomyManager().depositToAccount(taxAccountName, amount);
            default -> plugin.getEconomyManager().depositToAccount(taxAccountName, amount);
        };
    }

    private boolean appliesTo(Context context) {
        return switch (context) {
            case RENT -> plugin.getConfigManager().doesTaxApplyToRent();
            case SELL -> plugin.getConfigManager().doesTaxApplyToSell();
            case MAILBOX -> plugin.getConfigManager().doesTaxApplyToMailbox();
            case CLAIM_BLOCK_PURCHASE -> plugin.getConfigManager().doesTaxApplyToClaimBlockPurchases();
        };
    }

    private boolean isExempt(Player player) {
        return player != null && exemptPermission != null && !exemptPermission.isBlank() && player.hasPermission(exemptPermission);
    }

    private double roundTax(double tax) {
        double cents = tax * 100D;
        double rounded = switch (roundMode) {
            case "floor" -> Math.floor(cents);
            case "ceil", "ceiling" -> Math.ceil(cents);
            default -> Math.round(cents);
        };
        return rounded / 100D;
    }
}
