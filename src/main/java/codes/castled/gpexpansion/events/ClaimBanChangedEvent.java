package codes.castled.gpexpansion.events;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Called after a player is banned from or unbanned from a claim.
 *
 * <p>Bans are conceptually distinct from trust changes and have their own
 * data model (public bans, per-player bans). This event is standalone
 * rather than extending {@link ClaimValueChangedEvent} because ban
 * operations don't follow a simple old/new value pattern.
 */
public class ClaimBanChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull Claim claim;
    private final @NotNull UUID player;
    private final boolean banned;
    private final @Nullable CommandSender actor;

    public ClaimBanChangedEvent(
            @NotNull Claim claim,
            @NotNull UUID player,
            boolean banned,
            @Nullable CommandSender actor) {
        this.claim = claim;
        this.player = player;
        this.banned = banned;
        this.actor = actor;
    }

    public @NotNull Claim getClaim() { return claim; }
    public @NotNull UUID getPlayer() { return player; }
    public boolean isBanned() { return banned; }
    public @Nullable CommandSender getActor() { return actor; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
