package codes.castled.gpexpansion.gui;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.market.MarketListing;
import codes.castled.gpexpansion.market.MarketManager;
import codes.castled.gpexpansion.market.MarketStore;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.storage.ClaimDataStore;
import codes.castled.gpexpansion.util.ClaimCustomizationUtil;
import codes.castled.gpexpansion.util.ClaimGeometryUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Unified "My Claims" GUI covering Owned, Trusted, Rented, Bought, Mailboxes, and Listed claims.
 * Absorbs Trusted Claims via a bottom-right emerald toggle button (slot 52) as well as the Hopper filter.
 */
public class OwnedClaimsGUI extends BaseGUI {

    public enum FilterType {
        OWNED("Owned Claims", Material.DIAMOND),
        TRUSTED("Trusted Claims", Material.EMERALD),
        RENTED("Rented Claims", Material.CLOCK),
        BOUGHT("Bought Claims", Material.GOLD_INGOT),
        MAILBOXES("Mailboxes", Material.ENDER_CHEST),
        LISTED("Listed Claims", Material.OAK_SIGN);

        private final String displayName;
        private final Material icon;

        FilterType(String displayName, Material icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getDisplayName() { return displayName; }
        public Material getIcon() { return icon; }

        public FilterType next() {
            FilterType[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public FilterType previous() {
            FilterType[] values = values();
            return values[(ordinal() - 1 + values.length) % values.length];
        }

        public static FilterType parse(String raw) {
            if (raw == null || raw.isBlank()) return OWNED;
            if ("ALL".equalsIgnoreCase(raw) || "REGULAR".equalsIgnoreCase(raw)) return OWNED;
            if ("SOLD".equalsIgnoreCase(raw)) return LISTED;
            try {
                return FilterType.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return OWNED;
            }
        }
    }

    private final GPBridge gp;
    private FilterType currentFilter = FilterType.OWNED;
    private int currentPage = 0;
    private final List<ClaimInfo> filteredClaims = new ArrayList<>();
    private String searchQuery;
    private String armedCancelRentClaimId = null;

    // Slot positions
    private static final int PREV_PAGE_SLOT = 45;
    private static final int SEARCH_SLOT = 47;
    private static final int BACK_SLOT = 48;
    private static final int FILTER_SLOT = 49;
    private static final int LISTED_MANAGER_SLOT = 51;
    private static final int TRUSTED_TOGGLE_SLOT = 52;
    private static final int NEXT_PAGE_SLOT = 53;

    private static final int[] CLAIM_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    public OwnedClaimsGUI(GUIManager manager, Player player) {
        this(manager, player, null);
    }

    public OwnedClaimsGUI(GUIManager manager, Player player, String searchQuery) {
        super(manager, player, "owned-claims");
        this.gp = new GPBridge();
        this.searchQuery = searchQuery;
    }

    public static void openAsync(GUIManager manager, Player player, String searchQuery) {
        openAsyncWithState(manager, player, searchQuery, null, 0);
    }

    public static void openAsyncWithState(GUIManager manager, Player player, String searchQuery, String filterType, int page) {
        SchedulerAdapter.runAsyncNow(manager.getPlugin(), () -> {
            OwnedClaimsGUI gui = new OwnedClaimsGUI(manager, player, searchQuery);
            gui.currentFilter = FilterType.parse(filterType);
            gui.currentPage = Math.max(0, page);
            gui.loadClaims();
            SchedulerAdapter.runEntity(manager.getPlugin(), player, () -> {
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.OWNED_CLAIMS,
                    gui.searchQuery, gui.currentFilter.name(), gui.currentPage);
                manager.openGUI(player, gui);
            }, null);
        });
    }

    private void reloadAndRefresh() {
        armedCancelRentClaimId = null;
        SchedulerAdapter.runAsyncNow(plugin, () -> {
            loadClaims();
            SchedulerAdapter.runEntity(plugin, player, () -> {
                int maxPage = Math.max(0, (filteredClaims.size() - 1) / CLAIM_SLOTS.length);
                if (currentPage > maxPage) currentPage = maxPage;
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.OWNED_CLAIMS,
                    searchQuery, currentFilter.name(), currentPage);
                if (inventory != null) {
                    populateInventory();
                }
            }, null);
        });
    }

    private void loadClaims() {
        filteredClaims.clear();
        switch (currentFilter) {
            case OWNED -> loadOwnedClaims();
            case TRUSTED -> loadTrustedClaims();
            case RENTED -> loadRentedByMeClaims();
            case BOUGHT -> loadBoughtClaims();
            case MAILBOXES -> loadOwnMailboxes();
            case LISTED -> loadListedClaims();
        }
    }

    private void loadOwnedClaims() {
        ClaimDataStore ds = plugin.getClaimDataStore();
        MarketManager mm = plugin.getMarketManager();
        List<Object> allClaims = gp.getClaimsFor(player);
        for (Object claim : allClaims) {
            if (gp.isSubdivision(claim)) continue;
            String claimId = gp.getClaimId(claim).orElse(null);
            if (claimId == null) continue;

            ClaimInfo info = new ClaimInfo(claim, claimId, claimId);
            info.name = ds.getCustomName(claimId).orElse("Claim #" + claimId);
            info.ownerName = player.getName();
            info.stillOwner = true;
            info.childCount = gp.getSubclaims(claim).size();
            info.area = ClaimGeometryUtil.getClaimArea(claim);
            info.location = ClaimGeometryUtil.getClaimLocation(claim);
            info.isRented = ds.isRented(claimId);
            info.isForSale = mm != null && mm.store().forClaim(claimId).stream()
                .anyMatch(l -> l.type() == MarketListing.Type.SELL);
            info.isMailbox = ds.isMailbox(claimId);

            if (matchesSearch(info)) {
                filteredClaims.add(info);
            }
        }
    }

    private void loadTrustedClaims() {
        ClaimDataStore ds = plugin.getClaimDataStore();
        UUID viewerId = player.getUniqueId();
        for (Object claim : gp.getClaimsWhereTrusted(viewerId)) {
            String claimId = gp.getClaimId(claim).orElse(null);
            if (claimId == null) continue;
            if (gp.isOwner(claim, viewerId)) continue;
            // Skip mailboxes owned by viewer (shown in Mailboxes tab without teleport)
            if (ds.getMailboxOwner(claimId).map(viewerId::equals).orElse(false)) continue;

            String iconId = gp.getParentClaim(claim).flatMap(gp::getClaimId).orElse(claimId);
            ClaimInfo info = new ClaimInfo(claim, claimId, iconId);
            info.name = ds.getCustomName(claimId).orElse("Claim #" + claimId);
            info.ownerName = ClaimGeometryUtil.getOwnerName(claim);
            info.area = ClaimGeometryUtil.getClaimArea(claim);
            info.location = ClaimGeometryUtil.getClaimLocation(claim);
            info.isSubdivision = gp.isSubdivision(claim);
            info.isRented = ds.isRented(claimId);
            info.isMailbox = ds.isMailbox(claimId);
            if (info.isRented) {
                ClaimDataStore.RentalData rental = ds.getRental(claimId).orElse(null);
                info.canRenew = rental != null && viewerId.equals(rental.renter);
            }
            if (matchesSearch(info)) {
                filteredClaims.add(info);
            }
        }
    }

    private void loadRentedByMeClaims() {
        ClaimDataStore ds = plugin.getClaimDataStore();
        UUID viewerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        for (Map.Entry<String, ClaimDataStore.RentalData> entry : ds.getAllRentals().entrySet()) {
            String claimId = entry.getKey();
            ClaimDataStore.RentalData rental = entry.getValue();
            if (claimId == null || rental == null || !viewerId.equals(rental.renter)) continue;
            if (rental.expiry <= now) continue;

            Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (claimOpt.isEmpty()) continue;
            Object claim = claimOpt.get();

            ClaimInfo info = new ClaimInfo(claim, claimId, claimId);
            info.name = ds.getCustomName(claimId).orElse("Claim #" + claimId);
            info.ownerName = ClaimGeometryUtil.getOwnerName(claim);
            info.area = ClaimGeometryUtil.getClaimArea(claim);
            info.location = ClaimGeometryUtil.getClaimLocation(claim);
            info.isRented = true;
            info.canRenew = true;
            info.rentalExpiryMs = rental.expiry - now;

            ClaimDataStore.EvictionData ev = ds.getEviction(claimId).orElse(null);
            if (ev != null) {
                info.evictingRemainingMs = Math.max(0L, ev.effectiveAt - now);
                info.isBeingEvicted = true;
            }

            if (matchesSearch(info)) {
                filteredClaims.add(info);
            }
        }
    }

    private void loadBoughtClaims() {
        MarketManager mm = plugin.getMarketManager();
        if (mm == null) return;
        ClaimDataStore ds = plugin.getClaimDataStore();
        UUID viewerId = player.getUniqueId();

        for (MarketStore.Purchase purchase : mm.store().purchases(viewerId)) {
            String claimId = purchase.claimId();
            if (claimId == null || claimId.startsWith("v:")) continue;
            if (ds.isMailbox(claimId)) continue; // Bought mailboxes belong in the Mailboxes filter

            Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (claimOpt.isEmpty()) continue;
            Object claim = claimOpt.get();
            if (gp.isSubdivision(claim)) continue;

            ClaimInfo info = new ClaimInfo(claim, claimId, claimId);
            info.name = ds.getCustomName(claimId).orElse("Claim #" + claimId);
            info.ownerName = ClaimGeometryUtil.getOwnerName(claim);
            info.stillOwner = gp.isOwner(claim, viewerId);
            info.childCount = gp.getSubclaims(claim).size();
            info.area = ClaimGeometryUtil.getClaimArea(claim);
            info.location = ClaimGeometryUtil.getClaimLocation(claim);
            info.purchasePrice = purchase.price();
            info.purchaseTime = purchase.time();

            if (matchesSearch(info)) {
                filteredClaims.add(info);
            }
        }
    }

    private void loadOwnMailboxes() {
        MarketManager mm = plugin.getMarketManager();
        if (mm == null) return;

        for (MarketManager.MailboxEntry mb : mm.collectOwnedMailboxes(player, false, player.getUniqueId())) {
            ClaimInfo info = new ClaimInfo(null, mb.mailboxId(), mb.parentClaimId());
            info.mailboxEntry = mb;
            info.name = mb.displayName();
            info.ownerName = mb.ownerName();
            info.isMailbox = true;
            info.location = mb.worldName() + " (" + mb.x() + ", " + mb.y() + ", " + mb.z() + ")";

            if (matchesSearch(info)) {
                filteredClaims.add(info);
            }
        }
    }

    private void loadListedClaims() {
        MarketManager mm = plugin.getMarketManager();
        ClaimDataStore ds = plugin.getClaimDataStore();
        if (mm == null) return;
        UUID viewerId = player.getUniqueId();

        Map<String, Object> ownedMain = new HashMap<>();
        Map<String, String> subToParent = new HashMap<>();
        for (Object claim : gp.getClaimsFor(player)) {
            if (gp.isSubdivision(claim)) continue;
            String mainId = gp.getClaimId(claim).orElse(null);
            if (mainId == null) continue;
            ownedMain.put(mainId, claim);
            for (Object sub : gp.getSubclaims(claim)) {
                gp.getClaimId(sub).ifPresent(subId -> subToParent.put(subId, mainId));
            }
        }

        Map<String, Integer> listingCounts = new HashMap<>();
        Set<String> matchedMainIds = new LinkedHashSet<>();

        for (MarketListing listing : mm.store().listings()) {
            if (listing == null || listing.claimId() == null) continue;
            String mainId = listing.displayClaimId() != null ? listing.displayClaimId() : listing.claimId();
            boolean mine = (listing.owner() != null && listing.owner().equals(viewerId)) || ownedMain.containsKey(mainId);
            if (!mine || mainId == null) continue;
            matchedMainIds.add(mainId);
            listingCounts.merge(mainId, 1, Integer::sum);
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<String, ClaimDataStore.RentalData> r : ds.getAllRentals().entrySet()) {
            if (r.getValue() != null && r.getValue().expiry > now && ownedMain.containsKey(r.getKey())) {
                matchedMainIds.add(r.getKey());
                listingCounts.putIfAbsent(r.getKey(), 1);
            }
        }

        for (Map.Entry<String, UUID> mb : ds.getAllMailboxes().entrySet()) {
            String mbId = mb.getKey();
            UUID buyer = mb.getValue();
            if (mbId == null || buyer == null || buyer.equals(viewerId)) continue;
            if (mm.isMailboxStillForSale(mbId)) continue;
            String parentId = subToParent.get(mbId);
            if (parentId == null && mbId.startsWith("v:")) {
                parentId = mm.resolveParentClaimId(mbId, null);
            }
            if (parentId != null && ownedMain.containsKey(parentId)) {
                matchedMainIds.add(parentId);
                listingCounts.merge(parentId, 1, Integer::sum);
            }
        }

        for (String mainId : matchedMainIds) {
            Object claim = ownedMain.get(mainId);
            if (claim == null) {
                claim = gp.findClaimById(mainId).orElse(null);
            }
            if (claim == null) continue;

            ClaimInfo info = new ClaimInfo(claim, mainId, mainId);
            info.name = ds.getCustomName(mainId).orElse("Claim #" + mainId);
            info.ownerName = ClaimGeometryUtil.getOwnerName(claim);
            info.stillOwner = gp.isOwner(claim, viewerId);
            info.childCount = gp.getSubclaims(claim).size();
            info.area = ClaimGeometryUtil.getClaimArea(claim);
            info.location = ClaimGeometryUtil.getClaimLocation(claim);
            info.listingCount = listingCounts.getOrDefault(mainId, 1);
            info.isRented = ds.isRented(mainId);

            if (matchesSearch(info)) {
                filteredClaims.add(info);
            }
        }
    }

    private boolean matchesSearch(ClaimInfo info) {
        if (searchQuery == null || searchQuery.isEmpty()) return true;
        String query = searchQuery.toLowerCase(Locale.ROOT);
        if (info.claimId != null && info.claimId.toLowerCase(Locale.ROOT).contains(query)) return true;
        if (info.name != null && info.name.toLowerCase(Locale.ROOT).contains(query)) return true;
        return info.ownerName != null && info.ownerName.toLowerCase(Locale.ROOT).contains(query);
    }

    @Override
    public Inventory createInventory() {
        String title = searchQuery != null && !searchQuery.isEmpty()
            ? getString("title-search", "&6&lMy Claims - \"{query}\"").replace("{query}", searchQuery)
            : getString("title", "&6&lMy Claims");
        inventory = createBaseInventoryWithTitle(title, 54);
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        inventory.clear();
        fillBorder(createFiller());

        inventory.setItem(FILTER_SLOT, createFilterItem());
        inventory.setItem(BACK_SLOT, createBackItem());
        inventory.setItem(SEARCH_SLOT, createSearchItem());
        inventory.setItem(TRUSTED_TOGGLE_SLOT, createTrustedToggleItem());
        if (player.hasPermission(MarketManager.PERM_LISTED)) {
            inventory.setItem(LISTED_MANAGER_SLOT, createListedManagerItem());
        }

        if (currentPage > 0) {
            inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        }

        int maxPage = Math.max(0, (filteredClaims.size() - 1) / CLAIM_SLOTS.length);
        if (currentPage < maxPage) {
            inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        }

        String currentStandingClaimId = getCurrentStandingClaimId();

        int startIndex = currentPage * CLAIM_SLOTS.length;
        for (int i = 0; i < CLAIM_SLOTS.length && startIndex + i < filteredClaims.size(); i++) {
            ClaimInfo info = filteredClaims.get(startIndex + i);
            inventory.setItem(CLAIM_SLOTS[i], createClaimItem(info, currentStandingClaimId));
        }
    }

    private String getCurrentStandingClaimId() {
        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) return null;

        if (currentFilter == FilterType.TRUSTED) {
            String worldName = location.getWorld().getName();
            String bestId = null;
            long smallest = Long.MAX_VALUE;
            for (ClaimInfo info : filteredClaims) {
                if (info.claim == null || info.claimId == null) continue;
                if (!worldName.equals(gp.getClaimWorld(info.claim).orElse(null))) continue;
                if (!ClaimGeometryUtil.containsLocation(gp, info.claim, location)) continue;
                long size = ClaimGeometryUtil.getClaimSizeMetric(gp, info.claim);
                if (size < smallest) {
                    smallest = size;
                    bestId = info.claimId;
                }
            }
            return bestId;
        }

        Optional<Object> at = gp.getClaimAt(location, player);
        if (at.isEmpty()) return null;
        Object c = at.get();
        while (true) {
            Optional<Object> parent = gp.getParentClaim(c);
            if (parent.isEmpty() || parent.get() == c) break;
            c = parent.get();
        }
        return gp.isSubdivision(c) ? null : gp.getClaimId(c).orElse(null);
    }

    private ItemStack createFilterItem() {
        List<String> lore = new ArrayList<>();
        for (FilterType ft : FilterType.values()) {
            boolean active = ft == currentFilter;
            lore.add((active ? "&a▸ " : "&7  ") + ft.getDisplayName());
        }
        lore.add("");
        lore.add("&eLeft/Right-click to cycle filter");
        return createItem(Material.HOPPER, "&6&lFilter: &e" + currentFilter.getDisplayName(), lore);
    }

    private ItemStack createTrustedToggleItem() {
        if (currentFilter == FilterType.TRUSTED) {
            List<String> lore = List.of(
                "&7Currently viewing Trusted Claims.",
                "",
                "&e▸ Click to switch back to Owned Claims"
            );
            return createItem(Material.DIAMOND, "&b&lView Owned Claims", lore, true);
        }
        List<String> lore = List.of(
            "&7View claims other players have",
            "&7trusted you in.",
            "",
            "&e▸ Click to view Trusted Claims"
        );
        return createItem(Material.EMERALD, "&a&lTrusted Claims", lore);
    }

    private ItemStack createListedManagerItem() {
        List<String> lore = List.of(
            "&7Open the dedicated Listed Claims",
            "&7manager for rentals, sold mailboxes,",
            "&7and for-sale signs.",
            "",
            "&e▸ Click to open Listed Claims"
        );
        return createItem(Material.LECTERN, "&6&lManage Listed Claims", lore);
    }

    private ItemStack createBackItem() {
        if (currentFilter == FilterType.TRUSTED) {
            return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to Owned Claims"));
        }
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to main menu"));
    }

    private ItemStack createSearchItem() {
        List<String> lore = new ArrayList<>();
        if (searchQuery != null && !searchQuery.isEmpty()) {
            lore.add("&7Current search: &e" + searchQuery);
            lore.add("");
        }
        lore.add("&7Search by owner, ID, or name");
        lore.add("");
        lore.add("&a▸ Left-click to search");
        if (searchQuery != null && !searchQuery.isEmpty()) {
            lore.add("&c▸ Right-click to clear search");
        }
        return createItem(Material.COMPASS, "&b&lSearch Claims", lore);
    }

    private ItemStack createPrevPageItem() {
        int total = Math.max(1, (filteredClaims.size() - 1) / CLAIM_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + currentPage + "/" + total));
    }

    private ItemStack createNextPageItem() {
        int total = Math.max(1, (filteredClaims.size() - 1) / CLAIM_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&lNext Page »", List.of("&7Page " + (currentPage + 2) + "/" + total));
    }

    private ItemStack createClaimItem(ClaimInfo info, String currentStandingId) {
        if (currentFilter == FilterType.MAILBOXES && info.mailboxEntry != null) {
            return createOwnMailboxItem(info);
        }

        Material fallback = Material.GRASS_BLOCK;
        if (info.isRented) fallback = Material.CLOCK;
        else if (info.isForSale) fallback = Material.GOLD_BLOCK;
        else if (info.isMailbox) fallback = Material.ENDER_CHEST;
        else if (currentFilter == FilterType.BOUGHT) fallback = Material.GOLD_INGOT;
        else if (currentFilter == FilterType.LISTED) fallback = Material.OAK_SIGN;

        List<String> lore = new ArrayList<>();
        lore.add("&7ID: &f" + info.claimId);
        if (currentFilter != FilterType.OWNED && info.ownerName != null) {
            lore.add("&7Owner: &f" + info.ownerName);
        }
        lore.add("&7Location: &f" + info.location);
        lore.add("&7Area: &f" + info.area + " blocks");
        if (currentFilter == FilterType.OWNED || currentFilter == FilterType.BOUGHT || currentFilter == FilterType.LISTED) {
            lore.add("&7Children: &6" + info.childCount);
        }
        if (info.isSubdivision) {
            lore.add("&7Type: &eSubdivision");
        }

        if (currentFilter == FilterType.RENTED) {
            lore.add("");
            lore.add("&e⚡ Remaining: &f" + formatShortDuration(info.rentalExpiryMs));
            if (info.isBeingEvicted) {
                lore.add("&c⚠ Eviction Notice: &f" + formatShortDuration(info.evictingRemainingMs) + " left");
            }
        } else if (currentFilter == FilterType.BOUGHT) {
            lore.add("");
            if (info.purchasePrice != null && !info.purchasePrice.isBlank()) {
                lore.add("&7Bought For: &a" + info.purchasePrice);
            }
            if (info.purchaseTime > 0L) {
                lore.add("&7Purchased: &f" + new SimpleDateFormat("yyyy-MM-dd").format(new Date(info.purchaseTime)));
            }
        } else if (currentFilter == FilterType.LISTED) {
            lore.add("");
            lore.add("&eActive Listings: &f" + info.listingCount);
        } else {
            if (info.isRented) {
                lore.add("");
                lore.add("&e⚡ Currently Rented");
            } else if (info.isForSale) {
                lore.add("");
                lore.add("&a💰 Listed For Sale");
            } else if (info.isMailbox) {
                lore.add("");
                lore.add("&d📬 Mailbox Claim");
            }
        }

        lore.add("");
        boolean canTp = player.hasPermission("griefprevention.claim.teleport");
        switch (currentFilter) {
            case OWNED -> {
                lore.add("&a▸ Left-click to view options");
                if (player.hasPermission("griefprevention.claim.name")) {
                    lore.add("&b▸ Right-click to rename");
                }
                if (canTp) {
                    lore.add("&e▸ Shift-click to teleport");
                }
            }
            case TRUSTED -> {
                if (canTp) {
                    lore.add("&a▸ Left-click to teleport");
                }
                if (info.canRenew) {
                    lore.add("&b▸ Right-click to renew rental");
                }
            }
            case RENTED -> {
                if (canTp) {
                    lore.add("&a▸ Left-click to teleport");
                }
                lore.add("&b▸ Right-click to renew rental");
                if (player.hasPermission("griefprevention.claim.cancelrent")) {
                    if (info.claimId.equals(armedCancelRentClaimId)) {
                        lore.add("&c&l▸ Shift-click again to confirm cancel rent");
                    } else {
                        lore.add("&c▸ Shift-click to cancel rental");
                    }
                }
            }
            case BOUGHT -> {
                if (canTp) {
                    lore.add("&a▸ Left-click to teleport");
                } else if (info.stillOwner) {
                    lore.add("&a▸ Left-click to view options");
                }
                if (info.stillOwner && canTp) {
                    lore.add("&b▸ Right-click to view options");
                }
            }
            case LISTED -> {
                lore.add("&a▸ Left-click to manage listings");
                lore.add("&b▸ Right-click for claim options");
                if (canTp) {
                    lore.add("&e▸ Shift-click to teleport");
                }
            }
            default -> {}
        }

        boolean isHere = info.claimId != null && info.claimId.equals(currentStandingId);
        if (isHere) {
            lore.add("");
            lore.add("&b✦ You are here");
        }

        String nameColor = currentFilter == FilterType.TRUSTED ? "&a" : "&6";
        boolean glow = isHere || info.claimId.equals(armedCancelRentClaimId);
        return createClaimIconItem(info.iconClaimId, fallback, nameColor + info.name, lore, glow);
    }

    private ItemStack createOwnMailboxItem(ClaimInfo info) {
        MarketManager.MailboxEntry mb = info.mailboxEntry;
        MarketManager mm = plugin.getMarketManager();
        boolean accepts = mm.acceptsRemoteMail(mb.mailboxId());

        List<String> lore = new ArrayList<>();
        if (mb.parentClaimId() != null) {
            lore.add("&7Parent Claim: &f#" + mb.parentClaimId());
        }
        lore.add("&7Mailbox: &f" + mm.formatMailboxLabel(mb.mailboxId()));
        lore.add("&7Location: &f" + info.location);
        lore.add("&7Remote Mail: " + (accepts ? "&aAccepting Gifts & Letters" : "&cClosed to Remote Mail"));
        lore.add("");
        // Explicitly NO teleport action for mailboxes per design
        if (plugin.getConfigManager().isMarketMailEnabled()
                && plugin.getConfigManager().isMarketMailOwnerRemoteOpenAllowed()
                && player.hasPermission(MarketManager.PERM_MAIL_OPEN_OWN)) {
            lore.add("&a▸ Left-click to open mailbox remotely");
        }
        lore.add("&b▸ Right-click to toggle remote mail");

        return createClaimIconItem(mb.parentClaimId(), Material.ENDER_CHEST, "&d" + info.name, lore, false);
    }

    private static String formatShortDuration(long ms) {
        if (ms <= 0L) return "0s";
        long sec = ms / 1000L;
        long days = sec / 86400L;
        long hours = (sec % 86400L) / 3600L;
        long mins = (sec % 3600L) / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        if (mins > 0) return mins + "m";
        return sec + "s";
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        if (slot == FILTER_SLOT) {
            currentFilter = isRightClick(event) ? currentFilter.previous() : currentFilter.next();
            currentPage = 0;
            reloadAndRefresh();
            return;
        }

        if (slot == TRUSTED_TOGGLE_SLOT) {
            currentFilter = (currentFilter == FilterType.TRUSTED) ? FilterType.OWNED : FilterType.TRUSTED;
            currentPage = 0;
            reloadAndRefresh();
            return;
        }

        if (slot == LISTED_MANAGER_SLOT && player.hasPermission(MarketManager.PERM_LISTED)) {
            String savedSearch = searchQuery;
            String savedFilter = currentFilter.name();
            int savedPage = currentPage;
            ListedClaimsGUI.openAsync(manager, player, null,
                () -> OwnedClaimsGUI.openAsyncWithState(manager, player, savedSearch, savedFilter, savedPage));
            return;
        }

        if (slot == BACK_SLOT) {
            if (currentFilter == FilterType.TRUSTED) {
                currentFilter = FilterType.OWNED;
                currentPage = 0;
                reloadAndRefresh();
            } else {
                manager.openMainMenu(player);
            }
            return;
        }

        if (slot == SEARCH_SLOT) {
            if (isRightClick(event) && searchQuery != null && !searchQuery.isEmpty()) {
                OwnedClaimsGUI.openAsyncWithState(manager, player, null, currentFilter.name(), 0);
                return;
            }
            player.closeInventory();
            String savedFilter = currentFilter.name();
            SignInputGUI.openSearch(plugin, player,
                query -> OwnedClaimsGUI.openAsyncWithState(manager, player, query, savedFilter, 0),
                () -> OwnedClaimsGUI.openAsyncWithState(manager, player, searchQuery, savedFilter, currentPage));
            return;
        }

        if (slot == PREV_PAGE_SLOT && currentPage > 0) {
            currentPage--;
            armedCancelRentClaimId = null;
            GUIStateTracker.saveState(player, GUIStateTracker.GUIType.OWNED_CLAIMS,
                searchQuery, currentFilter.name(), currentPage);
            populateInventory();
            return;
        }

        if (slot == NEXT_PAGE_SLOT) {
            int maxPage = Math.max(0, (filteredClaims.size() - 1) / CLAIM_SLOTS.length);
            if (currentPage < maxPage) {
                currentPage++;
                armedCancelRentClaimId = null;
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.OWNED_CLAIMS,
                    searchQuery, currentFilter.name(), currentPage);
                populateInventory();
            }
            return;
        }

        int slotIndex = -1;
        for (int i = 0; i < CLAIM_SLOTS.length; i++) {
            if (CLAIM_SLOTS[i] == slot) {
                slotIndex = i;
                break;
            }
        }

        if (slotIndex >= 0) {
            int claimIndex = currentPage * CLAIM_SLOTS.length + slotIndex;
            if (claimIndex < filteredClaims.size()) {
                handleClaimClick(event, filteredClaims.get(claimIndex));
            }
        }
    }

    private void handleClaimClick(InventoryClickEvent event, ClaimInfo info) {
        switch (currentFilter) {
            case OWNED -> handleOwnedClick(event, info);
            case TRUSTED -> handleTrustedClick(event, info);
            case RENTED -> handleRentedClick(event, info);
            case BOUGHT -> handleBoughtClick(event, info);
            case MAILBOXES -> handleMailboxClick(event, info);
            case LISTED -> handleListedClick(event, info);
        }
    }

    private void handleOwnedClick(InventoryClickEvent event, ClaimInfo info) {
        if (event.isShiftClick()) {
            teleportIfPermitted(info.claimId);
            return;
        }
        if (isLeftClick(event)) {
            manager.openClaimOptions(player, info.claim, info.claimId);
        } else if (isRightClick(event)) {
            if (player.hasPermission("griefprevention.claim.name")) {
                player.closeInventory();
                String displayName = (info.name != null && !info.name.isEmpty() && !info.name.startsWith("Claim #"))
                    ? info.name : "Unnamed";
                String savedFilter = currentFilter.name();
                int savedPage = currentPage;
                SignInputGUI.openRename(plugin, player, displayName,
                    newName -> {
                        ClaimCustomizationUtil.TextResult normalized = ClaimCustomizationUtil.normalizeName(plugin, player, newName);
                        if (normalized.value().isEmpty()) {
                            plugin.getMessages().send(player, "claim.name-usage");
                            OwnedClaimsGUI.openAsyncWithState(manager, player, searchQuery, savedFilter, savedPage);
                            return;
                        }
                        if (normalized.truncated()) {
                            plugin.getMessages().send(player, "claim.name-truncated",
                                "{max}", String.valueOf(plugin.getConfigManager().getClaimNameMaxLength()));
                        }
                        plugin.getClaimDataStore().setCustomName(info.claimId, normalized.value());
                        plugin.getClaimDataStore().save();
                        plugin.getMessages().send(player, "gui.claim-renamed", "{id}", info.claimId, "{name}", normalized.value());
                        OwnedClaimsGUI.openAsyncWithState(manager, player, searchQuery, savedFilter, savedPage);
                    },
                    () -> OwnedClaimsGUI.openAsyncWithState(manager, player, searchQuery, savedFilter, savedPage));
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        }
    }

    private void handleTrustedClick(InventoryClickEvent event, ClaimInfo info) {
        if (isLeftClick(event) && !event.isShiftClick()) {
            teleportIfPermitted(info.claimId);
        } else if (isRightClick(event) && !event.isShiftClick() && info.canRenew) {
            triggerRentalRenew(info.claimId);
        }
    }

    private void handleRentedClick(InventoryClickEvent event, ClaimInfo info) {
        if (event.isShiftClick()) {
            if (!player.hasPermission("griefprevention.claim.cancelrent")) {
                plugin.getMessages().send(player, "general.no-permission");
                return;
            }
            if (!info.claimId.equals(armedCancelRentClaimId)) {
                armedCancelRentClaimId = info.claimId;
                populateInventory();
                return;
            }
            armedCancelRentClaimId = null;
            player.closeInventory();
            plugin.getClaimCommand().run(player, "claim", "claim", new String[]{"cancelrent", info.claimId});
            return;
        }
        armedCancelRentClaimId = null;
        if (isLeftClick(event)) {
            teleportIfPermitted(info.claimId);
        } else if (isRightClick(event)) {
            triggerRentalRenew(info.claimId);
        }
    }

    private void handleBoughtClick(InventoryClickEvent event, ClaimInfo info) {
        boolean canTp = player.hasPermission("griefprevention.claim.teleport");
        if (isLeftClick(event) && !event.isShiftClick()) {
            if (canTp) {
                teleportIfPermitted(info.claimId);
            } else if (info.stillOwner && info.claim != null) {
                manager.openClaimOptions(player, info.claim, info.claimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
        } else if (isRightClick(event) && !event.isShiftClick()) {
            if (info.stillOwner && info.claim != null) {
                manager.openClaimOptions(player, info.claim, info.claimId);
            }
        }
    }

    private void handleMailboxClick(InventoryClickEvent event, ClaimInfo info) {
        // Explicitly NO teleportation on mailboxes
        if (info.mailboxEntry == null || plugin.getMarketManager() == null) return;
        MarketManager mm = plugin.getMarketManager();
        if (isLeftClick(event) && !event.isShiftClick()) {
            mm.openRemoteDeposit(player, info.mailboxEntry.mailboxId());
        } else if (isRightClick(event)) {
            mm.toggleAcceptsRemoteMail(player, info.mailboxEntry.mailboxId());
            populateInventory();
        }
    }

    private void handleListedClick(InventoryClickEvent event, ClaimInfo info) {
        if (event.isShiftClick()) {
            teleportIfPermitted(info.claimId);
            return;
        }
        if (isLeftClick(event)) {
            String savedSearch = searchQuery;
            String savedFilter = currentFilter.name();
            int savedPage = currentPage;
            ListedClaimsGUI.openAsync(manager, player, info.claimId,
                () -> OwnedClaimsGUI.openAsyncWithState(manager, player, savedSearch, savedFilter, savedPage));
        } else if (isRightClick(event) && info.claim != null) {
            manager.openClaimOptions(player, info.claim, info.claimId);
        }
    }

    private void teleportIfPermitted(String claimId) {
        if (!player.hasPermission("griefprevention.claim.teleport")) {
            plugin.getMessages().send(player, "general.no-permission");
            return;
        }
        player.closeInventory();
        plugin.getClaimCommand().teleportToClaim(player, claimId);
    }

    private void triggerRentalRenew(String claimId) {
        MarketManager mm = plugin.getMarketManager();
        if (mm != null && plugin.getConfigManager().isMarketEnabled() && plugin.getConfigManager().isMarketGuiPurchaseAllowed()) {
            for (MarketListing listing : mm.store().forClaim(claimId)) {
                if (listing.type() == MarketListing.Type.RENT) {
                    mm.initiateGuiTransaction(player, listing);
                    return;
                }
            }
        }
        closeAndRun(() -> plugin.getMessages().send(player, "gui.rental-renew-hint", "{id}", claimId));
    }

    private static class ClaimInfo {
        final Object claim;
        final String claimId;
        final String iconClaimId;
        MarketManager.MailboxEntry mailboxEntry;
        String name;
        String ownerName;
        int childCount;
        int area;
        String location;
        boolean stillOwner;
        boolean isRented;
        boolean isForSale;
        boolean isMailbox;
        boolean isSubdivision;
        boolean canRenew;
        long rentalExpiryMs;
        boolean isBeingEvicted;
        long evictingRemainingMs;
        String purchasePrice;
        long purchaseTime;
        int listingCount;

        ClaimInfo(Object claim, String claimId, String iconClaimId) {
            this.claim = claim;
            this.claimId = claimId;
            this.iconClaimId = iconClaimId;
        }
    }
}
