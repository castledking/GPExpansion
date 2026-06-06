package codes.castled.gpexpansion.economy;

import codes.castled.gpexpansion.GPExpansionPlugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;

public class EconomyManager {

    private static final String VAULT2_PLUGIN_NAME = "GPExpansion";
    private static final String CURRENCY_SYMBOLS = "$€£¥₩₽₹₺₫₴₦₱₪₡₲₵₸₭₮₨";

    private final GPExpansionPlugin plugin;
    private Economy economy;
    private Object economyV2;
    private Class<?> economyV2Class;

    public EconomyManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    public void setupEconomy() {
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
                @SuppressWarnings("all")
                RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
                if (rsp != null) {
                    economy = rsp.getProvider();
                    plugin.getLogger().info("Hooked into Vault economy (legacy): " + economy.getName());
                }
                if (economy == null) {
                    @SuppressWarnings("all")
                    java.util.Collection<RegisteredServiceProvider<Economy>> regs = Bukkit.getServicesManager().getRegistrations(Economy.class);
                    if (regs != null && !regs.isEmpty()) {
                        RegisteredServiceProvider<Economy> pick = regs.iterator().next();
                        if (pick != null && pick.getProvider() != null) {
                            economy = pick.getProvider();
                            plugin.getLogger().info("Hooked into Vault economy (legacy via scan): " + economy.getName());
                        }
                    }
                }
                if (economy == null) {
                    try {
                        Class<?> legacyEcoClass = Class.forName("net.milkbowl.vault.economy.Economy");
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        RegisteredServiceProvider<?> rsp2 = (RegisteredServiceProvider) Bukkit.getServicesManager().getRegistration((Class) legacyEcoClass);
                        if (rsp2 == null) {
                            @SuppressWarnings({"rawtypes", "unchecked"})
                            java.util.Collection<RegisteredServiceProvider<?>> regs2 = (java.util.Collection) Bukkit.getServicesManager().getRegistrations((Class) legacyEcoClass);
                            if (regs2 != null && !regs2.isEmpty()) {
                                rsp2 = regs2.iterator().next();
                            }
                        }
                        if (rsp2 != null && rsp2.getProvider() != null) {
                            this.economyV2Class = legacyEcoClass;
                            this.economyV2 = rsp2.getProvider();
                            plugin.getLogger().info("Hooked into Vault economy via reflective legacy bridge: " + economyV2.getClass().getName());
                        }
                    } catch (Throwable ignored2) { }
                }
            }
        } catch (NoClassDefFoundError ignored) {
        } catch (Throwable t) {
            plugin.getLogger().warning("Error while hooking legacy Vault: " + t.getMessage());
        }

        if (economy == null) {
            try {
                economyV2Class = Class.forName("net.milkbowl.vault2.economy.Economy");
                @SuppressWarnings({"rawtypes", "unchecked"})
                RegisteredServiceProvider<?> rsp2 = (RegisteredServiceProvider) Bukkit.getServicesManager().getRegistration((Class) economyV2Class);
                if (rsp2 != null) {
                    economyV2 = rsp2.getProvider();
                    plugin.getLogger().info("Hooked into Vault economy (modern v2): " + economyV2.getClass().getName());
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to hook Vault v2 economy: " + t.getMessage());
            }
        }

        if (this.economy == null && this.economyV2 == null) {
            try {
                org.bukkit.plugin.ServicesManager sm = Bukkit.getServicesManager();
                java.util.Collection<Class<?>> known = sm.getKnownServices();
                if (known != null) {
                    for (Class<?> svc : known) {
                        String name = svc.getName();
                        if ("net.milkbowl.vault.economy.Economy".equals(name) || "net.milkbowl.vault2.economy.Economy".equals(name)) {
                            @SuppressWarnings({"rawtypes", "unchecked"})
                            RegisteredServiceProvider<?> reg = (RegisteredServiceProvider) sm.getRegistration((Class) svc);
                            if (reg != null && reg.getProvider() != null) {
                                this.economyV2Class = svc;
                                this.economyV2 = reg.getProvider();
                                plugin.getLogger().info("Hooked into economy via known-services scan: " + svc.getName() + " -> " + economyV2.getClass().getName());
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().fine("Known-services scan failed: " + t.getMessage());
            }
        }
    }

    public void refreshEconomy() {
        setupEconomy();
    }

    public boolean isEconomyAvailable() {
        boolean ok = economy != null || economyV2 != null;
        if (!ok) {
            plugin.getLogger().fine("Economy queried but no provider hooked yet.");
        } else {
            plugin.getLogger().fine("Economy available via: " + (economy != null ? ("Vault legacy - " + economy.getName()) : ("reflective/modern - " + economyV2.getClass().getName())));
        }
        return ok;
    }

    public boolean isVaultPluginInstalled() {
        return Bukkit.getPluginManager().getPlugin("Vault") != null
                || Bukkit.getPluginManager().getPlugin("VaultUnlocked") != null;
    }

    public String formatMoney(double amount) {
        try {
            if (economy != null) return economy.format(amount);
            if (economyV2 != null && economyV2Class != null) {
                BigDecimal bd = BigDecimal.valueOf(amount);
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("format", BigDecimal.class);
                    Object out = m.invoke(economyV2, bd);
                    if (out != null) return out.toString();
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("format", double.class);
                    Object out = m.invoke(economyV2, amount);
                    if (out != null) return out.toString();
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) { }
        return String.format(java.util.Locale.US, "%,.2f", amount);
    }

    public String formatMoneyForSign(double amount) {
        String formatted = formatMoney(amount);
        if (formatted == null || formatted.isEmpty()) return "$" + compactAmount(amount);
        if (amount == Math.floor(amount) && formatted.contains(".00")) {
            formatted = formatted.replace(".00", "");
        }
        boolean hasSymbol = false;
        for (int i = 0; i < CURRENCY_SYMBOLS.length(); i++) {
            if (formatted.indexOf(CURRENCY_SYMBOLS.charAt(i)) >= 0) {
                hasSymbol = true;
                break;
            }
        }
        if (!hasSymbol) return "$" + compactAmount(amount);
        return formatted;
    }

    public static String getCurrencySymbolsForParsing() {
        return CURRENCY_SYMBOLS;
    }

    private String compactAmount(double amount) {
        return amount == Math.floor(amount)
            ? String.format(java.util.Locale.US, "%,.0f", amount)
            : String.format(java.util.Locale.US, "%,.2f", amount);
    }

    public double getBalance(OfflinePlayer player) {
        try {
            if (economy != null) return economy.getBalance(player);
            if (economyV2 != null && economyV2Class != null) {
                java.util.UUID uuid = player.getUniqueId();
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("getBalance", String.class, java.util.UUID.class);
                    Object out = m.invoke(economyV2, VAULT2_PLUGIN_NAME, uuid);
                    if (out instanceof Number n) return n.doubleValue();
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("balance", String.class, java.util.UUID.class);
                    Object out = m.invoke(economyV2, VAULT2_PLUGIN_NAME, uuid);
                    if (out instanceof Number n) return n.doubleValue();
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("getBalance", OfflinePlayer.class);
                    Object out = m.invoke(economyV2, player);
                    if (out instanceof Number n) return n.doubleValue();
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("balance", OfflinePlayer.class);
                    Object out = m.invoke(economyV2, player);
                    if (out instanceof Number n) return n.doubleValue();
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) { }
        return 0.0D;
    }

    public boolean hasMoney(OfflinePlayer player, double amount) {
        try {
            if (economy != null) return economy.has(player, amount);
            if (economyV2 != null && economyV2Class != null) {
                java.util.UUID uuid = player.getUniqueId();
                BigDecimal bd = BigDecimal.valueOf(amount);
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("has", String.class, java.util.UUID.class, BigDecimal.class);
                    Object out = m.invoke(economyV2, VAULT2_PLUGIN_NAME, uuid, bd);
                    if (out instanceof Boolean) return (Boolean) out;
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("has", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    if (out instanceof Boolean) return (Boolean) out;
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("hasBalance", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    if (out instanceof Boolean) return (Boolean) out;
                } catch (NoSuchMethodException ignored) {}
                return getBalance(player) >= amount;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    public boolean withdrawMoney(OfflinePlayer player, double amount) {
        try {
            if (economy != null) {
                net.milkbowl.vault.economy.EconomyResponse resp = economy.withdrawPlayer(player, amount);
                return resp != null && resp.transactionSuccess();
            }
            if (economyV2 != null && economyV2Class != null) {
                java.util.UUID uuid = player.getUniqueId();
                BigDecimal bd = BigDecimal.valueOf(amount);
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("withdraw", String.class, java.util.UUID.class, BigDecimal.class);
                    Object out = m.invoke(economyV2, VAULT2_PLUGIN_NAME, uuid, bd);
                    return interpretEconomyResponse(out);
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("withdraw", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    return interpretEconomyResponse(out);
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    return interpretEconomyResponse(out);
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) { }
        return false;
    }

    public boolean depositMoney(Player player, double amount) {
        try {
            if (economy != null) {
                net.milkbowl.vault.economy.EconomyResponse resp = economy.depositPlayer(player, amount);
                return resp != null && resp.transactionSuccess();
            }
            if (economyV2 != null && economyV2Class != null) {
                java.util.UUID uuid = player.getUniqueId();
                BigDecimal bd = BigDecimal.valueOf(amount);
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("deposit", String.class, java.util.UUID.class, BigDecimal.class);
                    Object out = m.invoke(economyV2, VAULT2_PLUGIN_NAME, uuid, bd);
                    return interpretEconomyResponse(out);
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("deposit", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    return interpretEconomyResponse(out);
                } catch (NoSuchMethodException ignored) {}
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    return interpretEconomyResponse(out);
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) { }
        return false;
    }

    public boolean depositToAccount(String accountName, double amount) {
        if (!isEconomyAvailable() || amount <= 0) return false;
        try {
            if (economy != null) {
                OfflinePlayer fakePlayer = Bukkit.getOfflinePlayer(accountName);
                if (!economy.hasAccount(fakePlayer)) {
                    economy.createPlayerAccount(fakePlayer);
                }
                net.milkbowl.vault.economy.EconomyResponse resp = economy.depositPlayer(fakePlayer, amount);
                return resp != null && resp.transactionSuccess();
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to deposit " + amount + " to account '" + accountName + "': " + e.getMessage());
        }
        return false;
    }

    public boolean depositToBank(String bankName, double amount) {
        if (!isEconomyAvailable() || amount <= 0 || bankName == null || bankName.isBlank()) return false;
        try {
            if (economy != null) {
                net.milkbowl.vault.economy.EconomyResponse resp = economy.bankDeposit(bankName, amount);
                return resp != null && resp.transactionSuccess();
            }
            if (economyV2 != null && economyV2Class != null) {
                BigDecimal bd = BigDecimal.valueOf(amount);
                for (String methodName : new String[]{"bankDeposit", "depositBank", "depositToBank"}) {
                    try {
                        java.lang.reflect.Method m = economyV2Class.getMethod(methodName, String.class, BigDecimal.class);
                        Object out = m.invoke(economyV2, bankName, bd);
                        return interpretEconomyResponse(out);
                    } catch (NoSuchMethodException ignored) {}
                    try {
                        java.lang.reflect.Method m = economyV2Class.getMethod(methodName, String.class, double.class);
                        Object out = m.invoke(economyV2, bankName, amount);
                        return interpretEconomyResponse(out);
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to deposit " + amount + " to bank '" + bankName + "': " + e.getMessage());
        }
        return false;
    }

    private boolean interpretEconomyResponse(Object out) {
        if (out == null) return true;
        if (out instanceof Boolean) return (Boolean) out;
        for (String method : new String[]{"transactionSuccess", "isSuccessful", "success", "wasSuccessful"}) {
            try {
                java.lang.reflect.Method ok = out.getClass().getMethod(method);
                Object b = ok.invoke(out);
                if (b instanceof Boolean) return (Boolean) b;
            } catch (ReflectiveOperationException ignored) {}
        }
        try {
            java.lang.reflect.Field typeField = out.getClass().getField("type");
            Object type = typeField.get(out);
            if (type != null) return "SUCCESS".equalsIgnoreCase(type.toString());
        } catch (ReflectiveOperationException ignored) {}
        return true;
    }
}
