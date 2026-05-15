package codes.castled.gpexpansion.permission;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

public class PermissionService {

    private Permission vaultPermission;

    public PermissionService() {
    }

    @SuppressWarnings("all")
    public void setupVaultPermission() {
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
                RegisteredServiceProvider<Permission> rsp =
                    Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class);
                if (rsp != null && rsp.getProvider() != null) {
                    vaultPermission = rsp.getProvider();
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"null", "deprecation"})
    public boolean hasRestoreSnapshotPermission(OfflinePlayer player) {
        if (player.isOnline() && player.getPlayer() != null) {
            if (player.getPlayer().hasPermission(codes.castled.gpexpansion.storage.ClaimSnapshotStore.getPermission())) {
                return true;
            }
        }
        if (vaultPermission != null) {
            try {
                String worldName = null;
                if (player.getPlayer() != null && player.getPlayer().getWorld() != null) {
                    worldName = player.getPlayer().getWorld().getName();
                } else if (!Bukkit.getWorlds().isEmpty()) {
                    worldName = Bukkit.getWorlds().get(0).getName();
                }
                String playerName = player.getName();
                if (playerName != null && !playerName.isEmpty()) {
                    return vaultPermission.playerHas(worldName, playerName, codes.castled.gpexpansion.storage.ClaimSnapshotStore.getPermission());
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
