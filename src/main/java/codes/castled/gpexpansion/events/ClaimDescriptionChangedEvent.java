package codes.castled.gpexpansion.events;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called after a claim's description is changed.
 */
public class ClaimDescriptionChangedEvent extends ClaimValueChangedEvent<String> {
    private static final HandlerList HANDLERS = new HandlerList();

    public ClaimDescriptionChangedEvent(
            @NotNull Claim claim,
            @Nullable String oldDescription,
            @Nullable String newDescription,
            @Nullable CommandSender actor) {
        super(claim, oldDescription, newDescription, actor);
    }

    public @Nullable String getOldDescription() { return getOldValue(); }
    public @Nullable String getNewDescription() { return getNewValue(); }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
