package codes.castled.gpexpansion.api;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import codes.castled.gpexpansion.storage.ClaimDataStore;

import java.util.Collection;
import java.util.Optional;

/**
 * Read-only access to claim metadata stored by GPExpansion.
 *
 * <p>Obtain an instance via {@link codes.castled.gpexpansion.GPExpansionPlugin#getMetadataService()}.
 *
 * <p>All mutation goes through GP3D commands/GUIs. This interface is intentionally
 * read-only to avoid creating dual mutation paths.
 */
public interface ClaimMetadataService {

    /**
     * Create an instance backed by the given data store.
     * Intended for internal use; external consumers should use
     * {@link codes.castled.gpexpansion.GPExpansionPlugin#getMetadataService()}.
     */
    static ClaimMetadataService create(ClaimDataStore dataStore) {
        return new ClaimMetadataServiceImpl(dataStore);
    }

    /**
     * Get the custom name for a claim.
     *
     * @param claim the GP3D claim
     * @return the custom name, or empty if none set
     */
    @NotNull Optional<String> getName(@NotNull Claim claim);

    /**
     * Get the custom name for a claim by ID.
     * Use this overload when you don't have the Claim object on the classpath.
     *
     * @param claimId the claim ID string
     * @return the custom name, or empty if none set
     */
    @NotNull Optional<String> getName(@NotNull String claimId);

    /**
     * Get the description for a claim.
     *
     * @param claim the GP3D claim
     * @return the description, or empty if none set
     */
    @NotNull Optional<String> getDescription(@NotNull Claim claim);

    /**
     * Get the description for a claim by ID.
     *
     * @param claimId the claim ID string
     * @return the description, or empty if none set
     */
    @NotNull Optional<String> getDescription(@NotNull String claimId);

    /**
     * Get the icon material for a claim.
     *
     * @param claim the GP3D claim
     * @return the icon material, or empty if none set
     */
    @NotNull Optional<Material> getIcon(@NotNull Claim claim);

    /**
     * Get the icon material for a claim by ID.
     *
     * @param claimId the claim ID string
     * @return the icon material, or empty if none set
     */
    @NotNull Optional<Material> getIcon(@NotNull String claimId);

    /**
     * Get the claim's locator-bar waypoint colour.
     *
     * @param claim the GP3D claim
     * @return a lowercase Adventure colour name (one of Minecraft's 16), or empty for the default
     */
    @NotNull Optional<String> getWaypointColor(@NotNull Claim claim);

    /**
     * Get the claim's locator-bar waypoint colour by ID.
     *
     * @param claimId the claim ID string
     * @return a lowercase Adventure colour name (one of Minecraft's 16), or empty for the default
     */
    @NotNull Optional<String> getWaypointColor(@NotNull String claimId);

    /**
     * Get all metadata entries.
     *
     * @return unmodifiable collection of all metadata
     */
    @NotNull Collection<ClaimMetadata> getAllMetadata();
}
