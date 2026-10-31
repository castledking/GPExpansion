package codes.castled.gpexpansion.market;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.util.EcoKind;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claim Market data in {@code market.yml}: listings, purchase history (for the My Claims
 * "Bought" filter), mailboxes that turned remote deliveries off, and unread-mail notices.
 */
public final class MarketStore {

    /** A claim someone bought from a sell sign, newest first per player. */
    public record Purchase(String claimId, long time, String price) {}

    private static final int MAX_PURCHASES_PER_PLAYER = 100;

    private final GPExpansionPlugin plugin;
    private final File file;
    private final Map<String, MarketListing> listings = new ConcurrentHashMap<>();
    private final Map<UUID, List<Purchase>> purchases = new ConcurrentHashMap<>();
    private final Map<String, Boolean> mailboxAcceptsMail = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> unreadMail = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public MarketStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "market.yml");
    }

    public synchronized void load() {
        listings.clear();
        purchases.clear();
        mailboxAcceptsMail.clear();
        unreadMail.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection section = yaml.getConfigurationSection("listings");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) continue;
                MarketListing.Type type = parseType(entry.getString("type"));
                String world = entry.getString("world");
                if (type == null || world == null) continue;
                MarketListing listing = new MarketListing(type, world,
                    entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
                listing.claimId(entry.getString("claim"));
                listing.parentClaimId(entry.getString("parent"));
                listing.owner(parseUuid(entry.getString("owner")));
                listing.ecoKind(parseKind(entry.getString("eco-kind")));
                listing.ecoAmount(entry.getString("eco-amount"));
                listing.perClick(entry.getString("per-click"));
                listing.maxCap(entry.getString("max-cap"));
                listing.itemName(entry.getString("item"));
                listing.createdAt(entry.getLong("created"));
                listing.hidden(entry.getBoolean("hidden", false));
                listing.note(entry.getString("note"));
                listing.allowTeleport(entry.getBoolean("teleport", true));
                if (listing.claimId() != null) {
                    listings.put(listing.id(), listing);
                }
            }
        }

        ConfigurationSection bought = yaml.getConfigurationSection("purchases");
        if (bought != null) {
            for (String key : bought.getKeys(false)) {
                UUID player = parseUuid(key);
                if (player == null) continue;
                List<Purchase> list = new ArrayList<>();
                for (Map<?, ?> raw : bought.getMapList(key)) {
                    Object claim = raw.get("claim");
                    if (claim == null) continue;
                    Object time = raw.get("time");
                    Object price = raw.get("price");
                    list.add(new Purchase(String.valueOf(claim),
                        time instanceof Number n ? n.longValue() : 0L,
                        price == null ? "" : String.valueOf(price)));
                }
                if (!list.isEmpty()) purchases.put(player, list);
            }
        }

        ConfigurationSection mail = yaml.getConfigurationSection("mail.accepts");
        if (mail != null) {
            for (String key : mail.getKeys(false)) {
                mailboxAcceptsMail.put(decodeKey(key), mail.getBoolean(key));
            }
        }
        ConfigurationSection unread = yaml.getConfigurationSection("mail.unread");
        if (unread != null) {
            for (String key : unread.getKeys(false)) {
                UUID player = parseUuid(key);
                if (player != null && unread.getInt(key) > 0) unreadMail.put(player, unread.getInt(key));
            }
        }
        dirty = false;
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "GPExpansion Claim Market data. Listings mirror rent/sell/mailbox signs and are",
            "kept in sync automatically; edit prices and visibility from /claim market instead."));
        for (MarketListing listing : listings.values()) {
            String base = "listings." + encodeKey(listing.id()) + ".";
            yaml.set(base + "type", listing.type().name());
            yaml.set(base + "world", listing.world());
            yaml.set(base + "x", listing.x());
            yaml.set(base + "y", listing.y());
            yaml.set(base + "z", listing.z());
            yaml.set(base + "claim", listing.claimId());
            yaml.set(base + "parent", listing.parentClaimId());
            yaml.set(base + "owner", listing.owner() != null ? listing.owner().toString() : null);
            yaml.set(base + "eco-kind", listing.ecoKind() != null ? listing.ecoKind().name() : null);
            yaml.set(base + "eco-amount", listing.ecoAmount());
            yaml.set(base + "per-click", listing.perClick());
            yaml.set(base + "max-cap", listing.maxCap());
            yaml.set(base + "item", listing.itemName());
            yaml.set(base + "created", listing.createdAt());
            if (listing.hidden()) yaml.set(base + "hidden", true);
            if (listing.note() != null) yaml.set(base + "note", listing.note());
            if (!listing.allowTeleport()) yaml.set(base + "teleport", false);
        }
        purchases.forEach((player, list) -> {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (Purchase purchase : list) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("claim", purchase.claimId());
                entry.put("time", purchase.time());
                entry.put("price", purchase.price());
                raw.add(entry);
            }
            yaml.set("purchases." + player, raw);
        });
        mailboxAcceptsMail.forEach((mailbox, accepts) -> yaml.set("mail.accepts." + encodeKey(mailbox), accepts));
        unreadMail.forEach((player, count) -> yaml.set("mail.unread." + player, count));
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed saving market.yml: " + e.getMessage());
        }
    }

    /** Save only when something changed since the last save. */
    public void saveIfDirty() {
        if (dirty) save();
    }

    public void markDirty() {
        dirty = true;
    }

    // ================================ listings ================================

    public Collection<MarketListing> listings() {
        return listings.values();
    }

    public MarketListing get(String id) {
        return id == null ? null : listings.get(id);
    }

    public void put(MarketListing listing) {
        listings.put(listing.id(), listing);
        dirty = true;
    }

    public MarketListing remove(String id) {
        MarketListing removed = id == null ? null : listings.remove(id);
        if (removed != null) dirty = true;
        return removed;
    }

    public List<MarketListing> forClaim(String claimId) {
        List<MarketListing> result = new ArrayList<>();
        if (claimId == null) return result;
        for (MarketListing listing : listings.values()) {
            if (claimId.equals(listing.claimId())) result.add(listing);
        }
        return result;
    }

    // ================================ purchases ================================

    public void recordPurchase(UUID buyer, String claimId, String price) {
        List<Purchase> list = new ArrayList<>(purchases.getOrDefault(buyer, List.of()));
        list.removeIf(purchase -> purchase.claimId().equals(claimId));
        list.add(0, new Purchase(claimId, System.currentTimeMillis(), price == null ? "" : price));
        while (list.size() > MAX_PURCHASES_PER_PLAYER) list.remove(list.size() - 1);
        purchases.put(buyer, list);
        dirty = true;
    }

    public List<Purchase> purchases(UUID buyer) {
        return purchases.getOrDefault(buyer, List.of());
    }

    // ================================== mail ==================================

    public Boolean acceptsMail(String mailboxId) {
        return mailboxAcceptsMail.get(mailboxId);
    }

    public void setAcceptsMail(String mailboxId, boolean accepts) {
        mailboxAcceptsMail.put(mailboxId, accepts);
        dirty = true;
    }

    public void forgetMailbox(String mailboxId) {
        if (mailboxAcceptsMail.remove(mailboxId) != null) dirty = true;
    }

    public void addUnread(UUID player) {
        unreadMail.merge(player, 1, Integer::sum);
        dirty = true;
    }

    public int takeUnread(UUID player) {
        Integer count = unreadMail.remove(player);
        if (count != null) dirty = true;
        return count == null ? 0 : count;
    }

    public Set<String> mailSettingKeys() {
        return mailboxAcceptsMail.keySet();
    }

    // ================================= helpers =================================

    /** YAML path segments can't contain dots; virtual mailbox ids and world names may. */
    private static String encodeKey(String key) {
        return key.replace(".", "%2E");
    }

    private static String decodeKey(String key) {
        return key.replace("%2E", ".");
    }

    private static MarketListing.Type parseType(String raw) {
        if (raw == null) return null;
        try {
            return MarketListing.Type.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static EcoKind parseKind(String raw) {
        if (raw == null) return EcoKind.MONEY;
        try {
            return EcoKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EcoKind.MONEY;
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
