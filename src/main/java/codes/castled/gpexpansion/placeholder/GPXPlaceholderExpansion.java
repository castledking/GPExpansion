package codes.castled.gpexpansion.placeholder;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.claimfly.ClaimFlyManager;
import codes.castled.gpexpansion.gp.GPBridge;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
        return "gpx";
    }

    @Override
    public @NotNull String getAuthor() {
        return "castledking";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
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
            // OfflinePlayer cannot be permission-checked on this API, so an offline
            // holder of the unlimited node reports on their bank alone.
            Player online = Bukkit.getPlayer(player.getUniqueId());
            boolean access = online != null
                    ? manager.hasFlightAccess(online)
                    : manager.hasTime(player.getUniqueId());
            return access ? "yes" : "no";
        }

        // --- claim-location placeholders (need online player) ---

        Player online = Bukkit.getPlayer(player.getUniqueId());
        if (online == null) return "";

        if (params.equalsIgnoreCase("in_claim")) {
            return gpBridge.getClaimAt(online.getLocation(), online).isPresent() ? "yes" : "no";
        }

        Optional<Object> claimOpt = gpBridge.getClaimAt(online.getLocation(), online);
        if (claimOpt.isEmpty()) return "";

        Object claim = findDeepestClaimAt(claimOpt.get(), online.getLocation());

        if (params.equalsIgnoreCase("currentclaim_claimid")) {
            return gpBridge.getClaimId(claim).orElse("");
        }

        if (params.equalsIgnoreCase("currentclaim_claimname")) {
            Object namedClaim = claim;
            while (namedClaim != null) {
                Optional<String> name = gpBridge.getClaimId(namedClaim)
                    .flatMap(id -> plugin.getMetadataService().getName(id))
                    .filter(value -> !value.isBlank());
                if (name.isPresent()) return name.get();

                Object parent = gpBridge.getParentClaim(namedClaim).orElse(null);
                if (parent == null || parent == namedClaim) break;
                namedClaim = parent;
            }
            return "";
        }

        return null;
    }

    private Object findDeepestClaimAt(Object claim, Location location) {
        for (Object child : gpBridge.getSubclaims(claim)) {
            if (gpBridge.claimContains(child, location.getWorld(), location.getBlockX(),
                    location.getBlockY(), location.getBlockZ())) {
                return findDeepestClaimAt(child, location);
            }
        }
        return claim;
    }
}
