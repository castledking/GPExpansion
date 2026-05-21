package codes.castled.gpexpansion.listener;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.permission.PermissionManager;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import me.ryanhamshire.GriefPrevention.events.AccrueClaimBlocksEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimCreatedEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

public final class AccrualListener implements Listener {

    private final GPExpansionPlugin plugin;

    public AccrualListener(@NotNull GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAccrueClaimBlocks(@NotNull AccrueClaimBlocksEvent event) {
        PermissionManager permissionManager = plugin.getPermissionManager();
        if (permissionManager == null) {
            return;
        }

        PermissionManager.AccrualProfile profile = permissionManager.resolveAccrualProfile(event.getPlayer());
        event.setBlocksToAccruePerHour(profile.getBlocksPerHour());
        applyMaxBlocks(event.getPlayer(), profile);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        PermissionManager permissionManager = plugin.getPermissionManager();
        if (permissionManager == null) {
            return;
        }

        applyMaxBlocks(event.getPlayer(), permissionManager.resolveAccrualProfile(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClaimCreated(@NotNull ClaimCreatedEvent event) {
        PermissionManager permissionManager = plugin.getPermissionManager();
        if (permissionManager == null) {
            return;
        }

        CommandSender creator = event.getCreator();
        if (!(creator instanceof Player)) {
            return;
        }

        Player player = (Player) creator;
        PermissionManager.AccrualProfile profile = permissionManager.resolveAccrualProfile(player);
        int maxClaims = profile.getMaxClaims();
        if (maxClaims <= 0) {
            applyMaxBlocks(player, profile);
            return;
        }

        PlayerData playerData = GriefPrevention.instance.dataStore.getPlayerData(player.getUniqueId());
        int existingClaims = playerData.getClaims().size();
        Claim claim = event.getClaim();
        if (claim.parent != null) {
            return;
        }

        if (existingClaims >= maxClaims) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessages().get("commands.accruals-claim-limit",
                "{max}", String.valueOf(maxClaims),
                "{profile}", profile.getName()));
            return;
        }

        applyMaxBlocks(player, profile);
    }

    private void applyMaxBlocks(@NotNull Player player, @NotNull PermissionManager.AccrualProfile profile) {
        if (profile.getMaxBlocks() <= 0) {
            return;
        }

        PlayerData playerData = GriefPrevention.instance.dataStore.getPlayerData(player.getUniqueId());
        playerData.setAccruedClaimBlocksLimit(profile.getMaxBlocks());
        if (playerData.getAccruedClaimBlocks() > profile.getMaxBlocks()) {
            playerData.setAccruedClaimBlocks(profile.getMaxBlocks());
            GriefPrevention.instance.dataStore.asyncSavePlayerData(player.getUniqueId(), playerData);
        }
    }
}
