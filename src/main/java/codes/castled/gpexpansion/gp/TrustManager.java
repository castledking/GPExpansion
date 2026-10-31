package codes.castled.gpexpansion.gp;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Unified trust helper for rentals and real-protocol mailboxes across main claims and subdivisions.
 * Virtual-protocol mailboxes use {@link #NO_OP} because access is enforced via inventory sessions.
 */
public interface TrustManager {

    boolean giveTrust(Player actor, OfflinePlayer target, Object claim, GPBridge.TrustLevel... levels);

    boolean givePublicContainerTrust(Object claim);

    boolean revokeTrust(OfflinePlayer target, Object claim);

    default boolean revokeTrust(UUID targetId, Object claim) {
        if (targetId == null) return false;
        return revokeTrust(Bukkit.getOfflinePlayer(targetId), claim);
    }

    void save(Object claim);

    /** No-op implementation for virtual-protocol mailboxes. */
    TrustManager NO_OP = new TrustManager() {
        @Override
        public boolean giveTrust(Player actor, OfflinePlayer target, Object claim, GPBridge.TrustLevel... levels) {
            return true;
        }

        @Override
        public boolean givePublicContainerTrust(Object claim) {
            return true;
        }

        @Override
        public boolean revokeTrust(OfflinePlayer target, Object claim) {
            return true;
        }

        @Override
        public void save(Object claim) {
        }
    };

    static TrustManager create(GPBridge gp) {
        return new BridgeTrustManager(gp != null ? gp : new GPBridge());
    }

    static TrustManager forMailboxProtocol(GPBridge gp, boolean realProtocol) {
        return realProtocol ? create(gp) : NO_OP;
    }

    final class BridgeTrustManager implements TrustManager {
        private final GPBridge gp;

        private BridgeTrustManager(GPBridge gp) {
            this.gp = gp;
        }

        @Override
        public boolean giveTrust(Player actor, OfflinePlayer target, Object claim, GPBridge.TrustLevel... levels) {
            if (target == null || claim == null || levels == null || levels.length == 0) return false;
            String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString();
            Player exec = actor != null ? actor : target.getPlayer();
            boolean any = false;
            for (GPBridge.TrustLevel level : levels) {
                if (level == null) continue;
                boolean ok = switch (level) {
                    case BUILD, MANAGE -> gp.trust(exec, targetName, claim);
                    case CONTAINERS -> gp.grantInventoryTrust(exec, targetName, claim);
                    case ACCESS -> gp.grantAccessTrust(exec, targetName, claim);
                };
                if (ok) any = true;
            }
            if (any) {
                gp.saveClaim(claim);
            }
            return any;
        }

        @Override
        public boolean givePublicContainerTrust(Object claim) {
            if (claim == null) return false;
            boolean ok = gp.containerTrustPublic(claim);
            if (ok) gp.saveClaim(claim);
            return ok;
        }

        @Override
        public boolean revokeTrust(OfflinePlayer target, Object claim) {
            if (target == null || claim == null) return false;
            boolean ok = false;
            String name = target.getName();
            if (name != null && !name.isBlank()) {
                ok = gp.untrust(name, claim);
            }
            if (!ok) {
                ok = gp.untrust(target.getUniqueId().toString(), claim);
            }
            if (ok) {
                gp.saveClaim(claim);
            }
            return ok;
        }

        @Override
        public void save(Object claim) {
            if (claim != null) {
                gp.saveClaim(claim);
            }
        }
    }
}
