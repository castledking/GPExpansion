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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Editor GUI for an individual {@link MarketListing} (price, market visibility,
 * allow-teleport, custom tagline/note, shortcut to Global Claim Settings, and remove/evict).
 */
public class MarketListingEditGUI extends BaseGUI {

    private static final int SUMMARY_SLOT = 4;
    private static final int PRICE_SLOT = 10;
    private static final int VISIBILITY_SLOT = 11;
    private static final int TELEPORT_SLOT = 12;
    private static final int NOTE_SLOT = 14;
    private static final int GLOBAL_SETTINGS_SLOT = 15;
    private static final int REMOVE_SLOT = 16;
    private static final int BACK_SLOT = 22;

    private final MarketListing listing;
    private final Runnable backAction;
    private final GPBridge gp;
    private boolean confirmRemove = false;

    public MarketListingEditGUI(GUIManager manager, Player player, MarketListing listing, Runnable backAction) {
        super(manager, player, "market-listing-edit");
        this.listing = listing;
        this.backAction = backAction;
        this.gp = new GPBridge();
    }

    @Override
    public Inventory createInventory() {
        String displayId = listing.displayClaimId() != null ? listing.displayClaimId() : "?";
        String title = getString("title", "&6&lEdit Listing &7(#" + displayId + ")")
            .replace("{id}", displayId);
        inventory = createBaseInventoryWithTitle(title, 27);
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        inventory.clear();
        fillBorder(createFiller());

        inventory.setItem(SUMMARY_SLOT, createSummaryItem());
        inventory.setItem(PRICE_SLOT, createPriceItem());
        inventory.setItem(VISIBILITY_SLOT, createVisibilityItem());
        inventory.setItem(TELEPORT_SLOT, createTeleportItem());
        inventory.setItem(NOTE_SLOT, createNoteItem());
        inventory.setItem(GLOBAL_SETTINGS_SLOT, createGlobalSettingsItem());
        inventory.setItem(REMOVE_SLOT, createRemoveOrEvictItem());
        inventory.setItem(BACK_SLOT, createBackItem());
    }

    private ItemStack createSummaryItem() {
        MarketManager mm = plugin.getMarketManager();
        String displayId = listing.displayClaimId() != null ? listing.displayClaimId() : "?";
        String claimName = plugin.getClaimDataStore().getCustomName(displayId).orElse("Claim #" + displayId);
        String typeLabel = switch (listing.type()) {
            case RENT -> "Claim Rental";
            case SELL -> "Claim For Sale";
            case MAILBOX -> "Mailbox For Sale";
        };
        Material fallback = switch (listing.type()) {
            case RENT -> Material.CLOCK;
            case SELL -> Material.GOLD_BLOCK;
            case MAILBOX -> Material.CHEST;
        };

        List<String> lore = new ArrayList<>();
        lore.add("&7Type: &f" + typeLabel);
        lore.add("&7Claim ID: &f#" + displayId);
        if (listing.type() == MarketListing.Type.MAILBOX && listing.claimId() != null) {
            lore.add("&7Mailbox: &f" + mm.formatMailboxLabel(listing.claimId()));
        }
        lore.add("&7Price: &a" + mm.formatListingPrice(listing));
        lore.add("&7Sign: &f" + listing.world() + " @ " + listing.x() + ", " + listing.y() + ", " + listing.z());
        if (listing.owner() != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(listing.owner());
            String ownerName = op.getName() != null ? op.getName() : listing.owner().toString().substring(0, 8);
            lore.add("&7Seller: &f" + ownerName);
        }
        lore.add("&7Market Visibility: " + (listing.hidden() ? "&cHidden" : "&aListed"));
        lore.add("&7Market Teleport: " + (listing.allowTeleport() ? "&aAllowed" : "&cDisabled"));
        if (listing.note() != null && !listing.note().isBlank()) {
            lore.add("&7Note: &f\"" + listing.note() + "\"");
        }

        return createClaimIconItem(displayId, fallback, "&6&l" + claimName, lore, false);
    }

    private ItemStack createPriceItem() {
        MarketManager mm = plugin.getMarketManager();
        List<String> lore = new ArrayList<>();
        lore.add("&7Current Price: &a" + mm.formatListingPrice(listing));
        lore.add("");
        lore.add("&7Updates both the Claim Market");
        lore.add("&7and the physical sign in-world.");
        lore.add("&7Supports shorthand: &f500&7, &f2.5k&7, &f1m");
        lore.add("");
        lore.add("&e▸ Click to set new price");
        return createItem(Material.GOLD_INGOT, "&e&lEdit Price", lore);
    }

    private ItemStack createVisibilityItem() {
        boolean visible = !listing.hidden();
        List<String> lore = new ArrayList<>();
        lore.add("&7Status: " + (visible ? "&aVisible on Claim Market" : "&cHidden (In-Person Sign Only)"));
        lore.add("");
        lore.add("&7Controls whether buyers can discover");
        lore.add("&7this sign in &f/claim market&7.");
        lore.add("&8(Separate from Global Claim List)");
        lore.add("");
        lore.add("&e▸ Click to " + (visible ? "hide from market" : "show on market"));
        return createItem(visible ? Material.LIME_DYE : Material.GRAY_DYE, "&a&lMarket Visibility", lore, visible);
    }

    private ItemStack createTeleportItem() {
        boolean globalAllowed = plugin.getConfigManager().isMarketTeleportAllowed();
        boolean listingAllowed = listing.allowTeleport();
        List<String> lore = new ArrayList<>();
        lore.add("&7Listing Setting: " + (listingAllowed ? "&aAllowed" : "&cDisabled"));
        if (!globalAllowed) {
            lore.add("&c(Disabled server-wide in config.yml)");
        }
        lore.add("");
        lore.add("&7Allow prospective buyers to preview");
        lore.add("&7this claim directly from the Market.");
        lore.add("");
        lore.add("&e▸ Click to toggle teleport");
        return createItem(listingAllowed ? Material.ENDER_PEARL : Material.BARRIER, "&b&lAllow Market Teleport", lore);
    }

    private ItemStack createNoteItem() {
        boolean hasNote = listing.note() != null && !listing.note().isBlank();
        List<String> lore = new ArrayList<>();
        if (hasNote) {
            lore.add("&7Current Note:");
            lore.add("&f\"" + listing.note() + "\"");
        } else {
            lore.add("&7Current Note: &8None");
        }
        lore.add("");
        lore.add("&7Add a short tagline shown on your");
        lore.add("&7listing card in the Claim Market.");
        lore.add("");
        lore.add("&a▸ Left-click to edit in chat");
        if (hasNote) {
            lore.add("&c▸ Right-click to clear note");
        }
        return createItem(Material.WRITABLE_BOOK, "&d&lMarket Note", lore);
    }

    private ItemStack createGlobalSettingsItem() {
        String displayId = listing.displayClaimId();
        boolean publicListed = displayId != null && plugin.getClaimDataStore().isPublicListed(displayId);
        List<String> lore = new ArrayList<>();
        lore.add("&7Global List: " + (publicListed ? "&aPublic" : "&7Not Public"));
        lore.add("");
        lore.add("&7Customize the claim's icon, name,");
        lore.add("&7description, spawn point, and");
        lore.add("&7Global Claim List settings.");
        lore.add("");
        lore.add("&e▸ Click to open Global Claim Settings");
        return createItem(Material.ENDER_EYE, "&d&lClaim Appearance & Global Settings", lore);
    }

    private ItemStack createRemoveOrEvictItem() {
        MarketManager mm = plugin.getMarketManager();
        if (listing.type() == MarketListing.Type.RENT && mm.isListingCurrentlyRented(listing)) {
            List<String> lore = new ArrayList<>();
            lore.add("&cThis claim currently has an active renter.");
            lore.add("&7You must complete an eviction notice");
            lore.add("&7before removing the rental sign.");
            lore.add("");
            lore.add("&e▸ Click to start or check eviction");
            return createItem(Material.REDSTONE_BLOCK, "&c&lEvict Renter", lore);
        }

        if (confirmRemove) {
            List<String> lore = new ArrayList<>();
            lore.add("&cAre you sure?");
            lore.add("&7This will break the physical sign");
            lore.add("&7and remove the listing from the market.");
            lore.add("");
            lore.add("&c&l▸ Click again to confirm removal");
            return createItem(Material.RED_TERRACOTTA, "&c&lConfirm Remove Listing", lore, true);
        }

        List<String> lore = new ArrayList<>();
        lore.add("&7Removes this listing from the market");
        lore.add("&7and deletes the physical sign.");
        lore.add("");
        lore.add("&c▸ Click to remove listing");
        return createItem(Material.TNT, "&c&lRemove Listing", lore);
    }

    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to previous menu"));
    }

    private void reopenSelf() {
        manager.openMarketListingEdit(player, listing, backAction);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        MarketManager mm = plugin.getMarketManager();
        if (slot != REMOVE_SLOT) {
            confirmRemove = false;
        }

        if (slot == BACK_SLOT) {
            if (backAction != null) {
                backAction.run();
            } else {
                manager.openClaimMarket(player);
            }
            return;
        }

        if (!mm.canManageListing(player, listing)) {
            plugin.getMessages().send(player, "general.no-permission");
            player.closeInventory();
            return;
        }

        if (slot == PRICE_SLOT) {
            player.closeInventory();
            String current = listing.ecoAmount() != null ? listing.ecoAmount() : "";
            SignInputGUI.openAmount(plugin, player, current, "New price (e.g. 2.5k)",
                input -> mm.updateListingPrice(player, listing, input, this::reopenSelf),
                this::reopenSelf);
            return;
        }

        if (slot == VISIBILITY_SLOT) {
            listing.hidden(!listing.hidden());
            mm.store().put(listing);
            mm.store().saveIfDirty();
            plugin.getMessages().send(player, listing.hidden() ? "market.visibility-off" : "market.visibility-on",
                "{id}", listing.displayClaimId() != null ? listing.displayClaimId() : "?");
            populateInventory();
            return;
        }

        if (slot == TELEPORT_SLOT) {
            listing.allowTeleport(!listing.allowTeleport());
            mm.store().put(listing);
            mm.store().saveIfDirty();
            plugin.getMessages().send(player, listing.allowTeleport() ? "market.teleport-toggled-on" : "market.teleport-toggled-off",
                "{id}", listing.displayClaimId() != null ? listing.displayClaimId() : "?");
            populateInventory();
            return;
        }

        if (slot == NOTE_SLOT) {
            if (isRightClick(event) && listing.note() != null && !listing.note().isBlank()) {
                listing.note(null);
                mm.store().put(listing);
                mm.store().saveIfDirty();
                plugin.getMessages().send(player, "market.note-cleared");
                populateInventory();
                return;
            }
            player.closeInventory();
            int maxLen = Math.max(20, plugin.getConfigManager().getMarketMaxNoteLength());
            plugin.getChatPromptManager().begin(
                player,
                "market.note-prompt",
                new String[]{"{max}", String.valueOf(maxLen)},
                text -> {
                    String trimmed = text.length() > maxLen ? text.substring(0, maxLen) : text;
                    listing.note(trimmed);
                    mm.store().put(listing);
                    mm.store().saveIfDirty();
                    plugin.getMessages().send(player, "market.note-updated");
                    reopenSelf();
                },
                this::reopenSelf
            );
            return;
        }

        if (slot == GLOBAL_SETTINGS_SLOT) {
            String displayId = listing.displayClaimId();
            if (displayId == null) return;
            Optional<Object> claimOpt = gp.findClaimById(displayId);
            if (claimOpt.isEmpty()) {
                plugin.getMessages().send(player, "claim.not-found", "{id}", displayId);
                return;
            }
            manager.openGlobalClaimSettings(player, claimOpt.get(), displayId);
            return;
        }

        if (slot == REMOVE_SLOT) {
            if (listing.type() == MarketListing.Type.RENT && mm.isListingCurrentlyRented(listing)) {
                player.closeInventory();
                if (listing.claimId() != null) {
                    plugin.getClaimCommand().run(player, "claim", "claim", new String[]{"evict", listing.claimId()});
                }
                return;
            }
            if (!confirmRemove) {
                confirmRemove = true;
                populateInventory();
                return;
            }
            confirmRemove = false;
            player.closeInventory();
            mm.removeForSaleListing(player, listing.id());
            if (backAction != null) {
                backAction.run();
            }
        }
    }
}
