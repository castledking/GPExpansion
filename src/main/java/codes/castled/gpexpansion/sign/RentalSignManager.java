package codes.castled.gpexpansion.sign;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.util.EcoKind;
import codes.castled.gpexpansion.util.RentalSnapshotUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.SignSide;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;
import java.util.UUID;

public class RentalSignManager {

    private final GPExpansionPlugin plugin;

    public RentalSignManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    public enum ResetCause {
        GENERAL,
        EXPIRE,
        EVICT
    }

    @SuppressWarnings("all")
    public void resetRentalSign(Block signBlock) {
        resetRentalSign(signBlock, ResetCause.GENERAL);
    }

    @SuppressWarnings("all")
    public void resetRentalSign(Block signBlock, ResetCause cause) {
        if (!(signBlock.getState() instanceof Sign sign)) return;
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        NamespacedKey keyKind = new NamespacedKey(plugin, "sign.kind");
        NamespacedKey keyClaim = new NamespacedKey(plugin, "sign.claimId");
        NamespacedKey keyRenter = new NamespacedKey(plugin, "rent.renter");
        NamespacedKey keyExpiry = new NamespacedKey(plugin, "rent.expiry");
        NamespacedKey keyEcoAmt = new NamespacedKey(plugin, "sign.ecoAmt");
        NamespacedKey keyEcoKind = new NamespacedKey(plugin, "sign.ecoKind");
        NamespacedKey keyPerClick = new NamespacedKey(plugin, "sign.perClick");
        NamespacedKey keyMaxCap = new NamespacedKey(plugin, "sign.maxCap");
        if (!"RENT".equals(pdc.get(keyKind, PersistentDataType.STRING))) return;
        String claimId = pdc.get(keyClaim, PersistentDataType.STRING);
        String renterStr = pdc.get(keyRenter, PersistentDataType.STRING);
        codes.castled.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();

        boolean hasActiveRentalOrEviction = claimId != null && (
            dataStore.getRental(claimId).isPresent() || dataStore.getEviction(claimId).isPresent());

        if (claimId != null && hasActiveRentalOrEviction && plugin.getSnapshotStore() != null) {
            codes.castled.gpexpansion.gp.GPBridge gpBridge = new codes.castled.gpexpansion.gp.GPBridge();
            Optional<Object> claimOpt = gpBridge.findClaimById(claimId);
            if (claimOpt.isPresent()) {
                org.bukkit.World world = signBlock.getWorld();
                Optional<codes.castled.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry> snapshotToRestore =
                    switch (cause) {
                        case EXPIRE -> plugin.getConfigManager().isRentSnapshotAutoRestoreOnRentalExpire()
                            ? plugin.getSnapshotStore().getLatestSnapshot(claimId)
                            : Optional.empty();
                        case EVICT -> plugin.getConfigManager().isRentSnapshotAutoRestoreOnEvictionComplete()
                            ? plugin.getSnapshotStore().getLatestSnapshot(claimId)
                            : Optional.empty();
                        case GENERAL -> Optional.empty();
                    };

                if (cause == ResetCause.EVICT && plugin.getConfigManager().isRentSnapshotAutoCreateBeforeEvictionComplete()) {
                    RentalSnapshotUtil.createSnapshot(plugin, claimId, claimOpt.get(), "before eviction complete", ignored -> {
                        snapshotToRestore.ifPresent(snapshot ->
                            RentalSnapshotUtil.restoreSnapshot(plugin, claimId, snapshot.id, world, signBlock.getLocation()));
                    });
                } else {
                    snapshotToRestore.ifPresent(snapshot ->
                        RentalSnapshotUtil.restoreSnapshot(plugin, claimId, snapshot.id, world, signBlock.getLocation()));
                }
            }
        }

        if (cause == ResetCause.EVICT && claimId != null && plugin.getConfigManager().isOwnerNotifiedOnEvictionComplete()) {
            codes.castled.gpexpansion.gp.GPBridge gpBridge = new codes.castled.gpexpansion.gp.GPBridge();
            Optional<Object> claimOpt = gpBridge.findClaimById(claimId);
            claimOpt.map(gpBridge::getClaimOwner)
                .map(Bukkit::getPlayer)
                .ifPresent(owner -> owner.sendMessage(plugin.getMessages().get("eviction.completed-owner-notify", "{id}", claimId)));
        }

        if (claimId != null) {
            dataStore.clearRental(claimId);
            dataStore.clearEviction(claimId);
            dataStore.save();
        }
        boolean clearRenterTrust = switch (cause) {
            case EXPIRE -> plugin.getConfigManager().isRenterTrustClearedOnExpire();
            case EVICT -> plugin.getConfigManager().isRenterTrustClearedOnEvict();
            case GENERAL -> true;
        };
        if (clearRenterTrust && claimId != null && renterStr != null) {
            codes.castled.gpexpansion.gp.GPBridge gpBridge = new codes.castled.gpexpansion.gp.GPBridge();
            Optional<Object> claimOpt = gpBridge.findClaimById(claimId);
            if (claimOpt.isPresent()) {
                try {
                    UUID renter = UUID.fromString(renterStr);
                    String renterName = Bukkit.getOfflinePlayer(renter).getName();
                    if (renterName != null) {
                        boolean untrusted = gpBridge.untrust(renterName, claimOpt.get());
                        if (untrusted) {
                            gpBridge.saveClaim(claimOpt.get());
                            plugin.getLogger().info("Removed trust for " + renterName + " from claim " + claimId);
                        }
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
        pdc.remove(keyRenter);
        pdc.remove(keyExpiry);
        String ecoAmt = pdc.get(keyEcoAmt, PersistentDataType.STRING);
        String ecoKindStr = pdc.get(keyEcoKind, PersistentDataType.STRING);
        String perClick = pdc.get(keyPerClick, PersistentDataType.STRING);
        String maxCap = pdc.get(keyMaxCap, PersistentDataType.STRING);
        if (ecoAmt == null) ecoAmt = "";
        if (perClick == null) perClick = "";
        if (maxCap == null) maxCap = "";
        String ecoFormatted = formatEcoForSign(ecoKindStr, ecoAmt);
        boolean hanging = signBlock.getType().name().contains("HANGING_SIGN");
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        Component displayLine0 = LegacyComponentSerializer.legacyAmpersand().deserialize(
            plugin.getMessages().getRaw(hanging ? "sign-interaction.sign-display-rent-hanging" : "sign-interaction.sign-display-rent-full"));
        SignSide front = sign.getSide(org.bukkit.block.sign.Side.FRONT);
        front.line(0, displayLine0);
        front.line(1, legacy.deserialize("§0ID: §6" + (claimId != null ? claimId : "")));
        front.line(2, legacy.deserialize("§0" + ecoFormatted + "/" + perClick));
        front.line(3, legacy.deserialize("§0Max: " + maxCap));
        sign.update(true);
    }

    private String formatEcoForSign(String ecoKindStr, String ecoAmtRaw) {
        if (ecoAmtRaw == null || ecoAmtRaw.isEmpty()) return "";
        if (ecoKindStr == null) ecoKindStr = "MONEY";
        try {
            EcoKind kind = EcoKind.valueOf(ecoKindStr.toUpperCase());
            switch (kind) {
                case MONEY:
                    try {
                        return plugin.getEconomyManager().formatMoneyForSign(Double.parseDouble(ecoAmtRaw));
                    } catch (NumberFormatException ignored) {
                        return "$" + ecoAmtRaw;
                    }
                case EXPERIENCE:
                    return ecoAmtRaw.toUpperCase().endsWith("L")
                        ? ecoAmtRaw.substring(0, ecoAmtRaw.length() - 1) + " Levels"
                        : ecoAmtRaw + " XP";
                case CLAIMBLOCKS:
                    return ecoAmtRaw + " blocks";
                case ITEM:
                    return ecoAmtRaw + " items";
                default:
                    return ecoAmtRaw;
            }
        } catch (IllegalArgumentException ignored) {
            return ecoAmtRaw;
        }
    }

    public long getEvictionNoticePeriodMs() {
        Object val = plugin.getConfigManager().getRentEvictionNoticePeriod();
        if (val != null) {
            if (val instanceof Number n) {
                return n.longValue() * 24L * 60L * 60L * 1000L;
            }
            String s = String.valueOf(val).trim();
            if (!s.isEmpty()) {
                long ms = parseDurationToMillis(s);
                if (ms > 0) return ms;
            }
        }
        return 14L * 24L * 60L * 60L * 1000L;
    }

    public String getEvictionNoticePeriodDisplay() {
        long ms = getEvictionNoticePeriodMs();
        long sec = ms / 1000L;
        long min = sec / 60L;
        long hr = min / 60L;
        long d = hr / 24L;
        if (d > 0) return d + " day" + (d == 1 ? "" : "s");
        if (hr > 0) return hr + " hour" + (hr == 1 ? "" : "s");
        if (min > 0) return min + " minute" + (min == 1 ? "" : "s");
        return sec + " second" + (sec == 1 ? "" : "s");
    }

    private long parseDurationToMillis(String s) {
        if (s == null || s.isEmpty()) return 0L;
        String numStr = s.replaceAll("[^0-9]", "");
        if (numStr.isEmpty()) return 0L;
        long n = Long.parseLong(numStr);
        if (s.length() == numStr.length()) return n * 24L * 60L * 60L * 1000L;
        char unit = Character.toLowerCase(s.charAt(s.length() - 1));
        return switch (unit) {
            case 's' -> n * 1000L;
            case 'm' -> n * 60L * 1000L;
            case 'h' -> n * 60L * 60L * 1000L;
            case 'd' -> n * 24L * 60L * 60L * 1000L;
            case 'w' -> n * 7L * 24L * 60L * 60L * 1000L;
            default -> 0L;
        };
    }
}
