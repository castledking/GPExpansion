package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

public class EconomyHookListener implements Listener {
    private final GPExpansionPlugin plugin;

    public EconomyHookListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        try {
            Class<?> service = event.getProvider().getService();
            // Legacy Vault
            if (service.getName().equals("net.milkbowl.vault.economy.Economy")) {
                @SuppressWarnings("unchecked")
                RegisteredServiceProvider<?> rsp = (RegisteredServiceProvider<?>) event.getProvider();
                if (rsp.getProvider() != null) {
                    plugin.getLogger().info("Economy service (legacy Vault) registered late. Refreshing hook...");
                    plugin.setupEconomy();
                }
            }
            // Vault 2
            if (service.getName().equals("net.milkbowl.vault2.economy.Economy")) {
                @SuppressWarnings("unchecked")
                RegisteredServiceProvider<?> rsp = (RegisteredServiceProvider<?>) event.getProvider();
                if (rsp.getProvider() != null) {
                    plugin.getLogger().info("Economy service (Vault v2) registered late. Refreshing hook...");
                    plugin.setupEconomy();
                }
            }
        } catch (Throwable ignored) {}
    }
}
