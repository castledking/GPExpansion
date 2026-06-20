package codes.castled.gpexpansion.events;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called after a claim's global listing status changes.
 */
public class ClaimGlobalListedEvent extends ClaimValueChangedEvent<Boolean> {
    private static final HandlerList HANDLERS = new HandlerList();

    public ClaimGlobalListedEvent(
            @NotNull Claim claim,
            boolean oldListed,
            boolean newListed,
            @Nullable CommandSender actor) {
        super(claim, oldListed, newListed, actor);
    }

    public boolean getOldListed() { return getOldValue(); }
    public boolean getNewListed() { return getNewValue(); }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
