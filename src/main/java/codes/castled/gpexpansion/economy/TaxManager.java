package codes.castled.gpexpansion.economy;

import codes.castled.gpexpansion.GPExpansionPlugin;

public class TaxManager {

    private final GPExpansionPlugin plugin;
    private double taxPercent = 5.0;
    private String taxAccountName = "Tax";

    public TaxManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadTaxSettings() {
        taxPercent = plugin.getConfigManager().getTaxPercent();
        taxAccountName = plugin.getConfigManager().getTaxAccountName();
        if (taxPercent > 0) {
            plugin.getLogger().info("Tax enabled: " + taxPercent + "% to account '" + taxAccountName + "'");
        }
    }

    public double getTaxPercent() {
        return taxPercent;
    }

    public String getTaxAccountName() {
        return taxAccountName;
    }

    public boolean isTaxEnabled() {
        return taxPercent > 0 && plugin.getEconomyManager().isEconomyAvailable();
    }

    public double calculateTax(double amount) {
        if (taxPercent <= 0) return 0;
        return amount * (taxPercent / 100.0);
    }
}
