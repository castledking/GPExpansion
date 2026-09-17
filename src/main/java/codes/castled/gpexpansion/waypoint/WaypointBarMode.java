package codes.castled.gpexpansion.waypoint;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * How much of a player's claim set appears on their vanilla locator bar.
 *
 * <p>The mode only narrows the vanilla {@code ClientboundTrackedWaypointPacket} path. The CrowBar
 * plugin-channel payload is deliberately unaffected: CrowBar clients render their own waypoints
 * from it, and the mode is a no-op for them anyway (see {@code ClaimWaypointManager}).
 */
public enum WaypointBarMode {

    /** Only claims the player owns. */
    OWN,
    /** Claims the player owns or holds trust on (owned claims included for simplicity). */
    TRUSTED,
    /** Owned, trusted, and published claims — the default/current behaviour. */
    BOTH,
    /** Nothing appears on the vanilla bar. */
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