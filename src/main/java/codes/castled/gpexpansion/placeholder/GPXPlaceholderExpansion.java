package codes.castled.gpexpansion.placeholder;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.claimfly.ClaimFlyManager;
import codes.castled.gpexpansion.gp.GPBridge;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class GPXPlaceholderExpansion extends PlaceholderExpansion {

    private final GPExpansionPlugin plugin;
    private final GPBridge gpBridge;

    public GPXPlaceholderExpansion(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.gpBridge = new GPBridge();
    }

    @Override
    public @NotNull String getIdentifier() {
        return "griefprevention";
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

        // --- claim fly placeholders (no player location needed) ---

        if (params.equalsIgnoreCase("claim_flight_time")) {
            ClaimFlyManager manager = plugin.getClaimFlyManager();
            if (manager == null) return "";
            return ClaimFlyManager.formatDuration(manager.getRemainingMillis(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("claim_flight")) {
            ClaimFlyManager manager = plugin.getClaimFlyManager();
            if (manager == null) return "";
            return manager.hasTime(player.getUniqueId()) ? "yes" : "no";
        }

        // --- claim-location placeholders (need online player) ---

        Player online = Bukkit.getPlayer(player.getUniqueId());
        if (online == null) return "";

        if (params.equalsIgnoreCase("in_claim")) {
            return gpBridge.getClaimAt(online.getLocation(), online).isPresent() ? "yes" : "no";
        }

        Optional<Object> claimOpt = gpBridge.getClaimAt(online.getLocation(), online);
        if (claimOpt.isEmpty()) return "";

        Object claim = claimOpt.get();

        if (params.equalsIgnoreCase("currentclaim_claimid")) {
            return gpBridge.getClaimId(claim).orElse("");
        }

        if (params.equalsIgnoreCase("currentclaim_claimname")) {
            return gpBridge.getClaimId(claim)
                    .flatMap(id -> plugin.getMetadataService().getName(id))
                    .orElse("");
        }

        return null;
    }
}
