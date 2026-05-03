package codes.castled.gpexpansion.listener;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.claimfly.ClaimFlyManager;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.scheduler.TaskHandle;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for claim flight feature.
 * Grants flight to players with claimfly.use permission when inside claims they have access to.
 * Automatically revokes flight when leaving claims, with slow falling effect if they were flying.
 */
public class ClaimFlyListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final GPBridge gpBridge;
    private final ClaimFlyManager claimFlyManager;

    // Tracks UUIDs whose current allow-flight state was granted by this listener.
    // Without this, we would strip flight that other plugins (Essentials /fly, gamemode permission, etc.)
    // legitimately granted. We must only undo what we ourselves enabled.
    private final Set<UUID> claimFlightGranted = ConcurrentHashMap.newKeySet();

    // Track last claim for each player (replaces PlayerData.lastClaim access)
    private final Map<UUID, Object> playerLastClaim = new ConcurrentHashMap<>();

    // Per-player periodic flight reconciler. Catches state changes (gamemode, trust,
    // permission revocation) that happen between border crossings, plus closes the
    // race window where setAllowFlight calls can be stomped by other plugins.
    private final ConcurrentHashMap<UUID, TaskHandle> flightReconcilerTasks = new ConcurrentHashMap<>();

    public ClaimFlyListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.gpBridge = new GPBridge();
        this.claimFlyManager = plugin.getClaimFlyManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerID = player.getUniqueId();
        
        if (playerID == null) {
            return;
        }

        // Initialize flight state based on whether the login location is inside a claim.
        Object spawnClaim = gpBridge.getClaimAt(player.getLocation()).orElse(null);
        if (spawnClaim != null) {
            playerLastClaim.put(playerID, spawnClaim);
        } else {
            playerLastClaim.remove(playerID);
        }
        reconcileFlightForClaim(player, spawnClaim);

        // Start periodic flight reconciliation - runs every 2 seconds while the player
        // is online. Catches trust changes, permission revocation, and gamemode changes
        // that the move/teleport listeners can't see.
        startFlightReconciler(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerID = player.getUniqueId();

        // Cancel the periodic flight reconciler for this player.
        TaskHandle reconciler = flightReconcilerTasks.remove(playerID);
        if (reconciler != null && reconciler.isScheduled()) {
            reconciler.cancel();
        }

        // Drop our claim-flight grant marker; reconcileFlightForClaim will re-add it
        // on next join if the player is in a claim and meets the conditions.
        claimFlightGranted.remove(playerID);
        
        // Clean up last claim tracking
        playerLastClaim.remove(playerID);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only check if the player has moved a full block
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
                event.getFrom().getBlockZ() == event.getTo().getBlockZ() &&
                event.getFrom().getBlockY() == event.getTo().getBlockY()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerID = player.getUniqueId();

        // Get the claim at the new location
        Object toClaim = gpBridge.getClaimAt(event.getTo()).orElse(null);
        Object fromClaim = playerLastClaim.get(playerID);

        // If we're moving between claims or to/from wilderness
        if (fromClaim != toClaim) {
            // Update the last claim reference
            if (toClaim != null) {
                playerLastClaim.put(playerID, toClaim);
            } else {
                playerLastClaim.remove(playerID);
            }

            // Update commands to reflect the new location
            player.updateCommands();

            // Auto-toggle flight based on claim membership
            applyClaimFlightTransition(player, fromClaim, toClaim);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerID = player.getUniqueId();

        Object fromClaim = playerLastClaim.get(playerID);
        Object toClaim = gpBridge.getClaimAt(event.getTo()).orElse(null);

        // Update the lastClaim to the new location (only when teleport proceeds)
        if (toClaim != null) {
            playerLastClaim.put(playerID, toClaim);
        } else {
            playerLastClaim.remove(playerID);
        }

        // If we're moving from one claim to another, or from a claim to wilderness,
        // we need to update the player's permissions
        if (fromClaim != toClaim) {
            player.updateCommands();
            applyClaimFlightTransition(player, fromClaim, toClaim);
        }
    }

    private void applyClaimFlightTransition(Player player, Object fromClaim, Object toClaim) {
        if (fromClaim == toClaim) return;
        reconcileFlightForClaim(player, toClaim);
    }

    private void reconcileFlightForClaim(Player player, Object claim) {
        // Creative and Spectator manage their own flight; never touch them.
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;

        UUID playerID = player.getUniqueId();

        boolean shouldGrantClaimFlight = claimFlyManager != null
                && claimFlyManager.canUseClaimFlight(player)
                && claim != null
                && gpBridge.hasBuildOrInventoryTrust(claim, playerID);

        if (shouldGrantClaimFlight) {
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }

            claimFlightGranted.add(playerID);
            return;
        }

        if (claimFlightGranted.remove(playerID) && player.getAllowFlight()) {
            boolean wasFlying = player.isFlying();
            player.setAllowFlight(false);
            player.setFlying(false);
            if (wasFlying) {
                SchedulerAdapter.runLaterEntity(plugin, player, () -> player.addPotionEffect(
                        new PotionEffect(PotionEffectType.SLOW_FALLING, 5 * 20, 0)), 1L);
            }
        }
    }

    private void startFlightReconciler(Player player) {
        UUID playerID = player.getUniqueId();

        // If somehow already running, cancel the previous one first.
        TaskHandle existing = flightReconcilerTasks.remove(playerID);
        if (existing != null && existing.isScheduled()) {
            existing.cancel();
        }

        scheduleFlightReconcilerTick(playerID);
    }

    private void scheduleFlightReconcilerTick(UUID playerID) {
        Player player = plugin.getServer().getPlayer(playerID);
        if (player == null || !player.isOnline()) {
            flightReconcilerTasks.remove(playerID);
            return;
        }

        TaskHandle handle = SchedulerAdapter.runLaterEntity(plugin, player, () -> {
            Player p = plugin.getServer().getPlayer(playerID);
            if (p == null || !p.isOnline()) {
                flightReconcilerTasks.remove(playerID);
                return;
            }
            Object currentClaim = gpBridge.getClaimAt(p.getLocation()).orElse(null);
            if (currentClaim != null) {
                playerLastClaim.put(playerID, currentClaim);
            } else {
                playerLastClaim.remove(playerID);
            }
            if (claimFlightGranted.contains(playerID) && claimFlyManager != null) {
                claimFlyManager.consume(playerID, 40L * 50L);
            }
            reconcileFlightForClaim(p, currentClaim);

            // Reschedule the next tick.
            scheduleFlightReconcilerTick(playerID);
        }, 40L);

        flightReconcilerTasks.put(playerID, handle);
    }
}
