package codes.castled.gpexpansion.api;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
     * Get the custom name for a claim.
     *
     * @param claim the GP3D claim
     * @return the custom name, or empty if none set
     */
    @NotNull Optional<String> getName(@NotNull Claim claim);

    /**
     * Get the description for a claim.
     *
     * @param claim the GP3D claim
     * @return the description, or empty if none set
     */
    @NotNull Optional<String> getDescription(@NotNull Claim claim);

    /**
     * Get the icon material for a claim.
     *
     * @param claim the GP3D claim
     * @return the icon material, or empty if none set
     */
    @NotNull Optional<Material> getIcon(@NotNull Claim claim);

    /**
     * Get all metadata entries.
     *
     * @return unmodifiable collection of all metadata
     */
    @NotNull Collection<ClaimMetadata> getAllMetadata();
}
