package codes.castled.gpexpansion.transfer;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPBridge;

import java.util.Optional;
import java.util.UUID;

/**
 * Player-to-player claim transfers started from the claim options GUI.
 *
 * <p>Only GriefPrevention3D lets players give claims away, and only while its
 * {@code Claims.TransferClaim.Enabled} option is on. Upstream GriefPrevention keeps
 * {@code /transferclaim} for staff, so the GUI shows the button as unavailable there.
 * The checks mirror GP3D's {@code /transferclaim <player> confirm}: top-level claims only,
 * owner only, the recipient needs the claim blocks and a free claim slot, and the giver
 * pays {@code Claims.TransferClaim.Price} unless they hold {@code griefprevention.transferclaim.free}.
 * Staff with {@code griefprevention.transferclaim.others} skip the fee and recipient limits,
 * as they do in GP3D.
 */
public final class ClaimTransferService {

    public static final String PERMISSION = "griefprevention.transferclaim";
    public static final String STAFF_PERMISSION = "griefprevention.transferclaim.others";
    public static final String FREE_PERMISSION = "griefprevention.transferclaim.free";

    public enum Availability {
        /** GP3D with {@code Claims.TransferClaim.Enabled: true}. */
        AVAILABLE,
        /** Upstream GriefPrevention, or a GP3D build without player transfers. */
        UNSUPPORTED,
        /** GP3D with player transfers switched off. */
        DISABLED
    }

    /** Outcome of a validation step: a lang key and its placeholders when something is wrong. */
    public record Problem(String messageKey, String... placeholders) {}

    private final GPExpansionPlugin plugin;
    private final GPBridge gp;

    public ClaimTransferService(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.gp = new GPBridge();
    }

    public Availability availability() {
        if (!gp.isAvailable() || !gp.isGP3D()) {
            return Availability.UNSUPPORTED;
        }
        Boolean enabled = gp.getGpConfigBoolean("config_claims_transferClaimEnabled");
        if (enabled == null) {
            return Availability.UNSUPPORTED;
        }
        return enabled ? Availability.AVAILABLE : Availability.DISABLED;
    }

    public boolean isStaff(Player player) {
        return player.hasPermission(STAFF_PERMISSION);
    }

    /** What giving a claim away costs this player, through Vault. */
    public double fee(Player player) {
        if (isStaff(player) || player.hasPermission(FREE_PERMISSION)) {
            return 0.0D;
        }
        Double price = gp.getGpConfigDouble("config_claims_transferClaimPrice");
        return price != null ? Math.max(0.0D, price) : 0.0D;
    }

    public String formatFee(double fee) {
        return fee > 0 ? plugin.getEconomyManager().formatMoney(fee) : plugin.getMessages().getRaw("claim.transfer-fee-free");
    }

    /** Checks that don't depend on the recipient: availability, permission, claim type, ownership. */
    public Optional<Problem> checkSender(Player sender, Object claim) {
        Availability availability = availability();
        if (availability == Availability.UNSUPPORTED) {
            return Optional.of(new Problem("claim.transfer-unavailable-fork"));
        }
        if (availability == Availability.DISABLED) {
            return Optional.of(new Problem("claim.transfer-unavailable-disabled"));
        }
        if (!sender.hasPermission(PERMISSION)) {
            return Optional.of(new Problem("general.no-permission"));
        }
        if (claim == null) {
            return Optional.of(new Problem("claim.transfer-claim-missing"));
        }
        if (gp.isSubdivision(claim)) {
            return Optional.of(new Problem("claim.transfer-top-level-only"));
        }
        if (isStaff(sender)) {
            return Optional.empty();
        }
        if (gp.isAdminClaim(claim) || !gp.isOwner(claim, sender.getUniqueId())) {
            return Optional.of(new Problem("claim.transfer-not-owner"));
        }
        return Optional.empty();
    }

    /** Full validation before the confirmation step. */
    public Optional<Problem> checkTransfer(Player sender, Object claim, OfflinePlayer recipient) {
        Optional<Problem> senderProblem = checkSender(sender, claim);
        if (senderProblem.isPresent()) {
            return senderProblem;
        }
        if (recipient == null) {
            return Optional.of(new Problem("claim.transfer-player-not-found", "{player}", "?"));
        }
        String recipientName = displayName(recipient);
        UUID currentOwner = gp.getClaimOwner(claim);
        if (recipient.getUniqueId().equals(currentOwner)) {
            return Optional.of(new Problem("claim.transfer-self", "{player}", recipientName));
        }
        if (isStaff(sender)) {
            return Optional.empty();
        }

        // The recipient takes on the claim's area and a claim slot, as if they had claimed it.
        int area = gp.getClaimAreaSafe(claim);
        int remaining = gp.getPlayerClaimStats(recipient.getUniqueId()).map(stats -> stats.remaining).orElse(Integer.MAX_VALUE);
        if (area > remaining) {
            return Optional.of(new Problem("claim.transfer-needs-blocks",
                "{player}", recipientName,
                "{blocks}", String.valueOf(area - remaining)));
        }
        if (isRecipientAtClaimLimit(recipient)) {
            return Optional.of(new Problem("claim.transfer-at-limit", "{player}", recipientName));
        }

        double fee = fee(sender);
        if (fee > 0) {
            if (!plugin.getEconomyManager().isEconomyAvailable()) {
                return Optional.of(new Problem("claim.transfer-no-economy"));
            }
            if (!plugin.getEconomyManager().hasMoney(sender, fee)) {
                return Optional.of(new Problem("claim.transfer-cannot-afford", "{fee}", formatFee(fee)));
            }
        }
        return Optional.empty();
    }

    /**
     * Charge the fee and hand the claim over. Re-validates first, since the recipient's blocks
     * or the sender's balance may have changed while the confirmation menu was open.
     *
     * @return true when ownership changed
     */
    public boolean transfer(Player sender, Object claim, String claimId, OfflinePlayer recipient) {
        Optional<Problem> problem = checkTransfer(sender, claim, recipient);
        if (problem.isPresent()) {
            send(sender, problem.get());
            return false;
        }

        double fee = fee(sender);
        if (fee > 0 && !plugin.getEconomyManager().withdrawMoney(sender, fee)) {
            plugin.getMessages().send(sender, "claim.transfer-cannot-afford", "{fee}", formatFee(fee));
            return false;
        }

        UUID recipientId = recipient.getUniqueId();
        boolean changed = gp.transferClaimOwner(claim, recipientId) && recipientId.equals(gp.getClaimOwner(claim));
        if (!changed) {
            // An addon cancelled GP's ClaimTransferEvent, or the fork refused the change.
            if (fee > 0) {
                plugin.getEconomyManager().depositMoney(sender, fee);
            }
            plugin.getMessages().send(sender, "claim.transfer-failed", "{id}", claimId);
            return false;
        }
        gp.saveClaim(claim);

        String recipientName = displayName(recipient);
        if (fee > 0) {
            plugin.getMessages().send(sender, "claim.transfer-gui-success-fee",
                "{id}", claimId, "{player}", recipientName, "{fee}", formatFee(fee));
        } else {
            plugin.getMessages().send(sender, "claim.transfer-gui-success", "{id}", claimId, "{player}", recipientName);
        }
        Player online = recipient.getPlayer();
        if (online != null) {
            plugin.getMessages().send(online, "claim.transfer-received-from",
                "{id}", claimId, "{player}", sender.getName());
        }
        plugin.getLogger().info(sender.getName() + " gave claim " + claimId + " to " + recipientName
            + (fee > 0 ? " for " + formatFee(fee) : "") + " via the claim options menu.");
        return true;
    }

    public void send(Player player, Problem problem) {
        plugin.getMessages().send(player, problem.messageKey(), problem.placeholders());
    }

    /**
     * Resolve a typed name to a player who has joined before (or is online), without the
     * blocking Mojang lookup {@code Bukkit.getOfflinePlayer(String)} can make.
     */
    public OfflinePlayer resolveRecipient(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        Player online = Bukkit.getPlayerExact(trimmed);
        if (online != null) return online;
        // Paper keeps a name cache; Spigot does not, so fall back to the known players.
        try {
            Object cached = Bukkit.getServer().getClass().getMethod("getOfflinePlayerIfCached", String.class)
                .invoke(Bukkit.getServer(), trimmed);
            if (cached instanceof OfflinePlayer player) return player;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        for (OfflinePlayer known : Bukkit.getOfflinePlayers()) {
            if (trimmed.equalsIgnoreCase(known.getName())) return known;
        }
        return null;
    }

    public static String displayName(OfflinePlayer player) {
        if (player == null) return "?";
        String name = player.getName();
        return name != null ? name : player.getUniqueId().toString().substring(0, 8);
    }

    private boolean isRecipientAtClaimLimit(OfflinePlayer recipient) {
        Player online = recipient.getPlayer();
        if (online != null) {
            Boolean atLimit = gp.isAtClaimCountLimit(online);
            if (atLimit != null) {
                return atLimit;
            }
        }
        // Offline, their permission-based limit is unknown, so GP's configured default applies.
        Integer limit = gp.getGpConfigInt("config_claims_maxClaimsPerPlayer");
        if (limit == null || limit <= 0) {
            return false;
        }
        long owned = gp.getClaimsFor(recipient.getUniqueId()).stream()
            .filter(claim -> !gp.isSubdivision(claim))
            .count();
        return owned >= limit;
    }
}
