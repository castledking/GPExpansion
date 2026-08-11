package codes.castled.gpexpansion.listener;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.events.ClaimColorChangedEvent;
import codes.castled.gpexpansion.events.ClaimSpawnChangedEvent;
import codes.castled.gpexpansion.waypoint.ClaimWaypointManager;
import me.ryanhamshire.GriefPrevention.events.ClaimCreatedEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimDeletedEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimResizeEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimTransferEvent;
import me.ryanhamshire.GriefPrevention.events.TrustChangedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives {@link ClaimWaypointManager} from claim and trust changes.
 *
 * <p>Every trigger here is discrete, so the manager never needs to poll. Cancellable events are
 * handled at {@link EventPriority#MONITOR} and skipped when cancelled, so a denied trust change or
 * a vetoed resize does not move a marker.
 *
 * <p>Resize and transfer are deferred by a tick: GP3D fires those before it has finished writing
 * the new bounds or owner, so reading them immediately would rebuild against stale data.
 */
public final class ClaimWaypointListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final AtomicBoolean globalRebuildQueued = new AtomicBoolean();

    public ClaimWaypointListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    private ClaimWaypointManager manager() {
        return plugin.getClaimWaypointManager();
    }

    private void rebuild() {
        ClaimWaypointManager manager = manager();
        if (manager != null) manager.rebuildAll();
    }

    private void rebuildNextTick() {
        // Bulk claim operations can fire many events in one tick. One rebuild sees their final state.
        if (!globalRebuildQueued.compareAndSet(false, true)) return;
        codes.castled.gpexpansion.scheduler.SchedulerAdapter.runLaterGlobal(plugin, () -> {
            globalRebuildQueued.set(false);
            rebuild();
        }, 1L);
    }

    private void rebuildNextTick(org.bukkit.entity.Player player) {
        codes.castled.gpexpansion.scheduler.SchedulerAdapter.runLaterGlobal(plugin, () -> {
            ClaimWaypointManager manager = manager();
            if (manager != null && player.isOnline()) manager.rebuildViewer(player);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // The first send is fast, while the delayed retry covers clients whose plugin-channel
        // registration arrives after the join event. Both refresh only this player.
        rebuildNextTick(event.getPlayer());
        codes.castled.gpexpansion.scheduler.SchedulerAdapter.runLaterGlobal(
            plugin, () -> {
                ClaimWaypointManager manager = manager();
                if (manager != null && event.getPlayer().isOnline()) {
                    manager.rebuildViewer(event.getPlayer());
                }
            }, 60L);

        codes.castled.gpexpansion.pack.ClaimWaypointPackService packService =
            plugin.getClaimWaypointPackService();
        if (packService != null) {
            // Three seconds rather than one: the CrowBar check reads the plugin channels the client
            // registered, and that registration arrives shortly after join. Sending too early would
            // race it and prompt CrowBar users for a pack they do not need.
            codes.castled.gpexpansion.scheduler.SchedulerAdapter.runLaterGlobal(
                plugin, () -> {
                    if (event.getPlayer().isOnline()) packService.sendTo(event.getPlayer());
                }, 60L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ClaimWaypointManager manager = manager();
        if (manager != null) manager.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChannelRegistered(PlayerRegisterChannelEvent event) {
        if (ClaimWaypointManager.CLAIM_DATA_CHANNEL.equals(event.getChannel())) {
            // This is the earliest reliable point at which CrowBar has advertised its receiver.
            rebuildNextTick(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        // The claim set is per-world, and vanilla removes a player's waypoints on dimension
        // change — the diff resends the ones valid for the new world.
        rebuildNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrustChanged(TrustChangedEvent event) {
        rebuildNextTick();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaimCreated(ClaimCreatedEvent event) {
        rebuildNextTick();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClaimDeleted(ClaimDeletedEvent event) {
        ClaimWaypointManager manager = manager();
        if (manager == null || event.getClaim() == null) return;
        // Drop the marker directly: the claim is already gone from the data store, so a rebuild
        // alone would leave the entity orphaned until something else triggered a sweep.
        manager.removeClaim(event.getClaim().getID());
        rebuildNextTick();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaimResized(ClaimResizeEvent event) {
        rebuildNextTick();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaimTransferred(ClaimTransferEvent event) {
        rebuildNextTick();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRenamed(codes.castled.gpexpansion.events.ClaimRenamedEvent event) {
        // The name only lives in the CrowBar payload, so a rename just needs a resend.
        rebuildNextTick();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onColorChanged(ClaimColorChangedEvent event) {
        // Colour is part of the per-viewer diff fingerprint, so a rebuild resends it everywhere.
        rebuildNextTick();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPublicWaypointChanged(codes.castled.gpexpansion.events.ClaimPublicWaypointChangedEvent event) {
        // Changes who the claim is published to, so the whole viewer set has to be recomputed.
        rebuildNextTick();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawnChanged(ClaimSpawnChangedEvent event) {
        // The anchor is part of the per-viewer diff fingerprint, so a rebuild repositions it.
        rebuildNextTick();
    }
}
