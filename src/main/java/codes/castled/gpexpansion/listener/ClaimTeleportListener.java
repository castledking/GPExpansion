package codes.castled.gpexpansion.listener;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.command.ClaimCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Listens for player movement during pending claim teleports.
 * Cancels the teleport if the player moves, similar to EssentialsX teleport behavior.
 */
public class ClaimTeleportListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final ClaimCommand claimCommand;

    public ClaimTeleportListener(GPExpansionPlugin plugin, ClaimCommand claimCommand) {
        this.plugin = plugin;
        this.claimCommand = claimCommand;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Check if cancel-on-move is enabled
        if (!plugin.getConfigManager().isClaimTeleportCancelOnMove()) {
            return;
        }

        // Only check if the player actually moved blocks (not just head rotation)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Check if this player has a pending claim teleport
        Map<UUID, ClaimCommand.PendingClaimTeleport> pendingTeleports = claimCommand.getPendingClaimTeleports();
        ClaimCommand.PendingClaimTeleport pending = pendingTeleports.get(playerId);

        if (pending == null) {
            return;
        }

        // Check if player has moved significantly from initial location
        if (pending.hasMoved(event.getTo())) {
            // Cancel the pending teleport and notify the player
            claimCommand.cancelPendingClaimTeleportWithMessage(playerId);
        }
    }
}
