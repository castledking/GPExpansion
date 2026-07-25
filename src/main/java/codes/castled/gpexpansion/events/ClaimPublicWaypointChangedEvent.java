package codes.castled.gpexpansion.events;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called after a claim's waypoint is published to, or withdrawn from, all players.
 */
public class ClaimPublicWaypointChangedEvent extends ClaimValueChangedEvent<Boolean> {
    private static final HandlerList HANDLERS = new HandlerList();

    public ClaimPublicWaypointChangedEvent(
            @NotNull Claim claim,
            @Nullable Boolean oldValue,
            @Nullable Boolean newValue,
            @Nullable CommandSender actor) {
        super(claim, oldValue, newValue, actor);
    }

    public boolean wasPublic() { return Boolean.TRUE.equals(getOldValue()); }
    public boolean isPublic() { return Boolean.TRUE.equals(getNewValue()); }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
