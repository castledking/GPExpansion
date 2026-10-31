package codes.castled.gpexpansion.flags;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.events.ClaimDeletedEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimPermissionCheckEvent;
import me.ryanhamshire.GriefPrevention.events.PreventPvPEvent;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * GriefPrevention-side half of the built-in flags, kept apart from {@link ClaimFlagListener} so a
 * GriefPrevention build without one of these events can't stop the Bukkit-side listener loading.
 */
public final class ClaimFlagGPListener implements Listener {

    private final ClaimFlagManager manager;
    private final ClaimFlagListener flags;
    private final Runnable saveStore;

    public ClaimFlagGPListener(ClaimFlagManager manager, ClaimFlagListener flags, Runnable saveStore) {
        this.manager = manager;
        this.flags = flags;
        this.saveStore = saveStore;
    }

    /**
     * Allow on a player flag: clear GriefPrevention's denial for actions the flag covers, so
     * outsiders get through without the "you don't have permission" message. Edit and Manage
     * checks (claim tools, trust management) are never touched.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPermissionCheck(ClaimPermissionCheckEvent event) {
        if (event.getDenialReason() == null || !manager.isEnabled()) return;
        if (event.getCheckedPlayer() == null) return;
        Event trigger = event.getTriggeringEvent();
        if (trigger == null) return;
        ClaimFlag.Trust required = ClaimFlagManager.trustFor(event.getRequiredPermission());
        if (required == null) return;
        Claim claim = event.getClaim();
        if (claim != null && flags.allowsOverride(trigger, claim, required)) {
            event.setDenialReason(null);
        }
    }

    /** Allow on PvP lifts GriefPrevention's "players in claims are safe" protection. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreventPvP(PreventPvPEvent event) {
        if (!manager.isActive(ClaimFlag.PVP)) return;
        Claim claim = event.getClaim();
        if (claim != null && Boolean.TRUE.equals(manager.resolve(ClaimFlag.PVP, claim))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClaimDeleted(ClaimDeletedEvent event) {
        String claimId = ClaimFlagManager.claimId(event.getClaim());
        if (claimId != null && manager.store().removeClaim(claimId)) {
            saveStore.run();
        }
    }
}
