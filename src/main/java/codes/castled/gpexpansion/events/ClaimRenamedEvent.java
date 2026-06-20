package codes.castled.gpexpansion.events;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called after a claim's custom name is changed.
 */
public class ClaimRenamedEvent extends ClaimValueChangedEvent<String> {
    private static final HandlerList HANDLERS = new HandlerList();

    public ClaimRenamedEvent(
            @NotNull Claim claim,
            @Nullable String oldName,
            @Nullable String newName,
            @Nullable CommandSender actor) {
        super(claim, oldName, newName, actor);
    }

    public @Nullable String getOldName() { return getOldValue(); }
    public @Nullable String getNewName() { return getNewValue(); }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
