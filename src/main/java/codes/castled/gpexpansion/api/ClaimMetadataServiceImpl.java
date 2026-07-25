package codes.castled.gpexpansion.api;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

/**
 * Implementation of {@link ClaimMetadataService} that delegates to internal {@code ClaimDataStore}.
 */
class ClaimMetadataServiceImpl implements ClaimMetadataService {

    private final codes.castled.gpexpansion.storage.ClaimDataStore dataStore;

    public ClaimMetadataServiceImpl(codes.castled.gpexpansion.storage.ClaimDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public @NotNull Optional<String> getName(@NotNull Claim claim) {
        return getName(String.valueOf(claim.getID()));
    }

    @Override
    public @NotNull Optional<String> getName(@NotNull String claimId) {
        return dataStore.getCustomName(claimId);
    }

    @Override
    public @NotNull Optional<String> getDescription(@NotNull Claim claim) {
        return getDescription(String.valueOf(claim.getID()));
    }

    @Override
    public @NotNull Optional<String> getDescription(@NotNull String claimId) {
        return dataStore.getDescription(claimId);
    }

    @Override
    public @NotNull Optional<Material> getIcon(@NotNull Claim claim) {
        return getIcon(String.valueOf(claim.getID()));
    }

    @Override
    public @NotNull Optional<Material> getIcon(@NotNull String claimId) {
        return dataStore.getIcon(claimId);
    }

    @Override
    public @NotNull Optional<String> getWaypointColor(@NotNull Claim claim) {
        return getWaypointColor(String.valueOf(claim.getID()));
    }

    @Override
    public @NotNull Optional<String> getWaypointColor(@NotNull String claimId) {
        return dataStore.getWaypointColor(claimId);
    }

    @Override
    public @NotNull Collection<ClaimMetadata> getAllMetadata() {
        // Iterate all claims from GP3D's data store and build metadata snapshots
        if (GriefPrevention.instance == null || GriefPrevention.instance.dataStore == null) {
            return Collections.emptyList();
        }

        Collection<Claim> claims = GriefPrevention.instance.dataStore.getClaims();
        if (claims == null || claims.isEmpty()) {
            return Collections.emptyList();
        }

        java.util.List<ClaimMetadata> result = new ArrayList<>();
        for (Claim claim : claims) {
            String claimId = String.valueOf(claim.getID());
            Optional<String> name = dataStore.getCustomName(claimId);
            Optional<String> description = dataStore.getDescription(claimId);
            Optional<Material> icon = dataStore.getIcon(claimId);
            Optional<String> waypointColor = dataStore.getWaypointColor(claimId);

            // Only include claims that have at least one metadata field set
            if (name.isPresent() || description.isPresent() || icon.isPresent() || waypointColor.isPresent()) {
                result.add(new ClaimMetadata(
                    claim,
                    name.orElse(null),
                    description.orElse(null),
                    icon.orElse(null),
                    waypointColor.orElse(null)
                ));
            }
        }
        return Collections.unmodifiableList(result);
    }
}
