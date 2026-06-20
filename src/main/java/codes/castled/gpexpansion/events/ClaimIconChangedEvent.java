package codes.castled.gpexpansion.events;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called after a claim's icon is changed.
 */
public class ClaimIconChangedEvent extends ClaimValueChangedEvent<Material> {
    private static final HandlerList HANDLERS = new HandlerList();

    public ClaimIconChangedEvent(
            @NotNull Claim claim,
            @Nullable Material oldIcon,
            @Nullable Material newIcon,
            @Nullable CommandSender actor) {
        super(claim, oldIcon, newIcon, actor);
    }

    public @Nullable Material getOldIcon() { return getOldValue(); }
    public @Nullable Material getNewIcon() { return getNewValue(); }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
