package codes.castled.gpexpansion.listener;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.permission.PermissionManager;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import me.ryanhamshire.GriefPrevention.events.AccrueClaimBlocksEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimCreatedEvent;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.metadata.MetadataValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

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

        Player player = event.getPlayer();
        if (shouldPauseAccrual(player)) {
            event.setBlocksToAccruePerHour(0);
            return;
        }

        PermissionManager.AccrualProfile profile = permissionManager.resolveAccrualProfile(player);
        event.setBlocksToAccruePerHour(applyWorldMultiplier(player, profile.getBlocksPerHour()));
        applyMaxBlocks(player, profile);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        PermissionManager permissionManager = plugin.getPermissionManager();
        if (permissionManager == null) {
            return;
        }
        if (!plugin.getConfigManager().areAccrualsEnabled()) {
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
        if (!plugin.getConfigManager().areAccrualsEnabled()) {
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

    private boolean shouldPauseAccrual(@NotNull Player player) {
        if (!plugin.getConfigManager().areAccrualsEnabled()) {
            return true;
        }
        if (plugin.getConfigManager().doAccrualsRequireSurvivalMode()
                && player.getGameMode() != GameMode.SURVIVAL) {
            return true;
        }
        if (isWorldBlacklisted(player)) {
            return true;
        }
        if (plugin.getConfigManager().areAccrualsPausedWhileAfk()
                && hasTruthyMetadata(player, "afk", "essentials:afk")) {
            return true;
        }
        return plugin.getConfigManager().areAccrualsPausedWhileVanished()
            && hasTruthyMetadata(player, "vanished", "vanish", "VanishNoPacket", "invisible");
    }

    private boolean isWorldBlacklisted(@NotNull Player player) {
        String worldName = player.getWorld() != null ? player.getWorld().getName() : "";
        if (worldName.isEmpty()) return false;
        for (String blocked : plugin.getConfigManager().getAccrualWorldBlacklist()) {
            if (blocked != null && blocked.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    private int applyWorldMultiplier(@NotNull Player player, int blocksPerHour) {
        if (blocksPerHour <= 0 || player.getWorld() == null) {
            return Math.max(0, blocksPerHour);
        }
        double multiplier = plugin.getConfigManager().getAccrualWorldMultiplier(player.getWorld().getName());
        return Math.max(0, (int) Math.round(blocksPerHour * multiplier));
    }

    private boolean hasTruthyMetadata(@NotNull Player player, String... keys) {
        for (String key : keys) {
            List<MetadataValue> values = player.getMetadata(key);
            for (MetadataValue value : values) {
                if (metadataValueIsTruthy(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean metadataValueIsTruthy(@NotNull MetadataValue value) {
        try {
            if (value.asBoolean()) return true;
        } catch (Throwable ignored) {}
        String stringValue;
        try {
            stringValue = value.asString();
        } catch (Throwable ignored) {
            return false;
        }
        if (stringValue == null) return false;
        String normalized = stringValue.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("yes") || normalized.equals("1");
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
            if (plugin.getConfigManager().shouldNotifyAccrualCap()) {
                player.sendMessage(plugin.getMessages().get("commands.accruals-cap-reached",
                    "{max}", String.valueOf(profile.getMaxBlocks()),
                    "{profile}", profile.getName()));
            }
        }
    }
}
