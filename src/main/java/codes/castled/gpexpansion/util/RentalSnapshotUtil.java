package codes.castled.gpexpansion.util;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.storage.ClaimSnapshotStore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Optional;
import java.util.function.Consumer;

public final class RentalSnapshotUtil {
    private RentalSnapshotUtil() {}

    public static void createSnapshot(GPExpansionPlugin plugin, String claimId, Object claim, String reason) {
        createSnapshot(plugin, claimId, claim, reason, null);
    }

    public static void createSnapshot(GPExpansionPlugin plugin, String claimId, Object claim, String reason,
                                      Consumer<ClaimSnapshotStore.SnapshotEntry> callback) {
        if (plugin == null || claimId == null || claimId.isEmpty() || claim == null || plugin.getSnapshotStore() == null) {
            if (callback != null) callback.accept(null);
            return;
        }

        GPBridge gp = new GPBridge();
        Optional<String> worldNameOpt = gp.getClaimWorld(claim);
        Optional<GPBridge.ClaimCorners> cornersOpt = gp.getClaimCorners(claim);
        if (!worldNameOpt.isPresent() || !cornersOpt.isPresent()) {
            if (callback != null) callback.accept(null);
            return;
        }

        World world = Bukkit.getWorld(worldNameOpt.get());
        if (world == null) {
            if (callback != null) callback.accept(null);
            return;
        }

        GPBridge.ClaimCorners corners = cornersOpt.get();
        Location runAt = world.getBlockAt(corners.x1, corners.y1, corners.z1).getLocation();
        SchedulerAdapter.runAtLocation(plugin, runAt, () -> {
            ClaimSnapshotStore.SnapshotEntry entry = plugin.getSnapshotStore().createSnapshot(claimId, claim, world);
            if (entry != null && plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("[Snapshot] Auto-created snapshot " + entry.id + " for claim " + claimId + " (" + reason + ")");
            }
            if (callback != null) callback.accept(entry);
        });
    }

    public static void restoreLatestSnapshot(GPExpansionPlugin plugin, String claimId, World world, Location fallbackRunAt) {
        if (plugin == null || claimId == null || claimId.isEmpty() || world == null || plugin.getSnapshotStore() == null) return;
        plugin.getSnapshotStore().getLatestSnapshot(claimId)
            .ifPresent(snapshot -> restoreSnapshot(plugin, claimId, snapshot.id, world, fallbackRunAt));
    }

    public static void restoreSnapshot(GPExpansionPlugin plugin, String claimId, String snapshotId, World world, Location fallbackRunAt) {
        if (plugin == null || claimId == null || snapshotId == null || world == null || plugin.getSnapshotStore() == null) return;
        Optional<Location> originOpt = plugin.getSnapshotStore().getSnapshotOrigin(claimId, snapshotId, world);
        Location runAt = originOpt.orElse(fallbackRunAt != null ? fallbackRunAt : world.getSpawnLocation());
        Runnable restore = () -> {
            boolean ok = plugin.getSnapshotStore().restoreSnapshot(claimId, snapshotId, world, null);
            if (ok) {
                plugin.getLogger().info("Claim " + claimId + " restored from snapshot " + snapshotId + ".");
            } else {
                plugin.getLogger().warning("Failed to restore snapshot " + snapshotId + " for claim " + claimId + ".");
            }
        };
        if (SchedulerAdapter.isFolia()) {
            SchedulerAdapter.runAtLocationLater(plugin, runAt, restore, 1L);
        } else if (Bukkit.isPrimaryThread()) {
            restore.run();
        } else {
            SchedulerAdapter.runLaterGlobal(plugin, restore, 1L);
        }
    }
}
