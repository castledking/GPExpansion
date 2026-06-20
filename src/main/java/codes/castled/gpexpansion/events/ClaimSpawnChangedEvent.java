package codes.castled.gpexpansion.events;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called after a claim's spawn point is set or cleared.
 *
 * <p>Naturally covers all transitions:
 * <ul>
 *   <li>Set: null → location</li>
 *   <li>Change: locA → locB</li>
 *   <li>Clear: location → null</li>
 * </ul>
 */
public class ClaimSpawnChangedEvent extends ClaimValueChangedEvent<Location> {
    private static final HandlerList HANDLERS = new HandlerList();

    public ClaimSpawnChangedEvent(
            @NotNull Claim claim,
            @Nullable Location oldSpawn,
            @Nullable Location newSpawn,
            @Nullable CommandSender actor) {
        super(claim, oldSpawn, newSpawn, actor);
    }

    public @Nullable Location getOldSpawn() { return getOldValue(); }
    public @Nullable Location getNewSpawn() { return getNewValue(); }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
