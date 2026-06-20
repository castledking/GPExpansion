package codes.castled.gpexpansion.events;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Base for events where a single metadata value changes from old to new.
 *
 * <p>Subclasses must provide their own {@code HandlerList}.
 *
 * @param <T> the type of value that changed
 */
public abstract class ClaimValueChangedEvent<T> extends Event {

    private final @NotNull Claim claim;
    private final @Nullable T oldValue;
    private final @Nullable T newValue;
    private final @Nullable CommandSender actor;

    protected ClaimValueChangedEvent(
            @NotNull Claim claim,
            @Nullable T oldValue,
            @Nullable T newValue,
            @Nullable CommandSender actor) {
        this.claim = claim;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.actor = actor;
    }

    /**
     * The claim whose metadata changed.
     */
    public @NotNull Claim getClaim() {
        return claim;
    }

    /**
     * The previous value, or null if unset before the change.
     */
    public @Nullable T getOldValue() {
        return oldValue;
    }

    /**
     * The new value, or null if the value was cleared.
     */
    public @Nullable T getNewValue() {
        return newValue;
    }

    /**
     * Who initiated this change, or null if triggered by a system task.
     */
    public @Nullable CommandSender getActor() {
        return actor;
    }

    /**
     * Convenience check: was this change initiated by a player?
     */
    public boolean isPlayerInitiated() {
        return actor instanceof Player;
    }
}
