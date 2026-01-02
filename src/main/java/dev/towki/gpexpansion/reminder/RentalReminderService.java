package dev.towki.gpexpansion.reminder;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.storage.RentalStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;

public class RentalReminderService {
    private final GPExpansionPlugin plugin;
    private final RentalStore store;

    // Per-claim session reminder mask (resets each restart)
    private final Map<String, Integer> claimMasks = new HashMap<>();
    // Per-player join reminder mask (resets each restart) for 25/50/75 joins
    private final Map<UUID, Integer> joinPctMask = new HashMap<>();
    // Track if first-join after restart message has been shown for a player
    private final Set<UUID> firstJoinShown = new HashSet<>();

    private int bukkitTaskId = -1;
    // Folia scheduled task handle (ScheduledTask), kept as Object to avoid compile dependency
    private volatile Object foliaTask = null;

    // Bit indices for claimMasks
    private static final int BIT_PCT25 = 0;
    private static final int BIT_PCT50 = 1;
    private static final int BIT_PCT75 = 2;

    private static final int BIT_24H = 3;    // <= 24h
    private static final int BIT_22H = 4;    // <= 22h
    private static final int BIT_20H = 5;
    private static final int BIT_18H = 6;
    private static final int BIT_16H = 7;
    private static final int BIT_14H = 8;
    private static final int BIT_12H = 9;
    private static final int BIT_10H = 10;
    private static final int BIT_8H  = 11;
    private static final int BIT_6H  = 12;

    private static final int BIT_5H  = 13;
    private static final int BIT_4H  = 14;
    private static final int BIT_3H  = 15;
    private static final int BIT_2H  = 16;
    private static final int BIT_1H  = 17;

    private static final int BIT_30M = 18;
    private static final int BIT_10M = 19;
    private static final int BIT_5M  = 20;
    private static final int BIT_1M  = 21;

    private static final int BIT_10S = 22;
    private static final int BIT_5S  = 23;
    private static final int BIT_4S  = 24;
    private static final int BIT_3S  = 25;
    private static final int BIT_2S  = 26;
    private static final int BIT_1S  = 27;

    private static final int BIT_EXPIRED = 28; // final expiry message

    public RentalReminderService(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.store = plugin.getRentalStore();
    }

    public void start() {
        stop();
        // Prefer Folia GlobalRegionScheduler when available
        try {
            Object server = Bukkit.getServer();
            java.lang.reflect.Method getGrs = server.getClass().getMethod("getGlobalRegionScheduler");
            Object grs = getGrs.invoke(server);
            // Signature: runAtFixedRate(Plugin, Consumer<ScheduledTask>, long initialDelay, long period)
            java.lang.reflect.Method runAtFixedRate = grs.getClass().getMethod(
                    "runAtFixedRate",
                    org.bukkit.plugin.Plugin.class,
                    java.util.function.Consumer.class,
                    long.class,
                    long.class
            );
            java.util.function.Consumer<Object> consumer = (scheduledTask) -> {
                // Capture task handle once so we can cancel later
                if (foliaTask == null) {
                    foliaTask = scheduledTask;
                }
                tick();
            };
            runAtFixedRate.invoke(grs, plugin, consumer, 20L, 20L);
            return; // scheduled via Folia
        } catch (Throwable ignored) {
            // Not Folia, or API not present: fall through to Bukkit scheduler
        }
        // Spigot/Paper: Run every 1 second to catch sub-minute countdowns precisely
        this.bukkitTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L).getTaskId();
    }

    public void stop() {
        // Cancel Folia task if present
        if (foliaTask != null) {
            try {
                java.lang.reflect.Method cancel = foliaTask.getClass().getMethod("cancel");
                cancel.invoke(foliaTask);
            } catch (Throwable ignored) {}
            foliaTask = null;
        }
        // Cancel Bukkit task if present
        if (bukkitTaskId != -1) {
            try { Bukkit.getScheduler().cancelTask(bukkitTaskId); } catch (Throwable ignored) {}
            bukkitTaskId = -1;
        }
        claimMasks.clear();
        joinPctMask.clear();
        firstJoinShown.clear();
    }

    private void tick() {
        if (store == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, RentalStore.Entry> e : new ArrayList<>(store.all().entrySet())) {
            String claimId = e.getKey();
            RentalStore.Entry entry = e.getValue();
            // Skip reminders for evicted rentals
            if (entry.beingEvicted) {
                continue;
            }
            long start = entry.start > 0 ? entry.start : now; // fallback
            long expiry = entry.expiry;
            long duration = Math.max(1L, expiry - start);
            long remaining = expiry - now;

            Player renter = Bukkit.getPlayer(entry.renter);
            boolean online = renter != null && renter.isOnline();

            int mask = claimMasks.getOrDefault(claimId, 0);

            if (remaining <= 0) {
                // expired
                if ((mask & (1 << BIT_EXPIRED)) == 0) {
                    if (online) {
                        renter.sendMessage(color("&6Your rented claim has expired."));
                    } else {
                        // flag a pending notice
                        entry.pendingExpiryNotice = true;
                        store.update(claimId, entry);
                        store.save();
                    }
                    mask |= (1 << BIT_EXPIRED);
                    claimMasks.put(claimId, mask);
                }
                continue;
            }

            // Percent milestones if player is online at that time
            double elapsedFrac = Math.max(0d, Math.min(1d, (now - start) / (double) duration));
            if (online) {
                if (elapsedFrac >= 0.25 && (mask & (1 << BIT_PCT25)) == 0) {
                    sendRemain(renter, remaining);
                    mask |= (1 << BIT_PCT25);
                }
                if (elapsedFrac >= 0.50 && (mask & (1 << BIT_PCT50)) == 0) {
                    sendRemain(renter, remaining);
                    mask |= (1 << BIT_PCT50);
                }
                if (elapsedFrac >= 0.75 && (mask & (1 << BIT_PCT75)) == 0) {
                    sendRemain(renter, remaining);
                    mask |= (1 << BIT_PCT75);
                }
            }

            // Time-based thresholds
            // 24h in 2h steps down to 6h, then 1h steps to 1h, then 30m/10m/5m/1m, then 10s..1s
            mask = checkThreshold(online, renter, remaining, mask, 24 * H, BIT_24H);
            mask = checkThreshold(online, renter, remaining, mask, 22 * H, BIT_22H);
            mask = checkThreshold(online, renter, remaining, mask, 20 * H, BIT_20H);
            mask = checkThreshold(online, renter, remaining, mask, 18 * H, BIT_18H);
            mask = checkThreshold(online, renter, remaining, mask, 16 * H, BIT_16H);
            mask = checkThreshold(online, renter, remaining, mask, 14 * H, BIT_14H);
            mask = checkThreshold(online, renter, remaining, mask, 12 * H, BIT_12H);
            mask = checkThreshold(online, renter, remaining, mask, 10 * H, BIT_10H);
            mask = checkThreshold(online, renter, remaining, mask, 8 * H, BIT_8H);
            mask = checkThreshold(online, renter, remaining, mask, 6 * H, BIT_6H);

            mask = checkThreshold(online, renter, remaining, mask, 5 * H, BIT_5H);
            mask = checkThreshold(online, renter, remaining, mask, 4 * H, BIT_4H);
            mask = checkThreshold(online, renter, remaining, mask, 3 * H, BIT_3H);
            mask = checkThreshold(online, renter, remaining, mask, 2 * H, BIT_2H);
            mask = checkThreshold(online, renter, remaining, mask, 1 * H, BIT_1H);

            mask = checkThreshold(online, renter, remaining, mask, 30 * M, BIT_30M);
            mask = checkThreshold(online, renter, remaining, mask, 10 * M, BIT_10M);
            mask = checkThreshold(online, renter, remaining, mask, 5 * M, BIT_5M);
            mask = checkThreshold(online, renter, remaining, mask, 1 * M, BIT_1M);

            mask = checkThreshold(online, renter, remaining, mask, 10 * S, BIT_10S);
            mask = checkThreshold(online, renter, remaining, mask, 5 * S, BIT_5S);
            mask = checkThreshold(online, renter, remaining, mask, 4 * S, BIT_4S);
            mask = checkThreshold(online, renter, remaining, mask, 3 * S, BIT_3S);
            mask = checkThreshold(online, renter, remaining, mask, 2 * S, BIT_2S);
            mask = checkThreshold(online, renter, remaining, mask, 1 * S, BIT_1S);

            claimMasks.put(claimId, mask);
        }
    }

    private static final long S = 1000L;
    private static final long M = 60 * S;
    private static final long H = 60 * M;

    private int checkThreshold(boolean online, Player renter, long remaining, int mask, long threshold, int bit) {
        if ((mask & (1 << bit)) != 0) return mask;
        if (remaining <= threshold) {
            if (online && renter != null) sendRemain(renter, remaining);
            return mask | (1 << bit);
        }
        return mask;
    }

    private void sendRemain(Player player, long remainingMs) {
        player.sendMessage(color("&6Your rented claim will expire in: &e" + formatDuration(remainingMs)));
    }

    private String color(String amp) {
        return ChatColor.translateAlternateColorCodes('&', amp);
    }

    private String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = (ms + 999) / 1000; // ceil seconds
        long days = totalSec / 86400; totalSec %= 86400;
        long hours = totalSec / 3600; totalSec %= 3600;
        long minutes = totalSec / 60; long seconds = totalSec % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    // Called by PlayerJoin listener
    public void onPlayerJoin(Player player) {
        UUID u = player.getUniqueId();
        boolean first = !firstJoinShown.contains(u);
        if (first) firstJoinShown.add(u);

        long now = System.currentTimeMillis();
        for (Map.Entry<String, RentalStore.Entry> e : store.all().entrySet()) {
            RentalStore.Entry entry = e.getValue();
            if (entry == null || entry.renter == null || !entry.renter.equals(u)) continue;

            // Expired while offline?
            if (entry.expiry <= now && entry.pendingExpiryNotice) {
                player.sendMessage(color("&6Your rented claim has expired."));
                entry.pendingExpiryNotice = false; // clear flag after shown
                store.update(e.getKey(), entry);
                store.save();
                continue;
            }

            if (entry.expiry <= now) continue; // already expired (and no flag)

            // First join after restart: always show
            if (first) {
                long remaining = entry.expiry - now;
                sendRemain(player, remaining);
            }

            // Join-after-percentage thresholds (once per restart)
            long start = entry.start > 0 ? entry.start : now;
            long dur = Math.max(1L, entry.expiry - start);
            double frac = Math.max(0d, Math.min(1d, (now - start) / (double) dur));
            int mask = joinPctMask.getOrDefault(u, 0);
            if (frac >= 0.25 && (mask & (1 << BIT_PCT25)) == 0) {
                sendRemain(player, entry.expiry - now);
                mask |= (1 << BIT_PCT25);
            }
            if (frac >= 0.50 && (mask & (1 << BIT_PCT50)) == 0) {
                sendRemain(player, entry.expiry - now);
                mask |= (1 << BIT_PCT50);
            }
            if (frac >= 0.75 && (mask & (1 << BIT_PCT75)) == 0) {
                sendRemain(player, entry.expiry - now);
                mask |= (1 << BIT_PCT75);
            }
            joinPctMask.put(u, mask);
        }
    }
}
