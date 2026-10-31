package codes.castled.gpexpansion.listener;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataType;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.market.MarketManager;
import codes.castled.gpexpansion.storage.ClaimDataStore;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protects virtual-protocol mailbox containers from unauthorized breaking/explosions
 * and keeps {@link MarketManager} clean whenever any sign block is broken.
 */
public final class MailboxContainerListener implements Listener {

    private static final long CONFIRM_WINDOW_MS = 10_000L;

    private final GPExpansionPlugin plugin;
    private final GPBridge gp = new GPBridge();
    private final Map<String, Long> breakConfirmations = new ConcurrentHashMap<>();

    public MailboxContainerListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onContainerBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String mailboxId = findVirtualMailboxAtContainer(block.getLocation());
        if (mailboxId == null) return;

        Player player = event.getPlayer();
        ClaimDataStore ds = plugin.getClaimDataStore();
        MarketManager market = plugin.getMarketManager();

        boolean adminBypass = player.isOp()
            || player.hasPermission("griefprevention.admin")
            || player.hasPermission("griefprevention.sign.admin");

        UUID mailboxOwner = ds.getMailboxOwner(mailboxId).orElse(null);
        boolean stillForSale = market != null && market.isMailboxStillForSale(mailboxId);

        String parentClaimId = market != null ? market.resolveParentClaimId(mailboxId, block.getLocation()) : null;
        Optional<Object> parentClaimOpt = parentClaimId != null ? gp.findClaimById(parentClaimId) : gp.getClaimAt(block.getLocation());
        boolean isParentOwner = parentClaimOpt.map(c -> gp.isOwner(c, player.getUniqueId())).orElse(false);
        boolean isMailboxOwner = mailboxOwner != null && mailboxOwner.equals(player.getUniqueId());

        if (stillForSale) {
            if (!isParentOwner && !isMailboxOwner && !adminBypass) {
                event.setCancelled(true);
                plugin.getMessages().send(player, "mailbox.container-protected");
                return;
            }
            if (!confirmSneakBreak(player, block)) {
                event.setCancelled(true);
                return;
            }
            cleanupBrokenVirtualMailbox(mailboxId);
            return;
        }

        if (isMailboxOwner || adminBypass) {
            if (!confirmSneakBreak(player, block)) {
                event.setCancelled(true);
                return;
            }
            cleanupBrokenVirtualMailbox(mailboxId);
            return;
        }

        if (isParentOwner) {
            boolean evictionBypass = player.hasPermission("griefprevention.eviction.bypass");
            ClaimDataStore.EvictionData eviction = ds.getEviction(mailboxId).orElse(null);
            long now = System.currentTimeMillis();
            if (!evictionBypass) {
                if (eviction == null) {
                    event.setCancelled(true);
                    plugin.getMessages().send(player, "mailbox.container-evict-required", "{id}", mailboxId);
                    return;
                }
                if (now < eviction.effectiveAt) {
                    event.setCancelled(true);
                    plugin.getMessages().send(player, "eviction.notice-pending");
                    plugin.getMessages().send(player, "eviction.time-remaining",
                        "{time}", MarketManager.formatShortDuration(eviction.effectiveAt - now));
                    return;
                }
            }
            if (!confirmSneakBreak(player, block)) {
                event.setCancelled(true);
                return;
            }
            cleanupBrokenVirtualMailbox(mailboxId);
            return;
        }

        event.setCancelled(true);
        plugin.getMessages().send(player, "mailbox.container-protected");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectExplodedBlocks(event.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectExplodedBlocks(event.blockList().iterator());
    }

    private void protectExplodedBlocks(Iterator<Block> iterator) {
        MarketManager market = plugin.getMarketManager();
        while (iterator.hasNext()) {
            Block b = iterator.next();
            if (findVirtualMailboxAtContainer(b.getLocation()) != null) {
                iterator.remove();
            } else if (market != null && b.getType().name().contains("SIGN")) {
                market.removeListingAt(b.getLocation());
            }
        }
    }

    /** When any sign (e.g., an unprotected SELL sign) finishes breaking, remove its market listing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyBlockBreakCleanup(BlockBreakEvent event) {
        MarketManager market = plugin.getMarketManager();
        if (market == null) return;
        Block b = event.getBlock();
        market.removeListingAt(b.getLocation());
    }

    private boolean confirmSneakBreak(Player player, Block block) {
        if (!player.isSneaking()) {
            plugin.getMessages().send(player, "mailbox.container-sneak-to-break");
            return false;
        }
        String key = block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ() + ":" + player.getUniqueId();
        long now = System.currentTimeMillis();
        Long prev = breakConfirmations.get(key);
        if (prev == null || (now - prev) > CONFIRM_WINDOW_MS) {
            breakConfirmations.put(key, now);
            plugin.getMessages().send(player, "mailbox.container-break-confirm");
            return false;
        }
        breakConfirmations.remove(key);
        return true;
    }

    private void cleanupBrokenVirtualMailbox(String mailboxId) {
        ClaimDataStore ds = plugin.getClaimDataStore();
        Location signLoc = ds.getMailboxSignLocation(mailboxId).orElse(null);
        ds.clearMailbox(mailboxId);
        ds.clearEviction(mailboxId);
        ds.save();

        MarketManager market = plugin.getMarketManager();
        if (market != null) {
            market.store().forgetMailbox(mailboxId);
            if (signLoc != null) {
                market.removeListingAt(signLoc);
            }
            market.store().saveIfDirty();
        }

        if (signLoc != null && signLoc.getWorld() != null) {
            Block signBlock = signLoc.getBlock();
            if (signBlock.getState() instanceof Sign sign) {
                NamespacedKey keyKind = new NamespacedKey(plugin, "sign.kind");
                if ("MAILBOX".equals(sign.getPersistentDataContainer().get(keyKind, PersistentDataType.STRING))) {
                    signBlock.setType(Material.AIR);
                }
            }
        }
    }

    private String findVirtualMailboxAtContainer(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        ClaimDataStore ds = plugin.getClaimDataStore();
        String syntheticKey = "v:" + loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
        if (ds.isMailbox(syntheticKey)) {
            return syntheticKey;
        }
        for (String id : ds.getAllMailboxes().keySet()) {
            if (id == null || !id.startsWith("v:")) continue;
            Location cLoc = ds.getMailboxContainerLocation(id).orElse(null);
            if (cLoc != null && cLoc.getWorld() != null
                    && cLoc.getWorld().equals(loc.getWorld())
                    && cLoc.getBlockX() == loc.getBlockX()
                    && cLoc.getBlockY() == loc.getBlockY()
                    && cLoc.getBlockZ() == loc.getBlockZ()) {
                return id;
            }
        }
        return null;
    }
}
