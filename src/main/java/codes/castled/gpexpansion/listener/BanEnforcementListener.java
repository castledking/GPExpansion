package codes.castled.gpexpansion.listener;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.scheduler.TaskHandle;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BanEnforcementListener implements Listener {
    private final GPExpansionPlugin plugin;
    private final GPBridge gp = new GPBridge();
    
    // Track players currently being ejected to prevent movement during teleport
    private final java.util.Set<UUID> beingEjected = java.util.concurrent.ConcurrentHashMap.newKeySet();
    
    // Knockback strength for boundary rebound effect
    private static final double KNOCKBACK_STRENGTH = 0.8;
    private static final double KNOCKBACK_Y = 0.3;
    private static final long BAN_VISUALIZATION_TICKS = 200L;
    private static final long BAN_ENTER_MESSAGE_COOLDOWN_MS = 10_000L;

    private final Map<UUID, TaskHandle> visualizationClearTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastEnterMessageAt = new ConcurrentHashMap<>();

    public BanEnforcementListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        scheduleInitialEjectionCheck();
    }

    private void scheduleInitialEjectionCheck() {
        if (!plugin.getConfigManager().isClaimBanEjectOnReloadEnabled()) {
            return;
        }
        SchedulerAdapter.runLaterGlobal(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                SchedulerAdapter.runLaterEntity(plugin, player, () -> ejectIfBannedInside(player), 1L);
            }
        }, 40L);
    }

    private boolean isWithinVerticalBounds(Object claim, Location location) {
        if (claim == null || location == null) return false;
        int maxY = gp.getClaimMaxY(claim);
        int minY = gp.getClaimMinY(claim);
        int y = location.getBlockY();
        return y >= minY && y <= maxY;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player player = e.getPlayer();
        
        // Block all movement if player is being ejected via teleport
        if (beingEjected.contains(player.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (!plugin.getConfigManager().isClaimBanEntryPreventionEnabled()) {
            return;
        }
        
        // Only act when changing block coordinates to reduce spam
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld()) return;
        
        // Check if destination is in a banned claim
        BanCheckResult result = checkBanned(player, to);
        if (result == null) return;
        
        // Check if player was OUTSIDE the claim before (boundary crossing)
        boolean wasOutside = !isInsideClaim(from, result.claim);
        
        if (wasOutside) {
            // BOUNDARY CROSSING - apply immediate knockback/rebound effect
            e.setCancelled(true);
            applyKnockback(player, from, to, result.claim);
            notifyEnterBlocked(player, result.claim);
        } else {
            // ALREADY INSIDE - teleport them out safely (banned while inside, or teleported in)
            handleDeepEjection(player, result.claim);
            notifyBanned(player, "claim.ban-blocked-inside", result.claim);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player player = e.getPlayer();
        Location to = e.getTo();
        if (!plugin.getConfigManager().isClaimBanTeleportPreventionEnabled()) {
            return;
        }
        
        BanCheckResult result = checkBanned(player, to);
        if (result == null) return;
        
        // Cancel teleport into banned claim and eject
        e.setCancelled(true);
        notifyBanned(player, "claim.ban-blocked-teleport", result.claim);
        
        // If they're currently in the claim, eject them
        if (isInsideClaim(player.getLocation(), result.claim)) {
            handleDeepEjection(player, result.claim);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfigManager().isClaimBanEntryPreventionEnabled()) {
            return;
        }
        BanCheckResult result = checkBanned(p, p.getLocation());
        if (result != null) {
            e.setCancelled(true);
            showBanVisualization(p, result.claim);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isClaimBanEjectOnReloadEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        SchedulerAdapter.runLaterEntity(plugin, player, () -> ejectIfBannedInside(player), 20L);
    }

    private void ejectIfBannedInside(Player player) {
        if (player == null || !player.isOnline() || !player.isValid()) return;
        BanCheckResult result = checkBanned(player, player.getLocation());
        if (result == null) return;
        handleDeepEjection(player, result.claim);
        notifyBanned(player, "claim.ban-blocked-inside", result.claim);
    }

    private void notifyEnterBlocked(Player player, Object claim) {
        if (shouldSendEnterMessage(player)) {
            sendBanMessage(player, "claim.ban-blocked-enter");
        }
        showBanVisualization(player, claim);
    }

    private boolean shouldSendEnterMessage(Player player) {
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastSent = lastEnterMessageAt.get(playerId);
        if (lastSent != null && now - lastSent < BAN_ENTER_MESSAGE_COOLDOWN_MS) {
            return false;
        }
        lastEnterMessageAt.put(playerId, now);
        return true;
    }

    private void notifyBanned(Player player, String messageKey, Object claim) {
        sendBanMessage(player, messageKey);
        showBanVisualization(player, claim);
    }

    private void sendBanMessage(Player player, String messageKey) {
        String message = plugin.getMessages().getRaw(messageKey);
        if (player.hasPermission("griefprevention.ignoreclaims")) {
            message += "  " + plugin.getMessages().getRaw("claim.ban-ignoreclaims-hint");
        }
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    private void showBanVisualization(Player player, Object claim) {
        Object visualizeClaim = toMainClaim(claim);
        if (!gp.showConflictVisualization(player, visualizeClaim)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        TaskHandle previous = visualizationClearTasks.remove(playerId);
        if (previous != null) {
            previous.cancel();
        }

        TaskHandle clearTask = SchedulerAdapter.runLaterEntity(plugin, player, () -> {
            gp.clearBoundaryVisualization(player);
            visualizationClearTasks.remove(playerId);
        }, BAN_VISUALIZATION_TICKS);
        visualizationClearTasks.put(playerId, clearTask);
    }
    
    private static class BanCheckResult {
        final Object claim;

        BanCheckResult(Object claim) {
            this.claim = claim;
        }
    }
    
    /**
     * Check if player is banned from claim at location. Returns null if not banned.
     */
    private BanCheckResult checkBanned(Player player, Location loc) {
        if (loc == null) return null;
        String bypassPermission = plugin.getConfigManager().getClaimBanAdminBypassPermission();
        if (!bypassPermission.isEmpty() && player.hasPermission(bypassPermission)) {
            return null;
        }
        Optional<Object> oc = gp.getClaimAt(loc, player);
        if (!oc.isPresent()) return null;
        
        Object claim = oc.get();
        Object main = toMainClaim(claim);
        
        // Respect 3D subdivisions
        if (!isWithinVerticalBounds(claim, loc)) return null;
        
        String claimId = gp.getClaimId(main).orElse(null);
        if (claimId == null) return null;
        
        UUID uuid = player.getUniqueId();
        boolean pubBanned = plugin.getClaimDataStore().isPublicBanned(claimId);
        boolean playerBanned = plugin.getClaimDataStore().getBannedPlayers(claimId).contains(uuid);
        
        if (!pubBanned && !playerBanned) return null;
        if (pubBanned && isTrusted(main, player)) return null;
        
        return new BanCheckResult(claim);
    }
    
    /**
     * Check if location is inside the claim boundaries.
     */
    private boolean isInsideClaim(Location loc, Object claim) {
        if (loc == null || claim == null) return false;
        Optional<Object> claimAtLoc = gp.getClaimAt(loc, null);
        if (!claimAtLoc.isPresent()) return false;
        
        // Check if same claim or same parent claim
        Object main1 = toMainClaim(claim);
        Object main2 = toMainClaim(claimAtLoc.get());
        
        Optional<String> id1 = gp.getClaimId(main1);
        Optional<String> id2 = gp.getClaimId(main2);
        
        return id1.isPresent() && id2.isPresent() && id1.get().equals(id2.get());
    }
    
    /**
     * Apply knockback/rebound effect - pushes player away from claim boundary.
     */
    private void applyKnockback(Player player, Location from, Location to, Object claim) {
        // Calculate direction away from claim (from 'to' back towards 'from')
        Vector direction = from.toVector().subtract(to.toVector()).normalize();
        
        // If direction is zero (same block), calculate from claim center
        if (direction.lengthSquared() < 0.01) {
            direction = calculateDirectionFromClaimCenter(player.getLocation(), claim);
        }
        
        // Apply knockback velocity
        Vector knockback = direction.multiply(KNOCKBACK_STRENGTH).setY(KNOCKBACK_Y);
        player.setVelocity(knockback);
    }
    
    /**
     * Calculate direction away from claim center (fallback).
     */
    private Vector calculateDirectionFromClaimCenter(Location playerLoc, Object claim) {
        try {
            Object main = toMainClaim(claim);
            Optional<GPBridge.ClaimCorners> optCorners = gp.getClaimCorners(main);
            if (optCorners.isPresent()) {
                GPBridge.ClaimCorners c = optCorners.get();
                double centerX = (c.x1 + c.x2) / 2.0;
                double centerZ = (c.z1 + c.z2) / 2.0;
                
                Vector away = new Vector(playerLoc.getX() - centerX, 0, playerLoc.getZ() - centerZ);
                if (away.lengthSquared() > 0.01) {
                    return away.normalize();
                }
            }
        } catch (Exception ignored) {}
        
        return playerLoc.getDirection().multiply(-1).setY(0).normalize();
    }
    
    /**
     * Handle ejection for players already deep inside claim.
     */
    private void handleDeepEjection(Player player, Object claim) {
        plugin.getSchedulerFacade().runAtEntity(player, () -> {
            if (!ejectFromClaim(player, toMainClaim(claim))) {
                plugin.getSchedulerFacade().runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spawn " + player.getName()));
            }
        });
    }

    private Object toMainClaim(Object claim) {
        // Try multiple possible method names for getting parent
        String[] methodNames = {"getParent", "getParentClaim", "getTopLevelClaim"};
        
        for (String methodName : methodNames) {
            try {
                Method method = claim.getClass().getMethod(methodName);
                Object cur = claim;
                while (true) {
                    Object parent = method.invoke(cur);
                    if (parent == null || parent == cur) return cur;
                    cur = parent;
                }
            } catch (NoSuchMethodException e) {
                // Try next method
                continue;
            } catch (ReflectiveOperationException ignored) {
                break;
            }
        }
        return claim;
    }

    private boolean isTrusted(Object claim, Player player) {
        // Try common GP methods returning null when allowed, String when denied
        for (String m : new String[]{"allowAccess", "allowEntry", "allowContainers", "allowBuild"}) {
            try {
                Method method = claim.getClass().getMethod(m, Player.class);
                Object res = method.invoke(claim, player);
                if (res == null) return true; // permitted
            } catch (ReflectiveOperationException ignored) { }
        }
        return false;
    }

    private boolean ejectFromClaim(Player target, Object claim) {
        try {
            beingEjected.add(target.getUniqueId());
            
            Object main = toMainClaim(claim);
            Method getWorldMethod = main.getClass().getMethod("getWorld");
            org.bukkit.World claimWorld = (org.bukkit.World) getWorldMethod.invoke(main);
            if (claimWorld == null) {
                beingEjected.remove(target.getUniqueId());
                return false;
            }
            if (target.getWorld() == null || !target.getWorld().getUID().equals(claimWorld.getUID())) {
                beingEjected.remove(target.getUniqueId());
                return false;
            }

            Optional<GPBridge.ClaimCorners> optCorners = gp.getClaimCorners(main);
            if (!optCorners.isPresent()) {
                beingEjected.remove(target.getUniqueId());
                return false;
            }
            GPBridge.ClaimCorners c = optCorners.get();

            Location targetLoc = target.getLocation();
            if (targetLoc == null) {
                beingEjected.remove(target.getUniqueId());
                return false;
            }

            int x = targetLoc.getBlockX();
            int z = targetLoc.getBlockZ();
            int minX = Math.min(c.x1, c.x2);
            int maxX = Math.max(c.x1, c.x2);
            int minZ = Math.min(c.z1, c.z2);
            int maxZ = Math.max(c.z1, c.z2);

            int dx = Math.min(Math.abs(x - minX), Math.abs(maxX - x));
            int dz = Math.min(Math.abs(z - minZ), Math.abs(maxZ - z));
            int targetX = x;
            int targetZ = z;
            if (dx <= dz) {
                if (Math.abs(x - minX) <= Math.abs(maxX - x)) targetX = minX - 1; else targetX = maxX + 1;
            } else {
                if (Math.abs(z - minZ) <= Math.abs(maxZ - z)) targetZ = minZ - 1; else targetZ = maxZ + 1;
            }

            final int finalTargetX = targetX;
            final int finalTargetZ = targetZ;
            final int playerY = targetLoc.getBlockY();
            
            // On non-Folia: do everything synchronously for speed
            if (!codes.castled.gpexpansion.scheduler.SchedulerAdapter.isFolia()) {
                Location dest = findSafeLocation(target.getWorld(), finalTargetX, finalTargetZ, playerY);
                if (dest == null) {
                    dest = new Location(target.getWorld(), finalTargetX + 0.5, 
                        target.getWorld().getHighestBlockYAt(finalTargetX, finalTargetZ) + 1, finalTargetZ + 0.5);
                }
                plugin.getSchedulerFacade().teleportEntity(target, dest);
                beingEjected.remove(target.getUniqueId());
                return true;
            }
            
            // On Folia: use fast fallback (highest block) to avoid cross-region block access
            // This is less accurate but much faster and avoids thread issues
            Location baseDest = new Location(target.getWorld(), targetX + 0.5, playerY, targetZ + 0.5);
            codes.castled.gpexpansion.scheduler.SchedulerAdapter.runAtLocation(plugin, baseDest, () -> {
                // Quick safe location check - just use highest block + allow water
                Location dest = findSafeLocation(target.getWorld(), finalTargetX, finalTargetZ, playerY);
                if (dest == null) {
                    int y = target.getWorld().getHighestBlockYAt(finalTargetX, finalTargetZ) + 1;
                    dest = new Location(target.getWorld(), finalTargetX + 0.5, y, finalTargetZ + 0.5);
                }
                final Location finalDest = dest;
                plugin.getSchedulerFacade().teleportEntity(target, finalDest);
                // Clear ejection flag after a short delay to ensure teleport completes
                codes.castled.gpexpansion.scheduler.SchedulerAdapter.runLaterEntity(plugin, target, 
                    () -> beingEjected.remove(target.getUniqueId()), 5L);
            });
            return true;
        } catch (ReflectiveOperationException e) {
            beingEjected.remove(target.getUniqueId());
            return false;
        }
    }
    
    /**
     * Find a safe location to teleport to, allowing water as valid destination.
     */
    private Location findSafeLocation(org.bukkit.World world, int x, int z, int startY) {
        // Start from player's current Y and search down then up
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        
        // First try around player's current height
        for (int dy = 0; dy <= 20; dy++) {
            // Check below first
            int checkY = startY - dy;
            if (checkY >= minY) {
                Location safe = checkSafeAt(world, x, checkY, z);
                if (safe != null) return safe;
            }
            // Then check above
            checkY = startY + dy;
            if (checkY < maxY - 1) {
                Location safe = checkSafeAt(world, x, checkY, z);
                if (safe != null) return safe;
            }
        }
        
        // Fallback: scan from sea level
        int seaLevel = world.getSeaLevel();
        for (int y = seaLevel; y < maxY - 1; y++) {
            Location safe = checkSafeAt(world, x, y, z);
            if (safe != null) return safe;
        }
        
        return null;
    }
    
    /**
     * Check if a specific Y position is safe (feet can be in air/water, head in air/water, standing on solid/water).
     */
    private Location checkSafeAt(org.bukkit.World world, int x, int y, int z) {
        org.bukkit.block.Block feet = world.getBlockAt(x, y, z);
        org.bukkit.block.Block head = world.getBlockAt(x, y + 1, z);
        org.bukkit.block.Block ground = world.getBlockAt(x, y - 1, z);
        
        org.bukkit.Material feetType = feet.getType();
        org.bukkit.Material headType = head.getType();
        org.bukkit.Material groundType = ground.getType();
        
        // Feet and head must be passable (air, water, or other non-solid)
        boolean feetOk = !feetType.isSolid() || feetType == org.bukkit.Material.WATER;
        boolean headOk = !headType.isSolid() || headType == org.bukkit.Material.WATER;
        
        // Ground must be solid OR water (can swim)
        boolean groundOk = groundType.isSolid() || groundType == org.bukkit.Material.WATER;
        
        if (feetOk && headOk && groundOk) {
            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }
}
