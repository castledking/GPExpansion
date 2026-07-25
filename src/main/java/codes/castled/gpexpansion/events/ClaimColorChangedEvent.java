package codes.castled.gpexpansion.events;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called after a claim's waypoint colour is changed.
 *
 * <p>Values are lowercase Adventure colour names (one of Minecraft's 16), or null for the default.
 */
public class ClaimColorChangedEvent extends ClaimValueChangedEvent<String> {
    private static final HandlerList HANDLERS = new HandlerList();

    public ClaimColorChangedEvent(
            @NotNull Claim claim,
            @Nullable String oldColor,
            @Nullable String newColor,
            @Nullable CommandSender actor) {
        super(claim, oldColor, newColor, actor);
    }

    public @Nullable String getOldColor() { return getOldValue(); }
    public @Nullable String getNewColor() { return getNewValue(); }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
