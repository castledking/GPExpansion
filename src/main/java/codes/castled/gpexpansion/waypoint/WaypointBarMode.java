package codes.castled.gpexpansion.waypoint;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * How much of a player's claim set appears on their locator bar, chosen with {@code /claim bar}.
 *
 * <p>Applies to both delivery paths: vanilla {@code ClientboundTrackedWaypointPacket} markers and
 * the CrowBar plugin-channel payload (see {@code ClaimWaypointManager}). CrowBar's claim key cycles
 * this same mode, in {@link #ARGUMENTS} order, over {@code crowbar:claim_mode}. Older CrowBar
 * versions without that channel can narrow it with their own claim key, but never widen it.
 */
public enum WaypointBarMode {

    /** Only claims the player owns. */
    OWN,
    /** Claims the player owns or holds trust on (owned claims included for simplicity). */
    TRUSTED,
    /** Owned, trusted, and published claims — the default/current behaviour. */
    BOTH,
    /** No claim markers at all, for vanilla and CrowBar clients alike. */
    OFF;

    /** The values a player can type, in a stable order. */
    public static final List<String> ARGUMENTS =
        List.of("own", "trusted", "both", "off");

    /**
     * @param value the typed argument, or null
     * @return the matching mode, or {@code null} when the argument is not a known mode
     */
    public static @Nullable WaypointBarMode fromString(@Nullable String value) {
        if (value == null) return null;
        try {
            return WaypointBarMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}