package codes.castled.gpexpansion.market;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.confirm.ConfirmationService;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.scheduler.TaskHandle;
import codes.castled.gpexpansion.storage.ClaimDataStore;
import codes.castled.gpexpansion.util.ClaimGeometryUtil;
import codes.castled.gpexpansion.util.EcoKind;
import codes.castled.gpexpansion.util.Mini;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates Claim Market listings, sign synchronization, price/settings editing,
 * GUI purchases/rentals, remote mailbox gifts & written letters, and listing protections.
 */
public final class MarketManager implements Listener {

    public static final String PERM_MARKET = "griefprevention.claim.market";
    public static final String PERM_BUY = "griefprevention.claim.market.buy";
    public static final String PERM_TELEPORT = "griefprevention.claim.market.teleport";
    public static final String PERM_EDIT = "griefprevention.claim.market.edit";
    public static final String PERM_MAIL_DEPOSIT = "griefprevention.claim.market.mail.deposit";
    public static final String PERM_MAIL_LETTER = "griefprevention.claim.market.mail.letter";
    public static final String PERM_MAIL_OPEN_OWN = "griefprevention.claim.market.mail.open-own";
    public static final String PERM_ADMIN = "griefprevention.claim.market.admin";
    public static final String PERM_LISTED = "griefprevention.claim.listed";

    private static final Pattern SHORTHAND_AMOUNT =
        Pattern.compile("^(\\d+(?:\\.\\d+)?)([kmb])$", Pattern.CASE_INSENSITIVE);
    private static final BigDecimal MAX_ECO_AMOUNT = new BigDecimal("2000000000");

    public record MailboxEntry(
        String mailboxId,
        String parentClaimId,
        String displayName,
        UUID ownerId,
        String ownerName,
        UUID parentOwnerId,
        String worldName,
        int x,
        int y,
        int z,
        boolean acceptsMail,
        boolean soldMailbox
    ) {}

    private record PendingMarketContext(String listingId, String displayClaimId, boolean allowTeleport, long expiresAt) {}

    private final GPExpansionPlugin plugin;
    private final MarketStore store;
    private final GPBridge gp;
    private final Map<UUID, PendingMarketContext> pendingGuiTransactions = new ConcurrentHashMap<>();
    private TaskHandle autoSaveTask;

    public MarketManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.store = new MarketStore(plugin);
        this.gp = new GPBridge();
        this.store.load();
    }

    public void start() {
        autoSaveTask = SchedulerAdapter.runRepeatingGlobal(plugin, store::saveIfDirty, 20L * 120L, 20L * 120L);
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        store.saveIfDirty();
    }

    public void reload() {
        store.saveIfDirty();
        store.load();
    }

    public MarketStore store() {
        return store;
    }

    // =========================================================================
    // Sign Synchronization
    // =========================================================================

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }

    /** True when a mailbox sign's PDC still carries a positive purchase price (not yet bought, not self-mailbox). */
    public boolean isForSaleMailboxPdc(PersistentDataContainer pdc) {
        if (pdc == null) return false;
        String kind = pdc.get(key("sign.kind"), PersistentDataType.STRING);
        if (!"MAILBOX".equalsIgnoreCase(kind)) return false;
        String amt = pdc.get(key("sign.ecoAmt"), PersistentDataType.STRING);
        if (amt == null || amt.isBlank() || "0".equals(amt.trim())) return false;
        try {
            String raw = amt.endsWith("L") || amt.endsWith("l") ? amt.substring(0, amt.length() - 1) : amt;
            return Double.parseDouble(raw) > 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** True when a mailbox ID in ClaimDataStore still has an unpurchased for-sale sign attached. */
    public boolean isMailboxStillForSale(String mailboxId) {
        if (mailboxId == null) return false;
        for (MarketListing listing : store.listings()) {
            if (listing.type() == MarketListing.Type.MAILBOX && mailboxId.equals(listing.claimId())) {
                return true;
            }
        }
        Location signLoc = plugin.getClaimDataStore().getMailboxSignLocation(mailboxId).orElse(null);
        if (signLoc != null && signLoc.getWorld() != null && signLoc.getWorld().isChunkLoaded(signLoc.getBlockX() >> 4, signLoc.getBlockZ() >> 4)) {
            if (signLoc.getBlock().getState() instanceof Sign sign) {
                return isForSaleMailboxPdc(sign.getPersistentDataContainer());
            }
        }
        return false;
    }

    /**
     * Synchronize a managed sign into {@link MarketStore}. Must be called on the sign's region thread.
     */
    @SuppressWarnings("deprecation")
    public MarketListing syncSign(Sign sign) {
        if (sign == null || sign.getWorld() == null) return null;
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        String kindRaw = pdc.get(key("sign.kind"), PersistentDataType.STRING);
        MarketListing.Type type = MarketListing.Type.fromSignKind(kindRaw);
        Location loc = sign.getLocation();
        String listingKey = MarketListing.key(loc);
        if (type == null) {
            store.remove(listingKey);
            return null;
        }

        if (type == MarketListing.Type.MAILBOX && !isForSaleMailboxPdc(pdc)) {
            // Self-mailbox or already-purchased mailbox: not a for-sale market listing
            store.remove(listingKey);
            return null;
        }

        String claimId = pdc.get(key("sign.claimId"), PersistentDataType.STRING);
        if (claimId == null || claimId.isBlank()) {
            store.remove(listingKey);
            return null;
        }

        MarketListing existing = store.get(listingKey);
        MarketListing listing = existing != null && existing.type() == type
            ? existing
            : new MarketListing(type, loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        boolean isNew = existing == null;
        listing.claimId(claimId);

        String parentId = pdc.get(key("sign.mailbox.parent"), PersistentDataType.STRING);
        if (type == MarketListing.Type.MAILBOX) {
            if (parentId == null || parentId.isBlank()) {
                parentId = resolveParentClaimId(claimId, loc);
            }
            listing.parentClaimId(parentId != null ? parentId : claimId);
        } else {
            listing.parentClaimId(null);
        }

        UUID ownerId = parseUuid(pdc.get(key("sign.creator"), PersistentDataType.STRING));
        if (ownerId == null) {
            String ownerLookupId = listing.displayClaimId();
            ownerId = gp.findClaimById(ownerLookupId).map(gp::getClaimOwner).orElse(null);
        }
        listing.owner(ownerId);

        String ecoKindRaw = pdc.get(key("sign.ecoKind"), PersistentDataType.STRING);
        EcoKind ecoKind = EcoKind.MONEY;
        if (ecoKindRaw != null) {
            try {
                ecoKind = EcoKind.valueOf(ecoKindRaw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }
        listing.ecoKind(ecoKind);
        listing.ecoAmount(pdc.get(key("sign.ecoAmt"), PersistentDataType.STRING));
        listing.perClick(pdc.get(key("sign.perClick"), PersistentDataType.STRING));
        listing.maxCap(pdc.get(key("sign.maxCap"), PersistentDataType.STRING));

        if (ecoKind == EcoKind.ITEM) {
            String b64 = pdc.get(key("item-b64"), PersistentDataType.STRING);
            String decodedName = decodeItemDisplayName(b64);
            if (decodedName != null) listing.itemName(decodedName);
        } else {
            listing.itemName(null);
        }

        if (isNew || listing.createdAt() <= 0L) {
            listing.createdAt(System.currentTimeMillis());
        }
        if (isNew) {
            listing.hidden(!plugin.getConfigManager().isMarketAutoListSigns());
            listing.allowTeleport(plugin.getConfigManager().isMarketDefaultAllowTeleport());
        }

        store.put(listing);
        return listing;
    }

    public void syncSignBlock(Block block) {
        if (block == null) return;
        if (block.getState() instanceof Sign sign) {
            syncSign(sign);
        }
    }

    public void removeListingAt(Location location) {
        if (location == null) return;
        String id = MarketListing.key(location);
        if (id != null) {
            store.remove(id);
        }
    }

    public void relocateListing(Location source, Location destination) {
        if (source == null || destination == null || destination.getWorld() == null) return;
        MarketListing old = store.remove(MarketListing.key(source));
        if (old == null) {
            if (destination.getBlock().getState() instanceof Sign destSign) {
                syncSign(destSign);
            }
            return;
        }
        MarketListing moved = new MarketListing(
            old.type(),
            destination.getWorld().getName(),
            destination.getBlockX(),
            destination.getBlockY(),
            destination.getBlockZ()
        );
        moved.claimId(old.claimId());
        moved.parentClaimId(old.parentClaimId());
        moved.owner(old.owner());
        moved.ecoKind(old.ecoKind());
        moved.ecoAmount(old.ecoAmount());
        moved.perClick(old.perClick());
        moved.maxCap(old.maxCap());
        moved.itemName(old.itemName());
        moved.createdAt(old.createdAt());
        moved.hidden(old.hidden());
        moved.note(old.note());
        moved.allowTeleport(old.allowTeleport());
        store.put(moved);
    }

    public String resolveParentClaimId(String claimId, Location fallbackLoc) {
        if (claimId != null && !claimId.startsWith("v:")) {
            Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (claimOpt.isPresent()) {
                Object claim = claimOpt.get();
                Optional<Object> parent = gp.getParentClaim(claim);
                if (parent.isPresent() && parent.get() != claim) {
                    return gp.getClaimId(parent.get()).orElse(claimId);
                }
                if (!gp.isSubdivision(claim)) {
                    return claimId;
                }
            }
        }
        Location checkLoc = fallbackLoc;
        if (checkLoc == null && claimId != null) {
            checkLoc = plugin.getClaimDataStore().getMailboxContainerLocation(claimId)
                .or(() -> plugin.getClaimDataStore().getMailboxSignLocation(claimId))
                .orElse(null);
        }
        if (checkLoc != null && checkLoc.getWorld() != null) {
            Optional<Object> at = gp.getClaimAt(checkLoc);
            if (at.isPresent()) {
                Object top = at.get();
                while (true) {
                    Optional<Object> parent = gp.getParentClaim(top);
                    if (parent.isEmpty() || parent.get() == top) break;
                    top = parent.get();
                }
                return gp.getClaimId(top).orElse(null);
            }
        }
        return claimId;
    }

    // =========================================================================
    // Formatting & Price Editing
    // =========================================================================

    public String formatListingPrice(MarketListing listing) {
        if (listing == null) return "Free";
        String base = formatEcoAmount(listing.ecoKind(), listing.ecoAmount(), listing.itemName());
        if (listing.type() == MarketListing.Type.RENT && listing.perClick() != null && !listing.perClick().isBlank()) {
            return base + " / " + listing.perClick();
        }
        return base;
    }

    public String formatEcoAmount(EcoKind kind, String ecoAmt, String itemName) {
        if (ecoAmt == null || ecoAmt.isBlank()) return "N/A";
        EcoKind k = kind != null ? kind : EcoKind.MONEY;
        return switch (k) {
            case MONEY -> {
                try {
                    double v = Double.parseDouble(ecoAmt);
                    yield plugin.getEconomyManager().formatMoneyForSign(v);
                } catch (NumberFormatException e) {
                    yield "$" + ecoAmt;
                }
            }
            case EXPERIENCE -> {
                boolean levels = ecoAmt.endsWith("L") || ecoAmt.endsWith("l");
                yield levels ? ecoAmt.substring(0, ecoAmt.length() - 1) + " Levels" : ecoAmt + " XP";
            }
            case CLAIMBLOCKS -> ecoAmt + " blocks";
            case ITEM -> ecoAmt + " " + (itemName != null && !itemName.isBlank() ? itemName : "Items");
        };
    }

    /**
     * Validate and expand a price input (supports shorthand 1k / 2.5m / 1b and trailing L for XP levels).
     */
    public String normalizePriceInput(String rawInput, EcoKind kind) {
        if (rawInput == null) throw new IllegalArgumentException("Price cannot be empty.");
        String cleaned = rawInput.trim().replace(",", "");
        String symbols = codes.castled.gpexpansion.economy.EconomyManager.getCurrencySymbolsForParsing();
        while (!cleaned.isEmpty() && symbols.indexOf(cleaned.charAt(0)) >= 0) {
            cleaned = cleaned.substring(1).trim();
        }
        while (!cleaned.isEmpty() && symbols.indexOf(cleaned.charAt(cleaned.length() - 1)) >= 0) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (cleaned.isEmpty()) throw new IllegalArgumentException("Price cannot be empty.");

        boolean levels = (cleaned.endsWith("L") || cleaned.endsWith("l")) && kind == EcoKind.EXPERIENCE;
        String numeric = levels ? cleaned.substring(0, cleaned.length() - 1).trim() : cleaned;

        BigDecimal value;
        Matcher shorthand = SHORTHAND_AMOUNT.matcher(numeric);
        if (shorthand.matches()) {
            BigDecimal mult = switch (Character.toLowerCase(shorthand.group(2).charAt(0))) {
                case 'k' -> new BigDecimal("1000");
                case 'm' -> new BigDecimal("1000000");
                default -> new BigDecimal("1000000000");
            };
            value = new BigDecimal(shorthand.group(1)).multiply(mult);
        } else if (numeric.matches("^\\d+(\\.\\d+)?$")) {
            value = new BigDecimal(numeric);
        } else {
            throw new IllegalArgumentException("Invalid amount. Use digits or shorthand like 500, 2.5k, 1m.");
        }

        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be greater than zero.");
        }
        if (value.compareTo(MAX_ECO_AMOUNT) > 0) {
            throw new IllegalArgumentException("Price cannot exceed 2b.");
        }

        String formatted;
        if (kind == EcoKind.MONEY && value.stripTrailingZeros().scale() > 0) {
            formatted = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        } else {
            formatted = value.setScale(0, RoundingMode.DOWN).toPlainString();
        }
        return levels ? formatted + "L" : formatted;
    }

    public boolean canManageListing(Player player, MarketListing listing) {
        if (player == null || listing == null) return false;
        if (player.hasPermission(PERM_ADMIN) || player.hasPermission("griefprevention.admin") || player.hasPermission("griefprevention.sign.admin")) {
            return true;
        }
        if (!player.hasPermission(PERM_EDIT)) {
            return false;
        }
        if (listing.owner() != null && listing.owner().equals(player.getUniqueId())) {
            return true;
        }
        String claimId = listing.displayClaimId();
        return claimId != null && gp.findClaimById(claimId).map(c -> gp.isOwner(c, player.getUniqueId())).orElse(false);
    }

    /**
     * Edit a listing's price from the GUI, updating both {@link MarketStore} and the physical sign PDC/text.
     */
    public void updateListingPrice(Player actor, MarketListing listing, String rawInput, Runnable onComplete) {
        if (!canManageListing(actor, listing)) {
            plugin.getMessages().send(actor, "general.no-permission");
            if (onComplete != null) onComplete.run();
            return;
        }

        String normalized;
        try {
            normalized = normalizePriceInput(rawInput, listing.ecoKind() != null ? listing.ecoKind() : EcoKind.MONEY);
        } catch (IllegalArgumentException ex) {
            plugin.getMessages().send(actor, "market.invalid-price", "{reason}", ex.getMessage());
            if (onComplete != null) onComplete.run();
            return;
        }

        Location signLoc = listing.signLocation();
        if (signLoc == null || signLoc.getWorld() == null) {
            plugin.getMessages().send(actor, "claim.world-unknown", "{world}", listing.world());
            if (onComplete != null) onComplete.run();
            return;
        }

        SchedulerAdapter.runAtLocation(plugin, signLoc, () -> {
            Block block = signLoc.getBlock();
            if (!(block.getState() instanceof Sign sign)) {
                store.remove(listing.id());
                SchedulerAdapter.runEntity(plugin, actor, () -> {
                    plugin.getMessages().send(actor, "market.listing-gone");
                    if (onComplete != null) onComplete.run();
                }, null);
                return;
            }

            PersistentDataContainer pdc = sign.getPersistentDataContainer();
            String kindStr = pdc.get(key("sign.kind"), PersistentDataType.STRING);
            if (MarketListing.Type.fromSignKind(kindStr) != listing.type()) {
                store.remove(listing.id());
                SchedulerAdapter.runEntity(plugin, actor, () -> {
                    plugin.getMessages().send(actor, "market.listing-gone");
                    if (onComplete != null) onComplete.run();
                }, null);
                return;
            }

            pdc.set(key("sign.ecoAmt"), PersistentDataType.STRING, normalized);
            listing.ecoAmount(normalized);
            String amountDisplay = formatEcoAmount(listing.ecoKind(), normalized, listing.itemName());
            SignSide front = sign.getSide(Side.FRONT);
            LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

            if (listing.type() == MarketListing.Type.RENT) {
                String perClick = pdc.get(key("sign.perClick"), PersistentDataType.STRING);
                if (perClick == null) perClick = listing.perClick() != null ? listing.perClick() : "1d";
                Mini.signLine(front, 2, legacy.deserialize("§0" + amountDisplay + "/" + perClick));
            } else if (listing.type() == MarketListing.Type.SELL) {
                Mini.signLine(front, 2, legacy.deserialize("§0" + amountDisplay));
            } else if (listing.type() == MarketListing.Type.MAILBOX) {
                // Instant buyable mailbox puts price on line 1; 3-line sign puts ID on line 1 and price on line 2
                String parentPdc = pdc.get(key("sign.mailbox.parent"), PersistentDataType.STRING);
                if (parentPdc != null && !parentPdc.isBlank()) {
                    Mini.signLine(front, 1, legacy.deserialize("§a" + amountDisplay));
                } else {
                    Mini.signLine(front, 2, legacy.deserialize("§0" + amountDisplay));
                }
            }
            sign.update(true);
            store.put(listing);
            store.saveIfDirty();

            String displayPrice = formatListingPrice(listing);
            SchedulerAdapter.runEntity(plugin, actor, () -> {
                plugin.getMessages().send(actor, "market.price-updated",
                    "{id}", listing.displayClaimId() != null ? listing.displayClaimId() : "?",
                    "{price}", displayPrice);
                if (onComplete != null) onComplete.run();
            }, null);
        });
    }

    // =========================================================================
    // Market Browsing, Purchase/Rent & Teleport
    // =========================================================================

    public boolean isListingCurrentlyRented(MarketListing listing) {
        if (listing == null || listing.type() != MarketListing.Type.RENT || listing.claimId() == null) return false;
        ClaimDataStore.RentalData rental = plugin.getClaimDataStore().getRental(listing.claimId()).orElse(null);
        return rental != null && rental.expiry > System.currentTimeMillis();
    }

    public UUID getListingActiveRenter(MarketListing listing) {
        if (listing == null || listing.type() != MarketListing.Type.RENT || listing.claimId() == null) return null;
        ClaimDataStore.RentalData rental = plugin.getClaimDataStore().getRental(listing.claimId()).orElse(null);
        if (rental != null && rental.expiry > System.currentTimeMillis()) {
            return rental.renter;
        }
        return null;
    }

    public boolean canTeleportToListing(Player player, MarketListing listing) {
        if (player == null || listing == null) return false;
        if (player.hasPermission(PERM_ADMIN) || player.hasPermission("griefprevention.admin")) return true;
        if (!plugin.getConfigManager().isMarketTeleportAllowed()) return false;
        if (!listing.allowTeleport()) return false;
        return player.hasPermission(PERM_TELEPORT) || player.hasPermission("griefprevention.claim.teleport");
    }

    public void teleportToListing(Player player, MarketListing listing) {
        if (!canTeleportToListing(player, listing)) {
            plugin.getMessages().send(player, "market.teleport-not-allowed");
            return;
        }
        String claimId = listing.displayClaimId();
        if (claimId == null || isBannedFromClaim(player.getUniqueId(), claimId)) {
            plugin.getMessages().send(player, "market.banned-from-claim");
            return;
        }
        player.closeInventory();
        plugin.getClaimCommand().teleportToClaim(player, claimId);
    }

    /**
     * Click a listing in {@link codes.castled.gpexpansion.gui.ClaimMarketGUI} to rent or buy it.
     */
    public void initiateGuiTransaction(Player buyer, MarketListing listing) {
        if (!plugin.getConfigManager().isMarketEnabled()) {
            plugin.getMessages().send(buyer, "market.disabled");
            return;
        }
        if (!plugin.getConfigManager().isMarketGuiPurchaseAllowed()) {
            buyer.closeInventory();
            sendTravelInstructions(buyer, listing, "market.gui-purchase-disabled");
            return;
        }
        if (!buyer.hasPermission(PERM_BUY)) {
            plugin.getMessages().send(buyer, "general.no-permission");
            return;
        }

        String signPerm = switch (listing.type()) {
            case RENT -> "griefprevention.sign.use.rent";
            case SELL -> "griefprevention.sign.use.buy";
            case MAILBOX -> "griefprevention.sign.use.mailbox";
        };
        if (!buyer.hasPermission(signPerm) && !(listing.type() == MarketListing.Type.SELL && buyer.hasPermission("griefprevention.sign.use.sell"))) {
            plugin.getMessages().send(buyer, "permissions.use-sign-denied", "{signtype}", listing.type().name().toLowerCase(Locale.ROOT));
            return;
        }

        String targetClaimId = listing.displayClaimId();
        if (targetClaimId != null && isBannedFromClaim(buyer.getUniqueId(), targetClaimId)) {
            plugin.getMessages().send(buyer, "market.banned-from-claim");
            return;
        }

        Location signLoc = listing.signLocation();
        if (signLoc == null || signLoc.getWorld() == null) {
            plugin.getMessages().send(buyer, "claim.world-unknown", "{world}", listing.world());
            return;
        }

        buyer.closeInventory();

        // Re-verify the physical sign on its region thread before prompting payment
        SchedulerAdapter.runAtLocation(plugin, signLoc, () -> {
            Block block = signLoc.getBlock();
            if (!(block.getState() instanceof Sign sign)) {
                store.remove(listing.id());
                SchedulerAdapter.runEntity(plugin, buyer, () -> plugin.getMessages().send(buyer, "market.listing-gone"), null);
                return;
            }

            MarketListing synced = syncSign(sign);
            if (synced == null) {
                SchedulerAdapter.runEntity(plugin, buyer, () -> plugin.getMessages().send(buyer, "market.listing-gone"), null);
                return;
            }

            if (synced.owner() != null && synced.owner().equals(buyer.getUniqueId())) {
                SchedulerAdapter.runEntity(plugin, buyer, () -> plugin.getMessages().send(buyer, "market.cannot-buy-own"), null);
                return;
            }

            if (synced.type() == MarketListing.Type.RENT) {
                UUID activeRenter = getListingActiveRenter(synced);
                if (activeRenter != null && !activeRenter.equals(buyer.getUniqueId())) {
                    SchedulerAdapter.runEntity(plugin, buyer, () -> plugin.getMessages().send(buyer, "market.already-rented"), null);
                    return;
                }
            }

            String claimId = synced.claimId();
            EcoKind ecoKind = synced.ecoKind() != null ? synced.ecoKind() : EcoKind.MONEY;
            String ecoAmt = synced.ecoAmount();
            String perClick = synced.perClick() != null ? synced.perClick() : "1";
            String maxCap = synced.maxCap() != null ? synced.maxCap() : "1";
            String formatted = formatEcoAmount(ecoKind, ecoAmt, synced.itemName());

            pendingGuiTransactions.put(buyer.getUniqueId(), new PendingMarketContext(
                synced.id(),
                synced.displayClaimId(),
                synced.allowTeleport(),
                System.currentTimeMillis() + 120_000L
            ));

            ConfirmationService.Action action = synced.type() == MarketListing.Type.RENT
                ? ConfirmationService.Action.RENT
                : ConfirmationService.Action.BUY;

            plugin.getConfirmationService().prompt(
                buyer,
                action,
                claimId,
                formatted,
                ecoKind.name(),
                ecoAmt,
                perClick,
                maxCap,
                signLoc
            );
        });
    }

    /**
     * Called when a claim or mailbox purchase finishes (via sign or Market GUI).
     */
    public void onPurchaseCompleted(Player buyer, String claimId, String formattedPrice, Location signLoc) {
        if (signLoc != null) {
            removeListingAt(signLoc);
        }
        if (buyer != null && claimId != null) {
            store.recordPurchase(buyer.getUniqueId(), claimId, formattedPrice);
            store.saveIfDirty();
            handlePostGuiNavigation(buyer, claimId, signLoc, !claimId.startsWith("v:"));
        }
    }

    /**
     * Called when a rental starts or renews (via sign or Market GUI).
     */
    public void onRentCompleted(Player renter, String claimId, Location signLoc) {
        if (renter != null && claimId != null) {
            handlePostGuiNavigation(renter, claimId, signLoc, true);
        }
    }

    private void handlePostGuiNavigation(Player player, String claimId, Location signLoc, boolean allowClaimTp) {
        PendingMarketContext ctx = pendingGuiTransactions.remove(player.getUniqueId());
        if (ctx == null || System.currentTimeMillis() > ctx.expiresAt()) {
            return;
        }
        String targetClaimId = ctx.displayClaimId() != null ? ctx.displayClaimId() : claimId;
        boolean canTp = allowClaimTp
            && plugin.getConfigManager().isMarketTeleportAfterPurchase()
            && plugin.getConfigManager().isMarketTeleportAllowed()
            && ctx.allowTeleport()
            && (player.hasPermission("griefprevention.claim.teleport") || player.hasPermission(PERM_TELEPORT));

        if (canTp && targetClaimId != null && !targetClaimId.startsWith("v:")) {
            plugin.getClaimCommand().teleportToClaim(player, targetClaimId);
        } else if (signLoc != null && signLoc.getWorld() != null) {
            plugin.getMessages().send(player, "market.travel-instructions",
                "{world}", signLoc.getWorld().getName(),
                "{x}", String.valueOf(signLoc.getBlockX()),
                "{y}", String.valueOf(signLoc.getBlockY()),
                "{z}", String.valueOf(signLoc.getBlockZ()));
        }
    }

    private void sendTravelInstructions(Player player, MarketListing listing, String headerKey) {
        if (headerKey != null) {
            plugin.getMessages().send(player, headerKey);
        }
        plugin.getMessages().send(player, "market.travel-instructions",
            "{world}", listing.world(),
            "{x}", String.valueOf(listing.x()),
            "{y}", String.valueOf(listing.y()),
            "{z}", String.valueOf(listing.z()));
    }

    // =========================================================================
    // Remote Mailbox Deposits, Written Letters & Unread Join Notices
    // =========================================================================

    public boolean acceptsRemoteMail(String mailboxId) {
        Boolean stored = store.acceptsMail(mailboxId);
        return stored != null ? stored : plugin.getConfigManager().isMarketMailDefaultAccepts();
    }

    public boolean toggleAcceptsRemoteMail(Player actor, String mailboxId) {
        UUID owner = plugin.getClaimDataStore().getMailboxOwner(mailboxId).orElse(null);
        if (owner == null || (!owner.equals(actor.getUniqueId()) && !actor.hasPermission(PERM_ADMIN))) {
            plugin.getMessages().send(actor, "general.no-permission");
            return acceptsRemoteMail(mailboxId);
        }
        boolean next = !acceptsRemoteMail(mailboxId);
        store.setAcceptsMail(mailboxId, next);
        store.saveIfDirty();
        plugin.getMessages().send(actor, next ? "market.mail-accepting-on" : "market.mail-accepting-off",
            "{id}", formatMailboxLabel(mailboxId));
        return next;
    }

    /**
     * Collect all owned (sold & self-created) mailboxes for the Market's "Send Gift / Letter" filter
     * or a specific owner's My Claims "Mailboxes" filter.
     */
    public List<MailboxEntry> collectOwnedMailboxes(Player viewer, boolean onlyAcceptingOrOwned, UUID filterOwner) {
        List<MailboxEntry> result = new ArrayList<>();
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        for (Map.Entry<String, UUID> entry : dataStore.getAllMailboxes().entrySet()) {
            String mailboxId = entry.getKey();
            UUID ownerId = entry.getValue();
            if (mailboxId == null || ownerId == null) continue;
            if (filterOwner != null && !filterOwner.equals(ownerId)) continue;

            // Skip mailboxes that are still sitting unpurchased for sale
            if (isMailboxStillForSale(mailboxId)) continue;

            boolean isViewerOwner = viewer != null && ownerId.equals(viewer.getUniqueId());
            boolean accepts = acceptsRemoteMail(mailboxId);
            if (onlyAcceptingOrOwned && !accepts && !isViewerOwner && (viewer == null || !viewer.hasPermission(PERM_ADMIN))) {
                continue;
            }

            Location loc = dataStore.getMailboxContainerLocation(mailboxId)
                .or(() -> dataStore.getMailboxSignLocation(mailboxId))
                .orElse(null);
            String worldName = "world";
            int x = 0, y = 0, z = 0;
            if (loc != null && loc.getWorld() != null) {
                worldName = loc.getWorld().getName();
                x = loc.getBlockX();
                y = loc.getBlockY();
                z = loc.getBlockZ();
            } else if (!mailboxId.startsWith("v:")) {
                Object claim = gp.findClaimById(mailboxId).orElse(null);
                if (claim != null) {
                    worldName = gp.getClaimWorld(claim).orElse("world");
                    GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
                    if (corners != null) {
                        x = corners.x1;
                        y = corners.y1;
                        z = corners.z1;
                    }
                }
            }

            String parentClaimId = resolveParentClaimId(mailboxId, loc);
            if (viewer != null && !isViewerOwner && parentClaimId != null && isBannedFromClaim(viewer.getUniqueId(), parentClaimId)) {
                continue;
            }

            UUID parentOwnerId = parentClaimId != null
                ? gp.findClaimById(parentClaimId).map(gp::getClaimOwner).orElse(null)
                : null;
            boolean soldMailbox = parentOwnerId != null && !parentOwnerId.equals(ownerId);

            OfflinePlayer op = Bukkit.getOfflinePlayer(ownerId);
            String ownerName = op.getName() != null ? op.getName() : ownerId.toString().substring(0, 8);
            String parentName = parentClaimId != null
                ? dataStore.getCustomName(parentClaimId).orElse("Claim #" + parentClaimId)
                : "Claim";
            String displayName = dataStore.getCustomName(mailboxId)
                .orElse(ownerName + "'s Mailbox (" + parentName + ")");

            result.add(new MailboxEntry(
                mailboxId, parentClaimId, displayName, ownerId, ownerName, parentOwnerId,
                worldName, x, y, z, accepts, soldMailbox
            ));
        }
        result.sort(Comparator.comparing((MailboxEntry e) -> e.ownerName().toLowerCase(Locale.ROOT))
            .thenComparing(MailboxEntry::mailboxId));
        return result;
    }

    public void openRemoteDeposit(Player sender, String mailboxId) {
        if (!plugin.getConfigManager().isMarketMailEnabled() || !plugin.getConfigManager().isMarketMailDepositAllowed()) {
            plugin.getMessages().send(sender, "market.mail-disabled");
            return;
        }
        UUID owner = plugin.getClaimDataStore().getMailboxOwner(mailboxId).orElse(null);
        if (owner == null || isMailboxStillForSale(mailboxId)) {
            plugin.getMessages().send(sender, "mailbox.claim-not-found");
            return;
        }
        boolean isOwner = owner.equals(sender.getUniqueId());
        if (isOwner) {
            if (!plugin.getConfigManager().isMarketMailOwnerRemoteOpenAllowed() || !sender.hasPermission(PERM_MAIL_OPEN_OWN)) {
                plugin.getMessages().send(sender, "market.mail-owner-remote-disabled");
                return;
            }
        } else {
            if (!sender.hasPermission(PERM_MAIL_DEPOSIT)) {
                plugin.getMessages().send(sender, "general.no-permission");
                return;
            }
            if (!acceptsRemoteMail(mailboxId)) {
                plugin.getMessages().send(sender, "market.mail-not-accepting");
                return;
            }
            String parentId = resolveParentClaimId(mailboxId, null);
            if (parentId != null && isBannedFromClaim(sender.getUniqueId(), parentId)) {
                plugin.getMessages().send(sender, "market.banned-from-claim");
                return;
            }
        }

        sender.closeInventory();
        plugin.getMailboxListener().openRemoteMailbox(sender, mailboxId, isOwner);
    }

    public void promptAndSendLetter(Player sender, String mailboxId, Runnable reopenGui) {
        if (!plugin.getConfigManager().isMarketMailEnabled() || !plugin.getConfigManager().isMarketMailLettersAllowed()) {
            plugin.getMessages().send(sender, "market.mail-letters-disabled");
            return;
        }
        if (!sender.hasPermission(PERM_MAIL_LETTER)) {
            plugin.getMessages().send(sender, "general.no-permission");
            return;
        }
        UUID owner = plugin.getClaimDataStore().getMailboxOwner(mailboxId).orElse(null);
        if (owner == null || isMailboxStillForSale(mailboxId)) {
            plugin.getMessages().send(sender, "mailbox.claim-not-found");
            return;
        }
        boolean isOwner = owner.equals(sender.getUniqueId());
        if (!isOwner && !acceptsRemoteMail(mailboxId)) {
            plugin.getMessages().send(sender, "market.mail-not-accepting");
            return;
        }
        String parentId = resolveParentClaimId(mailboxId, null);
        if (!isOwner && parentId != null && isBannedFromClaim(sender.getUniqueId(), parentId)) {
            plugin.getMessages().send(sender, "market.banned-from-claim");
            return;
        }

        OfflinePlayer recipient = Bukkit.getOfflinePlayer(owner);
        String recipientName = recipient.getName() != null ? recipient.getName() : "Player";
        sender.closeInventory();

        plugin.getChatPromptManager().begin(
            sender,
            "market.letter-prompt",
            new String[]{"{player}", recipientName},
            text -> {
                int maxLen = Math.max(32, plugin.getConfigManager().getMarketMailLetterMaxLength());
                String body = text.length() > maxLen ? text.substring(0, maxLen) : text;
                ItemStack letter = buildLetterItem(sender.getName(), recipientName, body);
                plugin.getMailboxListener().deliverLetterItem(sender, mailboxId, letter, delivered -> {
                    if (delivered) {
                        plugin.getMessages().send(sender, "market.letter-sent", "{player}", recipientName);
                        notifyMailReceived(mailboxId, sender, true);
                    } else {
                        plugin.getMessages().send(sender, "market.letter-mailbox-full", "{player}", recipientName);
                    }
                    if (reopenGui != null) reopenGui.run();
                });
            },
            reopenGui
        );
    }

    private ItemStack buildLetterItem(String senderName, String recipientName, String message) {
        ItemStack paper = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
            meta.displayName(legacy.deserialize("&e✉ Letter from &f" + senderName)
                .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            String date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
            lore.add(legacy.deserialize("&8To: &7" + recipientName + " &8• &7" + date)
                .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            for (String line : wrapText(message, 38)) {
                lore.add(Component.text(line, NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            paper.setItemMeta(meta);
        }
        return paper;
    }

    private static List<String> wrapText(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    public void notifyMailReceived(String mailboxId, Player sender, boolean letter) {
        UUID ownerId = plugin.getClaimDataStore().getMailboxOwner(mailboxId).orElse(null);
        if (ownerId == null || (sender != null && ownerId.equals(sender.getUniqueId()))) return;
        Player onlineOwner = Bukkit.getPlayer(ownerId);
        String senderName = sender != null ? sender.getName() : "Someone";
        if (onlineOwner != null && onlineOwner.isOnline()) {
            plugin.getMessages().send(onlineOwner,
                letter ? "market.mail-received-letter" : "market.mail-received-gift",
                "{player}", senderName,
                "{id}", formatMailboxLabel(mailboxId));
        } else {
            store.addUnread(ownerId);
            store.saveIfDirty();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int unread = store.takeUnread(player.getUniqueId());
        if (unread > 0) {
            store.saveIfDirty();
            SchedulerAdapter.runLaterEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    plugin.getMessages().send(player, "market.mail-unread-join",
                        "{count}", String.valueOf(unread));
                }
            }, 60L);
        }
    }

    public String formatMailboxLabel(String mailboxId) {
        if (mailboxId == null) return "?";
        if (!mailboxId.startsWith("v:")) return "#" + mailboxId;
        String[] parts = mailboxId.split(":");
        if (parts.length >= 5) {
            return parts[2] + "," + parts[3] + "," + parts[4];
        }
        return mailboxId;
    }

    private boolean isBannedFromClaim(UUID playerId, String claimId) {
        if (playerId == null || claimId == null) return false;
        ClaimDataStore.ClaimData data = plugin.getClaimDataStore().get(claimId);
        return data.bans.publicBanned || data.bans.bannedPlayers.contains(playerId);
    }

    // =========================================================================
    // Listing Removal (/claim removelisting)
    // =========================================================================

    /**
     * Remove a for-sale sign/listing by listing key ({@code world:x:y:z}) or claim/mailbox ID.
     */
    public boolean removeForSaleListing(Player actor, String idOrKey) {
        if (idOrKey == null || idOrKey.isBlank()) {
            plugin.getMessages().send(actor, "market.removelisting-usage");
            return false;
        }
        boolean isAdmin = actor.hasPermission(PERM_ADMIN)
            || actor.hasPermission("griefprevention.admin")
            || actor.hasPermission("griefprevention.sign.admin");

        List<MarketListing> matches = new ArrayList<>();
        MarketListing direct = store.get(idOrKey);
        if (direct != null) {
            matches.add(direct);
        } else {
            for (MarketListing listing : store.listings()) {
                if (idOrKey.equalsIgnoreCase(listing.claimId()) || idOrKey.equalsIgnoreCase(listing.parentClaimId())) {
                    matches.add(listing);
                }
            }
        }

        if (matches.isEmpty()) {
            plugin.getMessages().send(actor, "market.removelisting-not-found", "{id}", idOrKey);
            return false;
        }

        MarketListing target = matches.get(0);
        String ownerCheckClaimId = target.displayClaimId();
        boolean ownsClaim = (target.owner() != null && target.owner().equals(actor.getUniqueId()))
            || (ownerCheckClaimId != null && gp.findClaimById(ownerCheckClaimId)
                .map(c -> gp.isOwner(c, actor.getUniqueId())).orElse(false));
        if (!ownsClaim && !isAdmin) {
            plugin.getMessages().send(actor, "general.no-permission");
            return false;
        }

        if (target.type() == MarketListing.Type.RENT && isListingCurrentlyRented(target)) {
            plugin.getMessages().send(actor, "eviction.active-renter");
            plugin.getMessages().send(actor, "eviction.start-eviction-notice",
                "{command}", "/claim evict " + target.claimId(),
                "{duration}", plugin.getRentalSignManager().getEvictionNoticePeriodDisplay());
            return false;
        }

        store.remove(target.id());
        store.saveIfDirty();

        Location signLoc = target.signLocation();
        if (signLoc != null && signLoc.getWorld() != null) {
            SchedulerAdapter.runAtLocation(plugin, signLoc, () -> {
                Block block = signLoc.getBlock();
                if (block.getState() instanceof Sign sign) {
                    String signKind = sign.getPersistentDataContainer().get(key("sign.kind"), PersistentDataType.STRING);
                    if (signKind != null) {
                        block.setType(Material.AIR);
                    }
                }
                if (target.type() == MarketListing.Type.MAILBOX && target.claimId() != null) {
                    ClaimDataStore ds = plugin.getClaimDataStore();
                    ds.clearMailbox(target.claimId());
                    ds.save();
                    store.forgetMailbox(target.claimId());
                    if (!target.claimId().startsWith("v:")) {
                        gp.findClaimById(target.claimId()).ifPresent(sub -> {
                            if (gp.isSubdivision(sub)) {
                                gp.deleteClaim(sub);
                            }
                        });
                    }
                }
            });
        }

        plugin.getMessages().send(actor, "market.removelisting-success",
            "{id}", target.displayClaimId() != null ? target.displayClaimId() : target.id());
        return true;
    }

    // =========================================================================
    // Abandon/Delete & Untrust Protection (Kyori Clickable Lists)
    // =========================================================================

    public record BlockingListing(
        String claimId,
        String targetId,
        String kindLabel,
        String detail,
        boolean requiresEvict,
        String actionCommand
    ) {}

    /**
     * Collect all active rentals, sold mailboxes, and for-sale listings inside a claim (and its subdivisions).
     */
    public List<BlockingListing> collectBlockingListingsForClaim(Object mainClaim, String mainClaimId) {
        List<BlockingListing> list = new ArrayList<>();
        if (mainClaimId == null) return list;
        ClaimDataStore ds = plugin.getClaimDataStore();
        long now = System.currentTimeMillis();
        boolean bypassEviction = false; // caller checks permission if needed

        // 1) Active rental on the main claim
        ClaimDataStore.RentalData rental = ds.getRental(mainClaimId).orElse(null);
        if (rental != null && rental.expiry > now) {
            ClaimDataStore.EvictionData ev = ds.getEviction(mainClaimId).orElse(null);
            boolean effective = ev != null && now >= ev.effectiveAt;
            if (!effective && !bypassEviction) {
                String renterName = playerName(rental.renter);
                String detail = ev != null
                    ? "renter: " + renterName + " — eviction in " + formatShortDuration(ev.effectiveAt - now)
                    : "renter: " + renterName + " — expires in " + formatShortDuration(rental.expiry - now);
                list.add(new BlockingListing(mainClaimId, mainClaimId, "RENTAL", detail, true, "/claim evict " + mainClaimId));
            }
        }

        // Collect child subdivision IDs
        Set<String> childIds = ConcurrentHashMap.newKeySet();
        if (mainClaim != null) {
            for (Object sub : gp.getSubclaims(mainClaim)) {
                gp.getClaimId(sub).ifPresent(childIds::add);
            }
        }
        UUID mainOwner = mainClaim != null ? gp.getClaimOwner(mainClaim) : null;

        // 2) Sold mailboxes inside this claim (real subdivisions or virtual containers)
        for (Map.Entry<String, UUID> mb : ds.getAllMailboxes().entrySet()) {
            String mbId = mb.getKey();
            UUID buyerId = mb.getValue();
            if (mbId == null || buyerId == null) continue;
            if (isMailboxStillForSale(mbId)) continue;

            boolean inside = childIds.contains(mbId);
            if (!inside && mbId.startsWith("v:")) {
                String resolvedParent = resolveParentClaimId(mbId, null);
                inside = mainClaimId.equals(resolvedParent);
            }
            if (!inside) continue;

            // Sold mailbox = owned by someone other than the parent claim owner
            if (mainOwner != null && mainOwner.equals(buyerId)) continue;

            ClaimDataStore.EvictionData ev = ds.getEviction(mbId).orElse(null);
            boolean effective = ev != null && now >= ev.effectiveAt;
            if (!effective) {
                String buyerName = playerName(buyerId);
                String detail = ev != null
                    ? "mailbox buyer: " + buyerName + " — eviction in " + formatShortDuration(ev.effectiveAt - now)
                    : "mailbox buyer: " + buyerName;
                list.add(new BlockingListing(mainClaimId, mbId, "MAILBOX_SOLD", detail, true, "/claim evict mailbox " + mbId));
            }
        }

        // 3) For-sale mailbox and sell listings targeting this claim
        for (MarketListing listing : store.listings()) {
            if (listing.type() == MarketListing.Type.MAILBOX) {
                boolean matches = mainClaimId.equals(listing.parentClaimId())
                    || mainClaimId.equals(listing.claimId())
                    || (listing.claimId() != null && childIds.contains(listing.claimId()));
                if (matches) {
                    list.add(new BlockingListing(
                        mainClaimId,
                        listing.id(),
                        "MAILBOX_FOR_SALE",
                        "mailbox for sale: " + formatListingPrice(listing),
                        false,
                        "/claim removelisting " + listing.id()
                    ));
                }
            } else if (listing.type() == MarketListing.Type.SELL && mainClaimId.equals(listing.claimId())) {
                list.add(new BlockingListing(
                    mainClaimId,
                    listing.id(),
                    "SELL_FOR_SALE",
                    "claim for sale: " + formatListingPrice(listing),
                    false,
                    "/claim removelisting " + listing.id()
                ));
            }
        }

        return list;
    }

    /**
     * Check whether abandoning/deleting {@code claimId} must be blocked, and send the Kyori clickable list if so.
     *
     * @return true if blocked
     */
    public boolean checkAndBlockAbandon(Player player, String claimId) {
        if (player == null || claimId == null || claimId.isBlank()) return false;
        Optional<Object> claimOpt = gp.findClaimById(claimId);
        Object claim = claimOpt.orElse(null);
        // Resolve to top-level parent if abandoning a top-level claim, or check the specific claim itself
        List<BlockingListing> blockers = collectBlockingListingsForClaim(claim, claimId);
        if (blockers.isEmpty()) return false;
        if (player.hasPermission("griefprevention.eviction.bypass") && blockers.stream().noneMatch(BlockingListing::requiresEvict)) {
            return false;
        }

        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        Mini.send(player, legacy.deserialize(plugin.getMessages().getRaw("market.abandon-blocked-header", "{id}", claimId)));
        Mini.send(player, Component.empty());
        for (BlockingListing b : blockers) {
            Mini.send(player, formatBlockingLine(b));
        }
        Mini.send(player, Component.empty());
        Mini.send(player, legacy.deserialize(plugin.getMessages().getRaw("market.abandon-blocked-footer")));
        return true;
    }

    /**
     * Scope-aware untrust protection covering rentals and sold/for-sale mailboxes across
     * standalone {@code /untrust}, {@code /untrust all}, {@code /claim untrust}, and GUI trust editors.
     *
     * @param explicitClaimId optional claim ID (e.g. from {@code /claim untrust <player> <id>} or GUI); null uses player's standing location
     * @return true if untrust must be blocked (message already sent)
     */
    public boolean checkAndBlockUntrust(Player sender, String targetArg, String explicitClaimId) {
        if (sender == null || targetArg == null || targetArg.isBlank()) return false;
        String trimmed = targetArg.trim();
        boolean untrustAll = trimmed.equalsIgnoreCase("all") || trimmed.equalsIgnoreCase("public");

        UUID targetUuid = null;
        String targetDisplay = trimmed;
        if (!untrustAll) {
            Player exact = Bukkit.getPlayerExact(trimmed);
            if (exact != null) {
                targetUuid = exact.getUniqueId();
                targetDisplay = exact.getName();
            } else {
                try {
                    targetUuid = UUID.fromString(trimmed);
                    OfflinePlayer op = Bukkit.getOfflinePlayer(targetUuid);
                    if (op.getName() != null) targetDisplay = op.getName();
                } catch (IllegalArgumentException ignored) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(trimmed);
                    targetUuid = op.getUniqueId();
                    if (op.getName() != null) targetDisplay = op.getName();
                }
            }
            if (targetUuid == null) return false;
        }

        // Determine scope: single claim vs all claims owned by sender
        boolean singleClaimScope = false;
        List<Map.Entry<String, Object>> claimsToCheck = new ArrayList<>();

        if (!untrustAll && explicitClaimId != null && !explicitClaimId.isBlank()) {
            Optional<Object> explicit = gp.findClaimById(explicitClaimId);
            if (explicit.isPresent()) {
                singleClaimScope = true;
                Object top = topLevelClaim(explicit.get());
                String topId = gp.getClaimId(top).orElse(explicitClaimId);
                claimsToCheck.add(Map.entry(topId, top));
            }
        } else if (!untrustAll) {
            Optional<Object> standing = gp.getClaimAt(sender.getLocation(), sender);
            if (standing.isPresent()) {
                Object top = topLevelClaim(standing.get());
                if (gp.isOwner(top, sender.getUniqueId()) || sender.hasPermission("griefprevention.admin")) {
                    singleClaimScope = true;
                    String topId = gp.getClaimId(top).orElse("?");
                    claimsToCheck.add(Map.entry(topId, top));
                }
            }
        }

        if (claimsToCheck.isEmpty()) {
            // Outside-claim scope (or /untrust all): hits EVERY claim the sender owns!
            singleClaimScope = false;
            Map<String, Object> unique = new LinkedHashMap<>();
            for (Object c : gp.getClaimsFor(sender)) {
                Object top = topLevelClaim(c);
                gp.getClaimId(top).ifPresent(id -> unique.putIfAbsent(id, top));
            }
            claimsToCheck.addAll(unique.entrySet());
        }

        List<BlockingListing> matched = new ArrayList<>();
        ClaimDataStore ds = plugin.getClaimDataStore();
        long now = System.currentTimeMillis();

        for (Map.Entry<String, Object> entry : claimsToCheck) {
            String mainId = entry.getKey();
            Object mainClaim = entry.getValue();

            // Check rental on main claim
            ClaimDataStore.RentalData rental = ds.getRental(mainId).orElse(null);
            if (rental != null && rental.expiry > now) {
                if (untrustAll || rental.renter.equals(targetUuid)) {
                    String renterName = playerName(rental.renter);
                    String detail = "renter: " + renterName + " — expires in " + formatShortDuration(rental.expiry - now);
                    matched.add(new BlockingListing(mainId, mainId, "RENTAL", detail, true, "/claim evict " + mainId));
                }
            }

            // Collect subdivisions of main claim
            Set<String> childIds = ConcurrentHashMap.newKeySet();
            for (Object sub : gp.getSubclaims(mainClaim)) {
                gp.getClaimId(sub).ifPresent(childIds::add);
            }
            UUID mainOwner = gp.getClaimOwner(mainClaim);

            // Check sold mailboxes
            for (Map.Entry<String, UUID> mb : ds.getAllMailboxes().entrySet()) {
                String mbId = mb.getKey();
                UUID buyerId = mb.getValue();
                if (mbId == null || buyerId == null || isMailboxStillForSale(mbId)) continue;
                boolean inside = childIds.contains(mbId)
                    || (mbId.startsWith("v:") && mainId.equals(resolveParentClaimId(mbId, null)));
                if (!inside) continue;
                if (mainOwner != null && mainOwner.equals(buyerId)) continue;

                if (untrustAll || buyerId.equals(targetUuid)) {
                    matched.add(new BlockingListing(
                        mainId,
                        mbId,
                        "MAILBOX_SOLD",
                        "mailbox buyer: " + playerName(buyerId),
                        true,
                        "/claim evict mailbox " + mbId
                    ));
                }
            }

            // If /untrust all, public container trust on for-sale real mailboxes would also be wiped
            if (untrustAll) {
                for (MarketListing listing : store.listings()) {
                    if (listing.type() == MarketListing.Type.MAILBOX) {
                        boolean inside = mainId.equals(listing.parentClaimId())
                            || (listing.claimId() != null && childIds.contains(listing.claimId()));
                        if (inside) {
                            matched.add(new BlockingListing(
                                mainId,
                                listing.id(),
                                "MAILBOX_FOR_SALE",
                                "mailbox for sale: " + formatListingPrice(listing),
                                false,
                                "/claim removelisting " + listing.id()
                            ));
                        }
                    }
                }
            }
        }

        if (matched.isEmpty()) return false;

        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        String headerKey = singleClaimScope ? "market.untrust-blocked-header-single" : "market.untrust-blocked-header-all";
        Mini.send(sender, legacy.deserialize(plugin.getMessages().getRaw(headerKey, "{player}", untrustAll ? "all" : targetDisplay)));
        Mini.send(sender, Component.empty());
        for (BlockingListing b : matched) {
            Mini.send(sender, formatBlockingLine(b));
        }
        Mini.send(sender, Component.empty());
        Mini.send(sender, legacy.deserialize(plugin.getMessages().getRaw("market.untrust-blocked-footer")));
        return true;
    }

    private Component formatBlockingLine(BlockingListing b) {
        Component prefix = Component.text(" Claim #" + b.claimId() + " ", NamedTextColor.GRAY);
        Component button = b.requiresEvict()
            ? Component.text("[EVICT]", NamedTextColor.RED, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Click to run " + b.actionCommand(), NamedTextColor.YELLOW)))
                .clickEvent(ClickEvent.runCommand(b.actionCommand()))
            : Component.text("[CANCEL]", NamedTextColor.GOLD, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Click to remove listing (" + b.actionCommand() + ")", NamedTextColor.YELLOW)))
                .clickEvent(ClickEvent.runCommand(b.actionCommand()));
        Component suffix = Component.text(" — " + b.detail(), NamedTextColor.GRAY);
        return prefix.append(button).append(suffix);
    }

    private Object topLevelClaim(Object claim) {
        Object cur = claim;
        while (cur != null) {
            Optional<Object> parent = gp.getParentClaim(cur);
            if (parent.isEmpty() || parent.get() == cur) break;
            cur = parent.get();
        }
        return cur;
    }

    private static String playerName(UUID id) {
        if (id == null) return "Unknown";
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString().substring(0, 8);
    }

    public static String formatShortDuration(long millis) {
        if (millis <= 0L) return "0m";
        long totalMinutes = millis / 60_000L;
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes % (24L * 60L)) / 60L;
        long mins = totalMinutes % 60L;
        if (days > 0) return hours > 0 ? days + "d " + hours + "h" : days + "d";
        if (hours > 0) return mins > 0 ? hours + "h " + mins + "m" : hours + "h";
        return Math.max(1L, mins) + "m";
    }

    /** Count active listings (rent, sell, for-sale mailbox, sold mailbox) owned by {@code ownerId} for a claim. */
    public List<MarketListing> getListingsForParentOrMainClaim(String mainClaimId) {
        List<MarketListing> out = new ArrayList<>();
        if (mainClaimId == null) return out;
        for (MarketListing l : store.listings()) {
            if (mainClaimId.equals(l.claimId()) || mainClaimId.equals(l.parentClaimId())) {
                out.add(l);
            }
        }
        return out;
    }

    public Collection<MarketListing> allListings() {
        return store.listings();
    }

    // =========================================================================
    // Internal Helpers
    // =========================================================================

    private String decodeItemDisplayName(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] data = Base64.getDecoder().decode(b64);
            try (org.bukkit.util.io.BukkitObjectInputStream ois =
                     new org.bukkit.util.io.BukkitObjectInputStream(new ByteArrayInputStream(data))) {
                Object obj = ois.readObject();
                if (obj instanceof ItemStack stack) {
                    if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
                        Component dn = stack.getItemMeta().displayName();
                        if (dn != null) {
                            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(dn);
                        }
                    }
                    String[] words = stack.getType().name().toLowerCase(Locale.ROOT).split("_");
                    StringBuilder sb = new StringBuilder();
                    for (String w : words) {
                        if (w.isEmpty()) continue;
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
