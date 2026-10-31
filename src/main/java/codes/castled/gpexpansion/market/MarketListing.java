package codes.castled.gpexpansion.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import codes.castled.gpexpansion.util.EcoKind;

import java.util.Locale;
import java.util.UUID;

/**
 * A rent, sell or mailbox sign as it appears on the Claim Market.
 *
 * <p>Signs keep their own data in their persistent data container, which can only be read
 * while their chunk is loaded. The market keeps this copy so every listing can be browsed
 * from anywhere; the sign stays the source of truth and is re-read before any purchase.
 */
public final class MarketListing {

    public enum Type {
        RENT, SELL, MAILBOX;

        public static Type fromSignKind(String kind) {
            if (kind == null) return null;
            return switch (kind.toUpperCase(Locale.ROOT)) {
                case "RENT" -> RENT;
                case "SELL", "BUY" -> SELL;
                case "MAILBOX" -> MAILBOX;
                default -> null;
            };
        }
    }

    private final String id;
    private final Type type;
    private final String world;
    private final int x;
    private final int y;
    private final int z;

    private String claimId;
    /** For mailboxes: the claim the mailbox sits in (teleports and names use it). */
    private String parentClaimId;
    private UUID owner;
    private EcoKind ecoKind;
    private String ecoAmount;
    private String perClick;
    private String maxCap;
    /** Display name of the payment item for ITEM listings. */
    private String itemName;
    private long createdAt;
    private boolean hidden;
    private String note;
    private boolean allowTeleport = true;

    public MarketListing(Type type, String world, int x, int y, int z) {
        this.type = type;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.id = key(world, x, y, z);
    }

    /** Listings are keyed by their sign's block position. */
    public static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public static String key(Location location) {
        return location == null || location.getWorld() == null ? null
            : key(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public String id() { return id; }
    public Type type() { return type; }
    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    /** The sign's location, or null while its world isn't loaded. */
    public Location signLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z);
    }

    public String claimId() { return claimId; }
    public void claimId(String claimId) { this.claimId = claimId; }
    public String parentClaimId() { return parentClaimId; }
    public void parentClaimId(String parentClaimId) { this.parentClaimId = parentClaimId; }
    public UUID owner() { return owner; }
    public void owner(UUID owner) { this.owner = owner; }
    public EcoKind ecoKind() { return ecoKind; }
    public void ecoKind(EcoKind ecoKind) { this.ecoKind = ecoKind; }
    public String ecoAmount() { return ecoAmount; }
    public void ecoAmount(String ecoAmount) { this.ecoAmount = ecoAmount; }
    public String perClick() { return perClick; }
    public void perClick(String perClick) { this.perClick = perClick; }
    public String maxCap() { return maxCap; }
    public void maxCap(String maxCap) { this.maxCap = maxCap; }
    public String itemName() { return itemName; }
    public void itemName(String itemName) { this.itemName = itemName; }
    public long createdAt() { return createdAt; }
    public void createdAt(long createdAt) { this.createdAt = createdAt; }
    public boolean hidden() { return hidden; }
    public void hidden(boolean hidden) { this.hidden = hidden; }
    public String note() { return note; }
    public void note(String note) { this.note = note; }
    public boolean allowTeleport() { return allowTeleport; }
    public void allowTeleport(boolean allowTeleport) { this.allowTeleport = allowTeleport; }

    /** The claim used for names, icons and teleports: the parent claim for mailboxes. */
    public String displayClaimId() {
        return type == Type.MAILBOX && parentClaimId != null ? parentClaimId : claimId;
    }

    /** Numeric price for sorting; unparseable amounts sort last. */
    public double numericPrice() {
        if (ecoAmount == null) return Double.MAX_VALUE;
        String raw = ecoAmount.endsWith("L") || ecoAmount.endsWith("l")
            ? ecoAmount.substring(0, ecoAmount.length() - 1) : ecoAmount;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }
}
