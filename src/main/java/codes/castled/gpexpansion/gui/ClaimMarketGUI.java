package codes.castled.gpexpansion.gui;

import org.bukkit.Bukkit;
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
import codes.castled.gpexpansion.util.ClaimGeometryUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Paginated Claim Market GUI for browsing available claims & mailboxes to rent or buy,
 * editing own listings, and remotely depositing gifts or sending written letters into mailboxes.
 */
public class ClaimMarketGUI extends BaseGUI {

    public enum FilterType {
        ALL_AVAILABLE("All Available", Material.EMERALD),
        FOR_RENT("Claims For Rent", Material.CLOCK),
        FOR_SALE("Claims For Sale", Material.GOLD_BLOCK),
        MAILBOXES_FOR_SALE("Mailboxes For Sale", Material.CHEST),
        SEND_MAIL("Send Gift / Letter", Material.WRITABLE_BOOK),
        MY_LISTINGS("My Listings", Material.BOOK);

        private final String displayName;
        private final Material icon;

        FilterType(String displayName, Material icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getDisplayName() { return displayName; }
        public Material getIcon() { return icon; }

        public FilterType next(boolean mailEnabled) {
            FilterType[] values = values();
            int idx = (ordinal() + 1) % values.length;
            while (!mailEnabled && values[idx] == SEND_MAIL) {
                idx = (idx + 1) % values.length;
            }
            return values[idx];
        }

        public FilterType previous(boolean mailEnabled) {
            FilterType[] values = values();
            int idx = (ordinal() - 1 + values.length) % values.length;
            while (!mailEnabled && values[idx] == SEND_MAIL) {
                idx = (idx - 1 + values.length) % values.length;
            }
            return values[idx];
        }
    }

    public enum SortMode {
        NEWEST("Newest First"),
        PRICE_ASC("Lowest Price"),
        PRICE_DESC("Highest Price"),
        AREA_DESC("Largest Area");

        private final String displayName;

        SortMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }

        public SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private static final int INFO_SLOT = 4;
    private static final int PREV_PAGE_SLOT = 45;
    private static final int SEARCH_SLOT = 47;
    private static final int BACK_SLOT = 48;
    private static final int FILTER_SLOT = 49;
    private static final int SORT_SLOT = 50;
    private static final int LISTED_SHORTCUT_SLOT = 51;
    private static final int NEXT_PAGE_SLOT = 53;

    private static final int[] ENTRY_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    private static final class MarketCard {
        final MarketListing listing;
        final MarketManager.MailboxEntry mailbox;
        String displayClaimId;
        String title;
        String ownerName;
        String description;
        String priceDisplay;
        String locationDisplay;
        int area;
        boolean ownListing;
        boolean rentedByViewer;
        boolean currentlyRented;

        MarketCard(MarketListing listing) {
            this.listing = listing;
            this.mailbox = null;
        }

        MarketCard(MarketManager.MailboxEntry mailbox) {
            this.listing = null;
            this.mailbox = mailbox;
        }
    }

    private final GPBridge gp;
    private FilterType currentFilter = FilterType.ALL_AVAILABLE;
    private SortMode currentSort = SortMode.NEWEST;
    private int currentPage = 0;
    private String searchQuery;
    private final List<MarketCard> cards = new ArrayList<>();

    public ClaimMarketGUI(GUIManager manager, Player player, String searchQuery) {
        super(manager, player, "claim-market");
        this.gp = new GPBridge();
        this.searchQuery = searchQuery;
        String defSort = plugin.getConfigManager().getMarketDefaultSort();
        if (defSort != null) {
            try {
                this.currentSort = SortMode.valueOf(defSort.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public static void openAsync(GUIManager manager, Player player, String searchQuery) {
        openAsyncWithState(manager, player, searchQuery, null, 0);
    }

    public static void openAsyncWithState(GUIManager manager, Player player, String searchQuery, String filterType, int page) {
        SchedulerAdapter.runAsyncNow(manager.getPlugin(), () -> {
            ClaimMarketGUI gui = new ClaimMarketGUI(manager, player, searchQuery);
            boolean mailEnabled = manager.getPlugin().getConfigManager().isMarketMailEnabled();
            if (filterType != null) {
                try {
                    FilterType parsed = FilterType.valueOf(filterType);
                    if (parsed != FilterType.SEND_MAIL || mailEnabled) {
                        gui.currentFilter = parsed;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            gui.currentPage = Math.max(0, page);
            gui.loadEntries();
            SchedulerAdapter.runEntity(manager.getPlugin(), player, () -> {
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.CLAIM_MARKET,
                    gui.searchQuery, gui.currentFilter.name(), gui.currentPage);
                manager.openGUI(player, gui);
            }, null);
        });
    }

    private void reloadAndRefresh() {
        SchedulerAdapter.runAsyncNow(plugin, () -> {
            loadEntries();
            SchedulerAdapter.runEntity(plugin, player, () -> {
                int maxPage = Math.max(0, (cards.size() - 1) / ENTRY_SLOTS.length);
                if (currentPage > maxPage) currentPage = maxPage;
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.CLAIM_MARKET,
                    searchQuery, currentFilter.name(), currentPage);
                if (inventory != null) {
                    populateInventory();
                }
            }, null);
        });
    }

    private void loadEntries() {
        cards.clear();
        MarketManager mm = plugin.getMarketManager();
        if (mm == null) return;

        if (currentFilter == FilterType.SEND_MAIL) {
            if (!plugin.getConfigManager().isMarketMailEnabled()) return;
            List<MarketManager.MailboxEntry> mailboxes = mm.collectOwnedMailboxes(player, true, null);
            for (MarketManager.MailboxEntry entry : mailboxes) {
                MarketCard card = new MarketCard(entry);
                card.displayClaimId = entry.parentClaimId();
                card.title = entry.displayName();
                card.ownerName = entry.ownerName();
                card.locationDisplay = entry.worldName() + " (" + entry.x() + ", " + entry.y() + ", " + entry.z() + ")";
                card.ownListing = entry.ownerId().equals(player.getUniqueId());
                if (matchesSearch(card)) {
                    cards.add(card);
                }
            }
            return;
        }

        boolean isAdmin = player.hasPermission(MarketManager.PERM_ADMIN) || player.hasPermission("griefprevention.admin");
        for (MarketListing listing : mm.store().listings()) {
            if (listing == null || listing.claimId() == null) continue;

            boolean isOwn = (listing.owner() != null && listing.owner().equals(player.getUniqueId()))
                || (listing.displayClaimId() != null && gp.findClaimById(listing.displayClaimId())
                    .map(c -> gp.isOwner(c, player.getUniqueId())).orElse(false));

            if (currentFilter == FilterType.MY_LISTINGS) {
                if (!isOwn) continue;
            } else {
                if (listing.hidden() && !isAdmin) continue;
                switch (currentFilter) {
                    case FOR_RENT -> { if (listing.type() != MarketListing.Type.RENT) continue; }
                    case FOR_SALE -> { if (listing.type() != MarketListing.Type.SELL) continue; }
                    case MAILBOXES_FOR_SALE -> { if (listing.type() != MarketListing.Type.MAILBOX) continue; }
                    default -> {}
                }
            }

            UUID activeRenter = mm.getListingActiveRenter(listing);
            boolean rented = activeRenter != null;
            boolean rentedByMe = rented && activeRenter.equals(player.getUniqueId());
            if (currentFilter != FilterType.MY_LISTINGS && rented && !rentedByMe) {
                // Hide already-rented claims from public "available" tabs unless viewer is the renter
                continue;
            }

            String displayId = listing.displayClaimId() != null ? listing.displayClaimId() : listing.claimId();
            Optional<Object> claimOpt = displayId != null && !displayId.startsWith("v:")
                ? gp.findClaimById(displayId)
                : Optional.empty();
            if (displayId != null && !displayId.startsWith("v:") && claimOpt.isEmpty()) {
                continue;
            }

            MarketCard card = new MarketCard(listing);
            card.displayClaimId = displayId;
            card.ownListing = isOwn;
            card.currentlyRented = rented;
            card.rentedByViewer = rentedByMe;
            card.priceDisplay = mm.formatListingPrice(listing);
            card.locationDisplay = listing.world() + " (" + listing.x() + ", " + listing.y() + ", " + listing.z() + ")";

            String baseName = displayId != null
                ? plugin.getClaimDataStore().getCustomName(displayId).orElse("Claim #" + displayId)
                : "Claim";
            if (listing.type() == MarketListing.Type.MAILBOX) {
                card.title = "Mailbox in " + baseName;
            } else {
                card.title = baseName;
            }

            if (listing.owner() != null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(listing.owner());
                card.ownerName = op.getName() != null ? op.getName() : listing.owner().toString().substring(0, 8);
            } else if (claimOpt.isPresent()) {
                card.ownerName = ClaimGeometryUtil.getOwnerName(claimOpt.get());
            } else {
                card.ownerName = "Unknown";
            }

            if (displayId != null) {
                card.description = plugin.getClaimDataStore().getDescription(displayId).orElse(null);
            }
            if (listing.type() == MarketListing.Type.MAILBOX) {
                card.area = 1;
            } else if (claimOpt.isPresent()) {
                card.area = ClaimGeometryUtil.getClaimArea(claimOpt.get());
            }

            if (matchesSearch(card)) {
                cards.add(card);
            }
        }

        Comparator<MarketCard> comparator = switch (currentSort) {
            case PRICE_ASC -> Comparator.comparingDouble((MarketCard c) -> c.listing.numericPrice())
                .thenComparing(Comparator.comparingLong((MarketCard c) -> c.listing.createdAt()).reversed());
            case PRICE_DESC -> Comparator.comparingDouble((MarketCard c) -> c.listing.numericPrice()).reversed()
                .thenComparing(Comparator.comparingLong((MarketCard c) -> c.listing.createdAt()).reversed());
            case AREA_DESC -> Comparator.comparingInt((MarketCard c) -> c.area).reversed()
                .thenComparingDouble(c -> c.listing.numericPrice());
            case NEWEST -> Comparator.comparingLong((MarketCard c) -> c.listing.createdAt()).reversed();
        };
        cards.sort(comparator);
    }

    private boolean matchesSearch(MarketCard card) {
        if (searchQuery == null || searchQuery.isBlank()) return true;
        String q = searchQuery.toLowerCase(Locale.ROOT);
        if (card.displayClaimId != null && card.displayClaimId.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (card.title != null && card.title.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (card.ownerName != null && card.ownerName.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (card.description != null && card.description.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (card.listing != null) {
            if (card.listing.claimId() != null && card.listing.claimId().toLowerCase(Locale.ROOT).contains(q)) return true;
            if (card.listing.note() != null && card.listing.note().toLowerCase(Locale.ROOT).contains(q)) return true;
            if (card.listing.world() != null && card.listing.world().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        if (card.mailbox != null && card.mailbox.mailboxId().toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    @Override
    public Inventory createInventory() {
        String title = searchQuery != null && !searchQuery.isBlank()
            ? getString("title-search", "&a&lClaim Market - \"{query}\"").replace("{query}", searchQuery)
            : getString("title", "&a&lClaim Market");
        inventory = createBaseInventoryWithTitle(title, 54);
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        inventory.clear();
        fillBorder(createFiller());

        inventory.setItem(INFO_SLOT, createInfoItem());
        inventory.setItem(BACK_SLOT, createBackItem());
        inventory.setItem(SEARCH_SLOT, createSearchItem());
        inventory.setItem(FILTER_SLOT, createFilterItem());
        if (currentFilter != FilterType.SEND_MAIL) {
            inventory.setItem(SORT_SLOT, createSortItem());
        }
        if (player.hasPermission(MarketManager.PERM_LISTED)) {
            inventory.setItem(LISTED_SHORTCUT_SLOT, createListedShortcutItem());
        }

        if (currentPage > 0) {
            inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        }
        int maxPage = Math.max(0, (cards.size() - 1) / ENTRY_SLOTS.length);
        if (currentPage < maxPage) {
            inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        }

        int start = currentPage * ENTRY_SLOTS.length;
        for (int i = 0; i < ENTRY_SLOTS.length && start + i < cards.size(); i++) {
            MarketCard card = cards.get(start + i);
            inventory.setItem(ENTRY_SLOTS[i], card.mailbox != null ? createMailboxMailItem(card) : createListingItem(card));
        }
    }

    private ItemStack createInfoItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Browse claims & mailboxes available");
        lore.add("&7to rent or buy across the server.");
        lore.add("");
        lore.add("&6How to list your claim or mailbox:");
        lore.add("&7• Place a &f[Rent]&7, &f[Sell]&7, or &f[Mailbox]&7 sign");
        lore.add("&7  or use Setup Wizards in &f/claim options&7.");
        lore.add("&7• &fShift-click&7 your listing (or click");
        lore.add("&7  &fManage My Listings&7) to edit its price,");
        lore.add("&7  visibility, teleport, or note.");
        if (plugin.getConfigManager().isMarketMailEnabled()) {
            lore.add("");
            lore.add("&dSend Gifts & Letters:");
            lore.add("&7• Switch Filter to &fSend Gift / Letter&7");
            lore.add("&7  to deposit items or mail written letters!");
        }
        return createItem(Material.OAK_SIGN, "&e&lClaim Market Guide", lore);
    }

    private ItemStack createFilterItem() {
        boolean mailEnabled = plugin.getConfigManager().isMarketMailEnabled();
        List<String> lore = new ArrayList<>();
        for (FilterType ft : FilterType.values()) {
            if (!mailEnabled && ft == FilterType.SEND_MAIL) continue;
            boolean active = ft == currentFilter;
            lore.add((active ? "&a▸ " : "&7  ") + ft.getDisplayName());
        }
        lore.add("");
        lore.add("&eLeft/Right-click to cycle filter");
        return createItem(Material.HOPPER, "&6&lFilter: &e" + currentFilter.getDisplayName(), lore);
    }

    private ItemStack createSortItem() {
        List<String> lore = new ArrayList<>();
        for (SortMode sm : SortMode.values()) {
            boolean active = sm == currentSort;
            lore.add((active ? "&a▸ " : "&7  ") + sm.getDisplayName());
        }
        lore.add("");
        lore.add("&eClick to change sort order");
        return createItem(Material.COMPARATOR, "&6&lSort: &e" + currentSort.getDisplayName(), lore);
    }

    private ItemStack createListedShortcutItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7View and manage all active rentals,");
        lore.add("&7sold mailboxes, and for-sale signs");
        lore.add("&7inside your claims.");
        lore.add("");
        lore.add("&e▸ Click to open Listed Claims");
        return createItem(Material.LECTERN, "&6&lManage My Listings", lore);
    }

    private ItemStack createSearchItem() {
        List<String> lore = new ArrayList<>();
        if (searchQuery != null && !searchQuery.isBlank()) {
            lore.add("&7Current search: &e" + searchQuery);
            lore.add("");
        }
        lore.add("&7Search by claim name, ID, seller,");
        lore.add("&7market note, or world.");
        lore.add("");
        lore.add("&a▸ Left-click to search");
        if (searchQuery != null && !searchQuery.isBlank()) {
            lore.add("&c▸ Right-click to clear search");
        }
        return createItem(Material.COMPASS, "&b&lSearch Market", lore);
    }

    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to main menu"));
    }

    private ItemStack createPrevPageItem() {
        int total = Math.max(1, (cards.size() - 1) / ENTRY_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&l« Previous Page", List.of("&7Page " + currentPage + "/" + total));
    }

    private ItemStack createNextPageItem() {
        int total = Math.max(1, (cards.size() - 1) / ENTRY_SLOTS.length + 1);
        return createItem(Material.ARROW, "&e&lNext Page »", List.of("&7Page " + (currentPage + 2) + "/" + total));
    }

    private ItemStack createListingItem(MarketCard card) {
        MarketListing listing = card.listing;
        MarketManager mm = plugin.getMarketManager();

        String badge = switch (listing.type()) {
            case RENT -> "&b[For Rent]";
            case SELL -> "&6[For Sale]";
            case MAILBOX -> "&d[Mailbox For Sale]";
        };
        Material fallback = switch (listing.type()) {
            case RENT -> Material.CLOCK;
            case SELL -> Material.GOLD_BLOCK;
            case MAILBOX -> Material.CHEST;
        };

        List<String> lore = new ArrayList<>();
        lore.add("&7Type: " + badge);
        lore.add("&7Claim ID: &f#" + (card.displayClaimId != null ? card.displayClaimId : "?"));
        if (listing.type() == MarketListing.Type.MAILBOX) {
            lore.add("&7Mailbox: &f" + mm.formatMailboxLabel(listing.claimId()));
        }
        lore.add("&7Seller: &f" + card.ownerName);
        lore.add("&7Price: &a" + card.priceDisplay);
        if (listing.type() == MarketListing.Type.RENT && listing.maxCap() != null && !listing.maxCap().isBlank()) {
            lore.add("&7Max Duration: &f" + listing.maxCap());
        }
        if (card.area > 0 && listing.type() != MarketListing.Type.MAILBOX) {
            lore.add("&7Area: &f" + card.area + " blocks");
        }
        lore.add("&7Location: &f" + card.locationDisplay);

        if (listing.hidden()) {
            lore.add("&c[Hidden from public market]");
        }
        if (card.currentlyRented) {
            lore.add(card.rentedByViewer ? "&a⚡ Currently Rented by You" : "&e⚡ Currently Rented");
        }
        if (listing.note() != null && !listing.note().isBlank()) {
            lore.add("");
            lore.add("&f\"" + listing.note() + "\"");
        } else if (card.description != null && !card.description.isBlank()) {
            lore.add("");
            lore.add("&7" + card.description);
        }

        lore.add("");
        boolean canEdit = mm.canManageListing(player, listing);
        if (card.ownListing && canEdit) {
            lore.add("&e▸ Left-click to edit listing");
        } else if (plugin.getConfigManager().isMarketGuiPurchaseAllowed()) {
            if (listing.type() == MarketListing.Type.RENT) {
                lore.add(card.rentedByViewer ? "&a▸ Left-click to extend rental" : "&a▸ Left-click to rent");
            } else if (listing.type() == MarketListing.Type.MAILBOX) {
                lore.add("&a▸ Left-click to buy mailbox");
            } else {
                lore.add("&a▸ Left-click to buy claim");
            }
        } else {
            lore.add("&e▸ Left-click for sign coordinates");
        }

        if (mm.canTeleportToListing(player, listing)) {
            lore.add("&b▸ Right-click to teleport");
        }
        if (canEdit) {
            lore.add("&6▸ Shift-click to edit price & settings");
        }

        return createClaimIconItem(card.displayClaimId, fallback, "&a" + card.title, lore, card.ownListing || card.rentedByViewer);
    }

    private ItemStack createMailboxMailItem(MarketCard card) {
        MarketManager.MailboxEntry mb = card.mailbox;
        Material fallback = card.ownListing ? Material.ENDER_CHEST : Material.CHEST;

        List<String> lore = new ArrayList<>();
        lore.add("&7Owner: &f" + mb.ownerName());
        if (mb.parentClaimId() != null) {
            lore.add("&7Claim ID: &f#" + mb.parentClaimId());
        }
        lore.add("&7Mailbox: &f" + plugin.getMarketManager().formatMailboxLabel(mb.mailboxId()));
        lore.add("&7Location: &f" + card.locationDisplay);
        lore.add("&7Remote Mail: " + (mb.acceptsMail() ? "&aAccepting Gifts & Letters" : "&cClosed to Remote Mail"));
        lore.add("");

        if (card.ownListing) {
            if (plugin.getConfigManager().isMarketMailOwnerRemoteOpenAllowed()
                    && player.hasPermission(MarketManager.PERM_MAIL_OPEN_OWN)) {
                lore.add("&a▸ Left-click to open your mailbox");
            }
        } else if (plugin.getConfigManager().isMarketMailDepositAllowed()
                && player.hasPermission(MarketManager.PERM_MAIL_DEPOSIT)) {
            lore.add("&a▸ Left-click to deposit gift items");
        }
        if (plugin.getConfigManager().isMarketMailLettersAllowed()
                && player.hasPermission(MarketManager.PERM_MAIL_LETTER)) {
            lore.add("&b▸ Right-click to send written letter");
        }
        if (card.ownListing || player.hasPermission(MarketManager.PERM_ADMIN)) {
            lore.add("&6▸ Shift-click to toggle remote mail");
        }

        return createClaimIconItem(mb.parentClaimId(), fallback, "&d" + mb.displayName(), lore, card.ownListing);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        if (slot == BACK_SLOT) {
            manager.openMainMenu(player);
            return;
        }

        if (slot == FILTER_SLOT) {
            boolean mailEnabled = plugin.getConfigManager().isMarketMailEnabled();
            currentFilter = isRightClick(event)
                ? currentFilter.previous(mailEnabled)
                : currentFilter.next(mailEnabled);
            currentPage = 0;
            reloadAndRefresh();
            return;
        }

        if (slot == SORT_SLOT && currentFilter != FilterType.SEND_MAIL) {
            currentSort = currentSort.next();
            currentPage = 0;
            reloadAndRefresh();
            return;
        }

        if (slot == LISTED_SHORTCUT_SLOT && player.hasPermission(MarketManager.PERM_LISTED)) {
            ListedClaimsGUI.openAsync(manager, player, null,
                () -> ClaimMarketGUI.openAsyncWithState(manager, player, searchQuery, currentFilter.name(), currentPage));
            return;
        }

        if (slot == SEARCH_SLOT) {
            if (isRightClick(event) && searchQuery != null && !searchQuery.isBlank()) {
                searchQuery = null;
                currentPage = 0;
                ClaimMarketGUI.openAsyncWithState(manager, player, null, currentFilter.name(), 0);
                return;
            }
            player.closeInventory();
            String savedFilter = currentFilter.name();
            SignInputGUI.openSearch(plugin, player,
                query -> ClaimMarketGUI.openAsyncWithState(manager, player, query, savedFilter, 0),
                () -> ClaimMarketGUI.openAsyncWithState(manager, player, searchQuery, savedFilter, currentPage));
            return;
        }

        if (slot == PREV_PAGE_SLOT && currentPage > 0) {
            currentPage--;
            GUIStateTracker.saveState(player, GUIStateTracker.GUIType.CLAIM_MARKET,
                searchQuery, currentFilter.name(), currentPage);
            populateInventory();
            return;
        }

        if (slot == NEXT_PAGE_SLOT) {
            int maxPage = Math.max(0, (cards.size() - 1) / ENTRY_SLOTS.length);
            if (currentPage < maxPage) {
                currentPage++;
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.CLAIM_MARKET,
                    searchQuery, currentFilter.name(), currentPage);
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
        if (index >= cards.size()) return;

        MarketCard card = cards.get(index);
        if (card.mailbox != null) {
            handleMailboxCardClick(event, card);
        } else if (card.listing != null) {
            handleListingCardClick(event, card);
        }
    }

    private void handleListingCardClick(InventoryClickEvent event, MarketCard card) {
        MarketManager mm = plugin.getMarketManager();
        MarketListing listing = card.listing;
        Runnable reopen = () -> ClaimMarketGUI.openAsyncWithState(manager, player, searchQuery, currentFilter.name(), currentPage);

        if (event.isShiftClick()) {
            if (mm.canManageListing(player, listing)) {
                manager.openMarketListingEdit(player, listing, reopen);
            }
            return;
        }

        if (isLeftClick(event)) {
            if (card.ownListing && mm.canManageListing(player, listing)) {
                manager.openMarketListingEdit(player, listing, reopen);
            } else {
                mm.initiateGuiTransaction(player, listing);
            }
        } else if (isRightClick(event)) {
            mm.teleportToListing(player, listing);
        }
    }

    private void handleMailboxCardClick(InventoryClickEvent event, MarketCard card) {
        MarketManager mm = plugin.getMarketManager();
        MarketManager.MailboxEntry mb = card.mailbox;

        if (event.isShiftClick()) {
            if (card.ownListing || player.hasPermission(MarketManager.PERM_ADMIN)) {
                mm.toggleAcceptsRemoteMail(player, mb.mailboxId());
                reloadAndRefresh();
            }
            return;
        }

        if (isLeftClick(event)) {
            mm.openRemoteDeposit(player, mb.mailboxId());
        } else if (isRightClick(event)) {
            String savedFilter = currentFilter.name();
            int savedPage = currentPage;
            mm.promptAndSendLetter(player, mb.mailboxId(),
                () -> ClaimMarketGUI.openAsyncWithState(manager, player, searchQuery, savedFilter, savedPage));
        }
    }
}
