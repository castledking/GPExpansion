package codes.castled.gpexpansion.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.storage.ClaimDataStore;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Intercepts claim abandon/delete commands to block abandoning claims that have active rentals,
 * sold mailboxes, or for-sale market listings, displaying Kyori clickable [EVICT] / [CANCEL] actions.
 */
public class ClaimAbandonListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final GPBridge gp = new GPBridge();

    public ClaimAbandonListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || !raw.startsWith("/")) return;
        String trimmed = raw.substring(1).trim();
        if (trimmed.isEmpty()) return;

        String[] parts = trimmed.split("\\s+");
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (root.contains(":")) {
            root = root.substring(root.indexOf(':') + 1);
        }

        Player player = event.getPlayer();

        // Check /abandonallclaims and /claim abandonall
        boolean isAbandonAll = root.equals("abandonallclaims")
            || (root.equals("claim") && parts.length >= 2 && parts[1].equalsIgnoreCase("abandonall"));
        if (isAbandonAll) {
            if (plugin.getMarketManager() != null) {
                Set<String> checked = new LinkedHashSet<>();
                for (Object claim : gp.getClaimsFor(player)) {
                    Object top = topLevel(claim);
                    String cid = gp.getClaimId(top).orElse(null);
                    if (cid != null && checked.add(cid) && plugin.getMarketManager().checkAndBlockAbandon(player, cid)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            return;
        }

        boolean isClaimAbandon = (root.equals("claim") || root.equals("claims"))
            && parts.length >= 2 && parts[1].equalsIgnoreCase("abandon");
        boolean isTopLevelDelete = root.equals("abandontoplevelclaim") || root.equals("deleteclaim");
        boolean isAbandon = root.equals("abandonclaim") || root.equals("abandon") || isTopLevelDelete || isClaimAbandon;
        if (!isAbandon) {
            return;
        }

        String claimId = null;
        int startIdx = isClaimAbandon ? 2 : 1;
        for (int i = startIdx; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase("all") && plugin.getMarketManager() != null) {
                Set<String> checked = new LinkedHashSet<>();
                for (Object claim : gp.getClaimsFor(player)) {
                    Object top = topLevel(claim);
                    String cid = gp.getClaimId(top).orElse(null);
                    if (cid != null && checked.add(cid) && plugin.getMarketManager().checkAndBlockAbandon(player, cid)) {
                        event.setCancelled(true);
                        return;
                    }
                }
                return;
            }
            if (parts[i].matches("\\d+")) {
                claimId = parts[i];
                break;
            }
        }

        if (claimId == null) {
            claimId = resolveStandingClaimId(player, isTopLevelDelete || !root.equals("abandonclaim"));
        }

        if (claimId != null) {
            if (plugin.getMarketManager() != null && plugin.getMarketManager().checkAndBlockAbandon(player, claimId)) {
                event.setCancelled(true);
                return;
            }
            scheduleRentalCleanupAfterAbandon(player, claimId);
        }
    }

    private String resolveStandingClaimId(Player player, boolean resolveToTopLevel) {
        Optional<Object> claimOpt = gp.getClaimAt(player.getLocation(), player);
        if (claimOpt.isEmpty()) return null;
        Object claim = claimOpt.get();
        if (resolveToTopLevel || gp.isSubdivision(claim)) {
            Object top = topLevel(claim);
            // If standing inside a mailbox/subdivision and running /abandonclaim without top-level, check both sub & top if top is targeted
            if (resolveToTopLevel) {
                claim = top;
            }
        }
        boolean canModify = gp.isOwner(claim, player.getUniqueId())
            || player.hasPermission("griefprevention.deleteclaims")
            || player.hasPermission("griefprevention.admin");
        if (!canModify) return null;
        return gp.getClaimId(claim).orElse(null);
    }

    private Object topLevel(Object claim) {
        Object cur = claim;
        while (cur != null) {
            Optional<Object> parent = gp.getParentClaim(cur);
            if (parent.isEmpty() || parent.get() == cur) break;
            cur = parent.get();
        }
        return cur;
    }

    private void scheduleRentalCleanupAfterAbandon(Player player, String claimId) {
        if (!plugin.getConfigManager().isRentClearedOnAbandon()) return;
        if (claimId == null || claimId.isBlank()) return;

        SchedulerAdapter.runLaterEntity(plugin, player, () -> {
            if (gp.findClaimById(claimId).isPresent()) {
                return;
            }
            ClaimDataStore dataStore = plugin.getClaimDataStore();
            boolean hadRental = dataStore.getRental(claimId).isPresent();
            boolean hadPendingRent = dataStore.getPendingRent(claimId).isPresent();
            boolean hadEviction = dataStore.getEviction(claimId).isPresent();
            if (!hadRental && !hadPendingRent && !hadEviction) {
                return;
            }
            dataStore.clearRental(claimId);
            dataStore.clearPendingRent(claimId);
            dataStore.clearEviction(claimId);
            dataStore.save();
        }, 1L);
    }
}
