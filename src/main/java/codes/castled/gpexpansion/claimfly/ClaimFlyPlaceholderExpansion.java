package codes.castled.gpexpansion.claimfly;

import codes.castled.gpexpansion.GPExpansionPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ClaimFlyPlaceholderExpansion extends PlaceholderExpansion {
    private final GPExpansionPlugin plugin;

    public ClaimFlyPlaceholderExpansion(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "claim";
    }

    @Override
    public @NotNull String getAuthor() {
        return "castledking";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || player.getUniqueId() == null) return "";
        ClaimFlyManager manager = plugin.getClaimFlyManager();
        if (manager == null) return "";

        if (params.equalsIgnoreCase("flight_time")) {
            return ClaimFlyManager.formatDuration(manager.getRemainingMillis(player.getUniqueId()));
        }
        if (params.equalsIgnoreCase("flight")) {
            return manager.hasTime(player.getUniqueId()) ? "yes" : "no";
        }
        return null;
    }
}
