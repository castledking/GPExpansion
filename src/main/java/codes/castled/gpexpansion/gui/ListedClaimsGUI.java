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
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.storage.ClaimDataStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GUI for viewing and managing active listings (rentals, for-sale & sold mailboxes, and sell signs)
 * across the player's claims. Condenses multiple mailbox listings inside the same parent claim into
 * a single count-badge entry that opens a child breakdown view when clicked.
 */
public class ListedClaimsGUI extends BaseGUI {

    public enum FilterType {
        ALL("All Listings"),
        RENTALS("Rentals"),
        MAILBOXES("Mailboxes (For Sale & Sold)"),
        SELL("Sell Listings");

        private final String displayName;

        FilterType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }

        public FilterType next() {
            FilterType[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private enum RowKind {
        RENTAL,
        SELL,
        MAILBOX_FOR_SALE,
        MAILBOX_SOLD,
        MAILBOX_GROUP
    }

    private static final class ListingRow {
        RowKind kind;
        String uniqueKey;
        String parentClaimId;
        String targetId; // claimId for rentals/sell, mailboxId for mailboxes
        String claimName;
        MarketListing listing; // non-null when backed by a MarketListing
        String counterpartyName;
        String priceDisplay;
        String statusDisplay;
        String locationDisplay;
        boolean requiresEvict;
        boolean evictionReadyToFinalize;
        int groupCount = 1;
        int groupForSaleCount = 0;
        int groupSoldCount = 0;
    }

    private static final int PREV_PAGE_SLOT = 45;
    private static final int BACK_SLOT = 48;
    private static final int FILTER_SLOT = 49;
    private static final int MARKET_SLOT = 50;
    private static final int NEXT_PAGE_SLOT = 53;

    private static final int[] ENTRY_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    private final GPBridge gp;
    private final String parentClaimFilter;
    private final Runnable backAction;
    private FilterType currentFilter = FilterType.ALL;
    private int currentPage = 0;
    private String armedCancelKey = null;
    private final List<ListingRow> rows = new ArrayList<>();

    public ListedClaimsGUI(GUIManager manager, Player player, String parentClaimFilter, Runnable backAction) {
        super(manager, player, "listed-claims");
        this.gp = new GPBridge();
        this.parentClaimFilter = (parentClaimFilter != null && !parentClaimFilter.isBlank()) ? parentClaimFilter : null;
        this.backAction = backAction;
    }

    public static void openAsync(GUIManager manager, Player player, String parentClaimFilter, Runnable backAction) {
        openAsyncWithState(manager, player, parentClaimFilter, null, 0, backAction);
    }

    public static void openAsyncWithState(GUIManager manager, Player player, String parentClaimFilter,
                                          String filterType, int page, Runnable backAction) {
        SchedulerAdapter.runAsyncNow(manager.getPlugin(), () -> {
            ListedClaimsGUI gui = new ListedClaimsGUI(manager, player, parentClaimFilter, backAction);
            if (filterType != null) {
                try {
                    gui.currentFilter = FilterType.valueOf(filterType);
                } catch (IllegalArgumentException ignored) {}
            } else if (parentClaimFilter != null) {
                gui.currentFilter = FilterType.ALL;
            }
            gui.currentPage = Math.max(0, page);
            gui.loadRows();
            SchedulerAdapter.runEntity(manager.getPlugin(), player, () -> {
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.LISTED_CLAIMS,
                    null, gui.currentFilter.name(), gui.currentPage, gui.parentClaimFilter);
                manager.openGUI(player, gui);
            }, null);
        });
    }

    private void reloadAndRefresh() {
        armedCancelKey = null;
        SchedulerAdapter.runAsyncNow(plugin, () -> {
            loadRows();
            SchedulerAdapter.runEntity(plugin, player, () -> {
                int maxPage = Math.max(0, (rows.size() - 1) / ENTRY_SLOTS.length);
                if (currentPage > maxPage) currentPage = maxPage;
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.LISTED_CLAIMS,
                    null, currentFilter.name(), currentPage, parentClaimFilter);
                if (inventory != null) {
                    populateInventory();
                }
            }, null);
        });
    }

    private void loadRows() {
        rows.clear();
        MarketManager mm = plugin.getMarketManager();
        ClaimDataStore ds = plugin.getClaimDataStore();
        if (mm == null) return;

        UUID viewerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Collect all top-level claims owned by player (plus subdivision->parent map)
        Set<String> ownedClaimIds = new HashSet<>();
        Map<String, String> subToParent = new HashMap<>();
        for (Object claim : gp.getClaimsFor(player)) {
            if (gp.isSubdivision(claim)) continue;
            String mainId = gp.getClaimId(claim).orElse(null);
            if (mainId == null) continue;
            ownedClaimIds.add(mainId);
            for (Object sub : gp.getSubclaims(claim)) {
                gp.getClaimId(sub).ifPresent(subId -> subToParent.put(subId, mainId));
            }
        }

        List<ListingRow> directRows = new ArrayList<>();
        Map<String, List<ListingRow>> mailboxRowsByParent = new LinkedHashMap<>();

        // Index player's MarketListings by type
        Map<String, MarketListing> rentListingsByClaim = new HashMap<>();
        List<MarketListing> sellListings = new ArrayList<>();
        List<MarketListing> mailboxListings = new ArrayList<>();

        for (MarketListing listing : mm.store().listings()) {
            if (listing == null || listing.claimId() == null) continue;
            String displayId = listing.displayClaimId() != null ? listing.displayClaimId() : listing.claimId();
            boolean owned = (listing.owner() != null && listing.owner().equals(viewerId))
                || ownedClaimIds.contains(displayId);
            if (!owned) continue;
            if (parentClaimFilter != null && !parentClaimFilter.equals(displayId)) continue;

            switch (listing.type()) {
                case RENT -> rentListingsByClaim.putIfAbsent(listing.claimId(), listing);
                case SELL -> sellListings.add(listing);
                case MAILBOX -> mailboxListings.add(listing);
            }
        }

        // 1) Rentals (both active rentals on owned claims and available rent signs)
        if (currentFilter == FilterType.ALL || currentFilter == FilterType.RENTALS) {
            Set<String> handledRentalClaims = new HashSet<>();

            for (Map.Entry<String, ClaimDataStore.RentalData> entry : ds.getAllRentals().entrySet()) {
                String claimId = entry.getKey();
                ClaimDataStore.RentalData rental = entry.getValue();
                if (claimId == null || rental == null) continue;
                if (!ownedClaimIds.contains(claimId)) continue;
                if (parentClaimFilter != null && !parentClaimFilter.equals(claimId)) continue;

                handledRentalClaims.add(claimId);
                MarketListing listing = rentListingsByClaim.get(claimId);

                ListingRow row = new ListingRow();
                row.kind = RowKind.RENTAL;
                row.uniqueKey = "rent:" + claimId;
                row.parentClaimId = claimId;
                row.targetId = claimId;
                row.claimName = ds.getCustomName(claimId).orElse("Claim #" + claimId);
                row.listing = listing;
                row.counterpartyName = playerName(rental.renter);
                row.priceDisplay = listing != null ? mm.formatListingPrice(listing) : null;
                row.locationDisplay = listing != null
                    ? listing.world() + " (" + listing.x() + ", " + listing.y() + ", " + listing.z() + ")"
                    : null;

                ClaimDataStore.EvictionData ev = ds.getEviction(claimId).orElse(null);
                boolean active = rental.expiry > now;
                row.requiresEvict = active;
                if (ev != null) {
                    if (now >= ev.effectiveAt) {
                        row.statusDisplay = "&cEviction Notice Complete";
                    } else {
                        row.statusDisplay = "&cEvicting &7(" + formatShort(ev.effectiveAt - now) + " left)";
                    }
                } else if (active) {
                    row.statusDisplay = "&aActive &7(expires in " + formatShort(rental.expiry - now) + ")";
                } else {
                    row.statusDisplay = "&eExpired";
                }
                directRows.add(row);
            }

            // Unrented [Rent] signs
            for (Map.Entry<String, MarketListing> entry : rentListingsByClaim.entrySet()) {
                String claimId = entry.getKey();
                if (handledRentalClaims.contains(claimId)) continue;
                MarketListing listing = entry.getValue();

                ListingRow row = new ListingRow();
                row.kind = RowKind.RENTAL;
                row.uniqueKey = "rentsign:" + listing.id();
                row.parentClaimId = claimId;
                row.targetId = claimId;
                row.claimName = ds.getCustomName(claimId).orElse("Claim #" + claimId);
                row.listing = listing;
                row.priceDisplay = mm.formatListingPrice(listing);
                row.statusDisplay = listing.hidden() ? "&7Available &8(Hidden)" : "&aAvailable For Rent";
                row.locationDisplay = listing.world() + " (" + listing.x() + ", " + listing.y() + ", " + listing.z() + ")";
                row.requiresEvict = false;
                directRows.add(row);
            }
        }

        // 2) Sell listings
        if (currentFilter == FilterType.ALL || currentFilter == FilterType.SELL) {
            for (MarketListing listing : sellListings) {
                String claimId = listing.claimId();
                ListingRow row = new ListingRow();
                row.kind = RowKind.SELL;
                row.uniqueKey = "sell:" + listing.id();
                row.parentClaimId = claimId;
                row.targetId = claimId;
                row.claimName = ds.getCustomName(claimId).orElse("Claim #" + claimId);
                row.listing = listing;
                row.priceDisplay = mm.formatListingPrice(listing);
                row.statusDisplay = listing.hidden() ? "&7For Sale &8(Hidden)" : "&aAvailable For Sale";
                row.locationDisplay = listing.world() + " (" + listing.x() + ", " + listing.y() + ", " + listing.z() + ")";
                row.requiresEvict = false;
                directRows.add(row);
            }
        }

        // 3) Mailboxes (For Sale + Sold inside player's claims)
        if (currentFilter == FilterType.ALL || currentFilter == FilterType.MAILBOXES) {
            for (MarketListing listing : mailboxListings) {
                String parentId = listing.displayClaimId() != null ? listing.displayClaimId() : listing.claimId();
                ListingRow row = new ListingRow();
                row.kind = RowKind.MAILBOX_FOR_SALE;
                row.uniqueKey = "mbsale:" + listing.id();
                row.parentClaimId = parentId;
                row.targetId = listing.claimId();
                row.claimName = ds.getCustomName(parentId).orElse("Claim #" + parentId);
                row.listing = listing;
                row.priceDisplay = mm.formatListingPrice(listing);
                row.statusDisplay = listing.hidden() ? "&7For Sale &8(Hidden)" : "&aFor Sale";
                row.locationDisplay = listing.world() + " (" + listing.x() + ", " + listing.y() + ", " + listing.z() + ")";
                row.requiresEvict = false;
                mailboxRowsByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(row);
            }

            // Sold mailboxes inside player's claims
            for (Map.Entry<String, UUID> mb : ds.getAllMailboxes().entrySet()) {
                String mbId = mb.getKey();
                UUID buyerId = mb.getValue();
                if (mbId == null || buyerId == null || buyerId.equals(viewerId)) continue;
                if (mm.isMailboxStillForSale(mbId)) continue;

                String parentId = subToParent.get(mbId);
                if (parentId == null && mbId.startsWith("v:")) {
                    parentId = mm.resolveParentClaimId(mbId, null);
                }
                if (parentId == null || !ownedClaimIds.contains(parentId)) continue;
                if (parentClaimFilter != null && !parentClaimFilter.equals(parentId)) continue;

                ListingRow row = new ListingRow();
                row.kind = RowKind.MAILBOX_SOLD;
                row.uniqueKey = "mbsold:" + mbId;
                row.parentClaimId = parentId;
                row.targetId = mbId;
                row.claimName = ds.getCustomName(parentId).orElse("Claim #" + parentId);
                row.counterpartyName = playerName(buyerId);
                row.requiresEvict = true;

                Location loc = ds.getMailboxContainerLocation(mbId)
                    .or(() -> ds.getMailboxSignLocation(mbId))
                    .orElse(null);
                if (loc != null && loc.getWorld() != null) {
                    row.locationDisplay = loc.getWorld().getName() + " (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
                }

                ClaimDataStore.EvictionData ev = ds.getEviction(mbId).orElse(null);
                if (ev != null) {
                    if (now >= ev.effectiveAt) {
                        row.evictionReadyToFinalize = true;
                        row.statusDisplay = "&aEviction Ready to Finalize";
                    } else {
                        row.statusDisplay = "&cEvicting &7(" + formatShort(ev.effectiveAt - now) + " left)";
                    }
                } else {
                    row.statusDisplay = "&dSold Mailbox";
                }
                mailboxRowsByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(row);
            }
        }

        rows.addAll(directRows);

        // Condense multiple mailboxes in the same parent claim when at top-level view
        for (Map.Entry<String, List<ListingRow>> entry : mailboxRowsByParent.entrySet()) {
            String parentId = entry.getKey();
            List<ListingRow> group = entry.getValue();
            if (parentClaimFilter == null && group.size() > 1) {
                ListingRow condensed = new ListingRow();
                condensed.kind = RowKind.MAILBOX_GROUP;
                condensed.uniqueKey = "mbgroup:" + parentId;
                condensed.parentClaimId = parentId;
                condensed.targetId = parentId;
                condensed.claimName = ds.getCustomName(parentId).orElse("Claim #" + parentId);
                condensed.groupCount = group.size();
                for (ListingRow r : group) {
                    if (r.kind == RowKind.MAILBOX_SOLD) condensed.groupSoldCount++;
                    else condensed.groupForSaleCount++;
                }
                rows.add(condensed);
            } else {
                rows.addAll(group);
            }
        }
    }

    private String playerName(UUID uuid) {
        if (uuid == null) return "Unknown";
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : uuid.toString().substring(0, 8);
    }

    private static String formatShort(long ms) {
        if (ms <= 0) return "0s";
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
    public Inventory createInventory() {
        String title;
        if (parentClaimFilter != null) {
            String parentName = plugin.getClaimDataStore().getCustomName(parentClaimFilter)
                .orElse("Claim #" + parentClaimFilter);
            title = getString("title-child", "&6&lMailboxes in {claim}").replace("{claim}", parentName);
        } else {
            title = getString("title", "&6&lListed Claims");
        }
        inventory = createBaseInventoryWithTitle(title, 54);
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        inventory.clear();
        fillBorder(createFiller());

        inventory.setItem(BACK_SLOT, createBackItem());
        inventory.setItem(FILTER_SLOT, createFilterItem());
        if (plugin.getConfigManager().isMarketEnabled() && player.hasPermission(MarketManager.PERM_MARKET)) {
            inventory.setItem(MARKET_SLOT, createMarketShortcutItem());
        }

        if (currentPage > 0) {
            inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        }
        int maxPage = Math.max(0, (rows.size() - 1) / ENTRY_SLOTS.length);
        if (currentPage < maxPage) {
            inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        }

        int start = currentPage * ENTRY_SLOTS.length;
        for (int i = 0; i < ENTRY_SLOTS.length && start + i < rows.size(); i++) {
            inventory.setItem(ENTRY_SLOTS[i], createRowItem(rows.get(start + i)));
        }
    }

    private ItemStack createFilterItem() {
        List<String> lore = new ArrayList<>();
        for (FilterType ft : FilterType.values()) {
            boolean active = ft == currentFilter;
            lore.add((active ? "&a▸ " : "&7  ") + ft.getDisplayName());
        }
        lore.add("");
        lore.add("&eClick to cycle filter");
        return createItem(Material.HOPPER, "&6&lFilter: &e" + currentFilter.getDisplayName(), lore);
    }

    private ItemStack createMarketShortcutItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Browse public listings or send");
        lore.add("&7remote mailbox gifts and letters.");
        lore.add("");
        lore.add("&e▸ Click to open Claim Market");
        return createItem(Material.EMERALD, "&a&lOpen Claim Market", lore);
    }

    private ItemStack createBackItem() {
        String hint = parentClaimFilter != null ? "&7Return to all listed claims" : "&7Return to previous menu";
        return createItem(Material.ARROW, "&c&lBack", List.of(hint));
    }

    private ItemStack createPrevPageItem() {
        int total = Math.max(1, (rows.size() - 1) / ENTRY_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + currentPage + "/" + total));
    }

    private ItemStack createNextPageItem() {
        int total = Math.max(1, (rows.size() - 1) / ENTRY_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&lNext Page »", List.of("&7Page " + (currentPage + 2) + "/" + total));
    }

    private ItemStack createRowItem(ListingRow row) {
        if (row.kind == RowKind.MAILBOX_GROUP) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Claim ID: &f#" + row.parentClaimId);
            lore.add("&7Total Mailboxes: &e" + row.groupCount);
            if (row.groupForSaleCount > 0) {
                lore.add("&7  • For Sale: &a" + row.groupForSaleCount);
            }
            if (row.groupSoldCount > 0) {
                lore.add("&7  • Sold: &d" + row.groupSoldCount);
            }
            lore.add("");
            lore.add("&a▸ Left-click to view " + row.groupCount + " mailboxes");
            if (player.hasPermission("griefprevention.claim.teleport")) {
                lore.add("&b▸ Right-click to teleport to claim");
            }
            ItemStack item = createClaimIconItem(row.parentClaimId, Material.ENDER_CHEST,
                "&d" + row.claimName + " &7(&e" + row.groupCount + " Mailboxes&7)", lore, false);
            item.setAmount(Math.max(1, Math.min(64, row.groupCount)));
            return item;
        }

        MarketManager mm = plugin.getMarketManager();
        Material fallback = switch (row.kind) {
            case RENTAL -> Material.CLOCK;
            case SELL -> Material.GOLD_BLOCK;
            case MAILBOX_FOR_SALE -> Material.CHEST;
            case MAILBOX_SOLD, MAILBOX_GROUP -> Material.ENDER_CHEST;
        };
        String typeLabel = switch (row.kind) {
            case RENTAL -> "&bRental";
            case SELL -> "&6Claim For Sale";
            case MAILBOX_FOR_SALE -> "&dMailbox For Sale";
            case MAILBOX_SOLD, MAILBOX_GROUP -> "&dSold Mailbox";
        };

        List<String> lore = new ArrayList<>();
        lore.add("&7Claim ID: &f#" + row.parentClaimId);
        if (row.kind == RowKind.MAILBOX_FOR_SALE || row.kind == RowKind.MAILBOX_SOLD) {
            lore.add("&7Mailbox: &f" + mm.formatMailboxLabel(row.targetId));
        }
        lore.add("&7Type: " + typeLabel);
        if (row.counterpartyName != null) {
            String role = row.kind == RowKind.RENTAL ? "Renter" : "Buyer";
            lore.add("&7" + role + ": &f" + row.counterpartyName);
        }
        if (row.priceDisplay != null) {
            lore.add("&7Price: &a" + row.priceDisplay);
        }
        if (row.statusDisplay != null) {
            lore.add("&7Status: " + row.statusDisplay);
        }
        if (row.locationDisplay != null) {
            lore.add("&7Location: &f" + row.locationDisplay);
        }

        lore.add("");
        boolean armed = row.uniqueKey.equals(armedCancelKey);
        if (row.listing != null) {
            lore.add("&a▸ Left-click to edit price & settings");
        }
        if (player.hasPermission("griefprevention.claim.teleport")) {
            lore.add("&b▸ Right-click to teleport");
        }
        if (row.requiresEvict) {
            if (row.evictionReadyToFinalize) {
                lore.add("&c▸ Shift-click to [EVICT] (finalize now)");
            } else {
                lore.add("&c▸ Shift-click to [EVICT]");
            }
        } else if (row.listing != null) {
            if (armed) {
                lore.add("&c&l▸ Shift-click again to confirm [CANCEL]");
            } else {
                lore.add("&c▸ Shift-click to [CANCEL] listing");
            }
        }

        String displayTitle = switch (row.kind) {
            case MAILBOX_FOR_SALE -> "&d" + row.claimName + " &7(Mailbox For Sale)";
            case MAILBOX_SOLD -> "&d" + row.claimName + " &7(" + row.counterpartyName + "'s Mailbox)";
            default -> "&6" + row.claimName;
        };

        return createClaimIconItem(row.parentClaimId, fallback, displayTitle, lore, armed);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        if (slot == BACK_SLOT) {
            if (parentClaimFilter != null) {
                ListedClaimsGUI.openAsyncWithState(manager, player, null, currentFilter.name(), 0, backAction);
            } else if (backAction != null) {
                backAction.run();
            } else {
                manager.openMainMenu(player);
            }
            return;
        }

        if (slot == FILTER_SLOT) {
            currentFilter = currentFilter.next();
            currentPage = 0;
            reloadAndRefresh();
            return;
        }

        if (slot == MARKET_SLOT && plugin.getConfigManager().isMarketEnabled()
                && player.hasPermission(MarketManager.PERM_MARKET)) {
            manager.openClaimMarket(player);
            return;
        }

        if (slot == PREV_PAGE_SLOT && currentPage > 0) {
            currentPage--;
            armedCancelKey = null;
            populateInventory();
            return;
        }

        if (slot == NEXT_PAGE_SLOT) {
            int maxPage = Math.max(0, (rows.size() - 1) / ENTRY_SLOTS.length);
            if (currentPage < maxPage) {
                currentPage++;
                armedCancelKey = null;
                populateInventory();
            }
            return;
        }

        int slotIdx = -1;
        for (int i = 0; i < ENTRY_SLOTS.length; i++) {
            if (ENTRY_SLOTS[i] == slot) {
                slotIdx = i;
                break;
            }
        }
        if (slotIdx < 0) return;

        int index = currentPage * ENTRY_SLOTS.length + slotIdx;
        if (index >= rows.size()) return;

        ListingRow row = rows.get(index);
        handleRowClick(event, row);
    }

    private void handleRowClick(InventoryClickEvent event, ListingRow row) {
        if (row.kind == RowKind.MAILBOX_GROUP) {
            if (isRightClick(event) && !event.isShiftClick()) {
                if (player.hasPermission("griefprevention.claim.teleport")) {
                    player.closeInventory();
                    plugin.getClaimCommand().teleportToClaim(player, row.parentClaimId);
                } else {
                    plugin.getMessages().send(player, "general.no-permission");
                }
                return;
            }
            ListedClaimsGUI.openAsyncWithState(manager, player, row.parentClaimId,
                FilterType.MAILBOXES.name(), 0, backAction);
            return;
        }

        if (event.isShiftClick()) {
            if (row.requiresEvict) {
                player.closeInventory();
                if (row.kind == RowKind.MAILBOX_SOLD) {
                    plugin.getClaimCommand().run(player, "claim", "claim", new String[]{"evict", "mailbox", row.targetId});
                } else {
                    plugin.getClaimCommand().run(player, "claim", "claim", new String[]{"evict", row.parentClaimId});
                }
                return;
            }
            if (row.listing != null) {
                if (!row.uniqueKey.equals(armedCancelKey)) {
                    armedCancelKey = row.uniqueKey;
                    populateInventory();
                    return;
                }
                armedCancelKey = null;
                plugin.getMarketManager().removeForSaleListing(player, row.listing.id());
                reloadAndRefresh();
            }
            return;
        }

        armedCancelKey = null;

        if (isRightClick(event)) {
            if (player.hasPermission("griefprevention.claim.teleport")) {
                player.closeInventory();
                plugin.getClaimCommand().teleportToClaim(player, row.parentClaimId);
            } else {
                plugin.getMessages().send(player, "general.no-permission");
            }
            return;
        }

        if (isLeftClick(event)) {
            if (row.listing != null) {
                String savedParent = parentClaimFilter;
                String savedFilter = currentFilter.name();
                int savedPage = currentPage;
                manager.openMarketListingEdit(player, row.listing,
                    () -> ListedClaimsGUI.openAsyncWithState(manager, player, savedParent, savedFilter, savedPage, backAction));
            } else if (row.parentClaimId != null) {
                gp.findClaimById(row.parentClaimId).ifPresent(claim ->
                    manager.openClaimOptions(player, claim, row.parentClaimId));
            }
        }
    }
}
