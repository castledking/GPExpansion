package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public class BanEnforcementListener implements Listener {
    private final GPExpansionPlugin plugin;
    private final GPBridge gp = new GPBridge();

    public BanEnforcementListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isWithinVerticalBounds(Object claim, Location location) {
        if (claim == null || location == null) return false;
        return gp.getClaimCorners(claim)
                .map(corners -> {
                    int minY = Math.min(corners.y1, corners.y2);
                    int maxY = Math.max(corners.y1, corners.y2);
                    int y = location.getBlockY();
                    return y >= minY && y <= maxY;
                })
                .orElse(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        // Only act when changing block coordinates to reduce spam
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockZ() == e.getTo().getBlockZ() && e.getFrom().getWorld() == e.getTo().getWorld()) return;
        handlePositionCheck(e.getPlayer(), e.getTo(), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        handlePositionCheck(e.getPlayer(), e.getTo(), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        // Deny interactions if banned inside claim
        Player p = e.getPlayer();
        if (handlePositionCheck(p, p.getLocation(), false)) {
            e.setCancelled(true);
        }
    }

    // Returns true if action should be denied (blocked)
    private boolean handlePositionCheck(Player player, Location to, boolean mayTeleportOut) {
        if (to == null) return false;
        Optional<Object> oc = gp.getClaimAt(to, player);
        if (!oc.isPresent()) return false;
        Object claim = oc.get();
        Object main = toMainClaim(claim);

        // Respect 3D subdivisions by checking Y bounds when present
        if (!isWithinVerticalBounds(claim, to)) {
            return false;
        }

        String claimId = gp.getClaimId(main).orElse(null);
        if (claimId == null) return false;

        UUID uuid = player.getUniqueId();
        boolean pubBanned = plugin.getBanStore().isPublicBanned(claimId);
        boolean playerBanned = plugin.getBanStore().getPlayers(claimId).contains(uuid);

        if (!pubBanned && !playerBanned) return false;

        // If public ban, allow trusted users to pass
        if (pubBanned && isTrusted(main, player)) return false;

        // Deny entry / interaction
        if (mayTeleportOut) {
            // Eject to closest edge
            plugin.runAtEntity(player, () -> {
                if (!ejectFromClaim(player, main)) {
                    plugin.runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spawn " + player.getName()));
                }
            });
        }
        player.sendMessage(ChatColor.RED + "You are not allowed to enter this claim!");
        return true;
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
            Object main = toMainClaim(claim);
            Location lesser = (Location) main.getClass().getMethod("getLesserBoundaryCorner").invoke(main);
            Location greater = (Location) main.getClass().getMethod("getGreaterBoundaryCorner").invoke(main);
            if (lesser == null || greater == null) return false;
            if (target.getWorld() == null || lesser.getWorld() == null) return false;
            if (!target.getWorld().getUID().equals(lesser.getWorld().getUID())) return false;

            int x = target.getLocation().getBlockX();
            int z = target.getLocation().getBlockZ();
            int minX = Math.min(lesser.getBlockX(), greater.getBlockX());
            int maxX = Math.max(lesser.getBlockX(), greater.getBlockX());
            int minZ = Math.min(lesser.getBlockZ(), greater.getBlockZ());
            int maxZ = Math.max(lesser.getBlockZ(), greater.getBlockZ());

            int dx = Math.min(Math.abs(x - minX), Math.abs(maxX - x));
            int dz = Math.min(Math.abs(z - minZ), Math.abs(maxZ - z));
            int targetX = x;
            int targetZ = z;
            if (dx <= dz) {
                if (Math.abs(x - minX) <= Math.abs(maxX - x)) targetX = minX - 1; else targetX = maxX + 1;
            } else {
                if (Math.abs(z - minZ) <= Math.abs(maxZ - z)) targetZ = minZ - 1; else targetZ = maxZ + 1;
            }
            Location dest = new Location(target.getWorld(), targetX + 0.5, 0, targetZ + 0.5);
            int y = target.getWorld().getHighestBlockYAt(targetX, targetZ) + 1;
            dest.setY(y);
            plugin.teleportEntity(target, dest);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
