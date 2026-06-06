package codes.castled.gpexpansion.listener;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.claimfly.ClaimFlyManager;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.scheduler.TaskHandle;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.EnumSet;
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

    // Tracks players who had flight enabled by external means (Essentials /fly, etc.) before claimfly.
    // We preserve their flight when they exit claims instead of disabling it.
    private final Set<UUID> externalFlightEnabled = ConcurrentHashMap.newKeySet();

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
        
        // Clean up external flight tracking
        externalFlightEnabled.remove(playerID);
        
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!claimFlightGranted.contains(player.getUniqueId())) return;

        boolean pvp = event instanceof EntityDamageByEntityEvent byEntity && isPvpDamage(player, byEntity);
        if (pvp && (plugin.getConfigManager().isClaimFlightDisabledOnPvp()
                || plugin.getConfigManager().isClaimFlightDisabledOnDamage())) {
            revokeClaimFlight(player);
            return;
        }
        if (!pvp && plugin.getConfigManager().isClaimFlightDisabledOnDamage()) {
            revokeClaimFlight(player);
        }
    }

    private void applyClaimFlightTransition(Player player, Object fromClaim, Object toClaim) {
        if (fromClaim == toClaim) return;
        reconcileFlightForClaim(player, toClaim);
    }

    private void reconcileFlightForClaim(Player player, Object claim) {
        // Creative and Spectator manage their own flight; never grant/revoke there.
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;

        UUID playerID = player.getUniqueId();

        boolean canUseClaimFlight = claimFlyManager != null && claimFlyManager.canUseClaimFlight(player);
        boolean hasClaimAccess = claim != null && hasClaimFlightAccess(claim, playerID);
        boolean mayContinueAfterLeaving = claim == null
                && claimFlightGranted.contains(playerID)
                && !plugin.getConfigManager().isClaimFlightDisabledOnLeavingClaim();
        boolean shouldGrantClaimFlight = canUseClaimFlight && (hasClaimAccess || mayContinueAfterLeaving);

        if (shouldGrantClaimFlight) {
            // Check if player already has flight from external sources (Essentials /fly, etc.)
            if (player.getAllowFlight() && !claimFlightGranted.contains(playerID)) {
                externalFlightEnabled.add(playerID);
            }
            
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }

            claimFlightGranted.add(playerID);
            return;
        }

        revokeClaimFlight(player);
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
            if (claimFlightGranted.contains(playerID)
                    && claimFlyManager != null
                    && claimFlyManager.shouldConsumeFlightTime(p)) {
                claimFlyManager.consume(playerID, 40L * 50L);
            }
            reconcileFlightForClaim(p, currentClaim);

            // Reschedule the next tick.
            scheduleFlightReconcilerTick(playerID);
        }, 40L);

        flightReconcilerTasks.put(playerID, handle);
    }

    private boolean hasClaimFlightAccess(Object claim, UUID playerID) {
        if (claim == null || playerID == null) return false;

        if (gpBridge.isAdminClaim(claim) && !plugin.getConfigManager().isClaimFlightAllowedInAdminClaims()) {
            return false;
        }

        String claimId = gpBridge.getClaimId(claim).orElse(null);
        if (claimId != null
                && plugin.getConfigManager().isClaimFlightAllowedInPublicGlobalClaims()
                && plugin.getClaimDataStore().isPublicListed(claimId)) {
            return true;
        }

        if (plugin.getConfigManager().isClaimFlightOwnerTrustAllowed() && gpBridge.isOwner(claim, playerID)) {
            return true;
        }

        EnumSet<GPBridge.TrustLevel> levels = gpBridge.getTrustLevels(claim, playerID);
        return (plugin.getConfigManager().isClaimFlightManagerTrustAllowed() && levels.contains(GPBridge.TrustLevel.MANAGE))
                || (plugin.getConfigManager().isClaimFlightBuilderTrustAllowed() && levels.contains(GPBridge.TrustLevel.BUILD))
                || (plugin.getConfigManager().isClaimFlightContainerTrustAllowed() && levels.contains(GPBridge.TrustLevel.CONTAINERS))
                || (plugin.getConfigManager().isClaimFlightAccessTrustAllowed() && levels.contains(GPBridge.TrustLevel.ACCESS));
    }

    private void revokeClaimFlight(Player player) {
        UUID playerID = player.getUniqueId();
        if (!claimFlightGranted.remove(playerID) || !player.getAllowFlight()) return;

        if (externalFlightEnabled.remove(playerID)) {
            return;
        }

        boolean wasFlying = player.isFlying();
        player.setAllowFlight(false);
        player.setFlying(false);
        int graceSeconds = plugin.getConfigManager().getClaimFlightLandingGraceSeconds();
        if (wasFlying && graceSeconds > 0) {
            SchedulerAdapter.runLaterEntity(plugin, player, () -> player.addPotionEffect(
                    new PotionEffect(PotionEffectType.SLOW_FALLING, graceSeconds * 20, 0)), 1L);
        }
    }

    private boolean isPvpDamage(Player victim, EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player) {
            return !player.getUniqueId().equals(victim.getUniqueId());
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Player player && !player.getUniqueId().equals(victim.getUniqueId());
        }
        return false;
    }
}
