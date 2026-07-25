package codes.castled.gpexpansion.api;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of a claim's metadata.
 *
 * @param claim     the GP3D claim (provides ID, owner, geometry)
 * @param name      custom name, or null if unset
 * @param description custom description, or null if unset
 * @param icon      icon material, or null if unset
 * @param waypointColor lowercase Adventure colour name for the claim's locator-bar waypoint,
 *                      or null to use the default colour
 */
public record ClaimMetadata(
    @NotNull Claim claim,
    @Nullable String name,
    @Nullable String description,
    @Nullable Material icon,
    @Nullable String waypointColor
) {}
