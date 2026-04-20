package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Head-based banned players list for a single claim.
 */
public class BannedPlayersGUI extends BaseGUI {

    private static final int[] PLAYER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34
    };

    private static final int PREV_PAGE_SLOT = 45;
    private static final int ADD_BAN_SLOT = 47;
    private static final int BACK_SLOT = 51;
    private static final int NEXT_PAGE_SLOT = 53;

    private final GPBridge gp;
    private final ClaimDataStore claimDataStore;
    private final Object claim;
    private final String claimId;
    private final List<BannedPlayerInfo> bannedPlayers = new ArrayList<>();
    private int currentPage = 0;

    public BannedPlayersGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "banned-players");
        this.gp = new GPBridge();
        this.claimDataStore = plugin.getClaimDataStore();
        this.claim = claim;
        this.claimId = claimId;
    }

    public static void openAsync(GUIManager manager, Player player, Object claim, String claimId) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAsyncNow(manager.getPlugin(), () -> {
            BannedPlayersGUI gui = new BannedPlayersGUI(manager, player, claim, claimId);
            gui.loadBannedPlayers();
            dev.towki.gpexpansion.scheduler.SchedulerAdapter.runEntity(manager.getPlugin(), player, () -> {
                GUIStateTracker.saveState(player, GUIStateTracker.GUIType.BANNED_PLAYERS, null, null, gui.currentPage, claimId);
                manager.openGUI(player, gui);
            }, null);
        });
    }

    @Override
    public Inventory createInventory() {
        String title = getString("title", "&c&lBanned Players - #{id}").replace("{id}", claimId);
        inventory = createBaseInventoryWithTitle(title, 54);
        loadBannedPlayers();
        populateInventory();
        return inventory;
    }

    private void loadBannedPlayers() {
        bannedPlayers.clear();

        ClaimDataStore.BanData entry = claimDataStore.getBans(claimId);
        if (entry == null || entry.bannedPlayers.isEmpty()) {
            return;
        }

        for (UUID playerId : entry.bannedPlayers) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            String playerName = entry.playerNames.getOrDefault(playerId,
                offlinePlayer.getName() != null ? offlinePlayer.getName() : playerId.toString().substring(0, 8));

            bannedPlayers.add(new BannedPlayerInfo(playerId, playerName, offlinePlayer.getLastPlayed()));
        }

        bannedPlayers.sort(Comparator.comparing(info -> info.playerName.toLowerCase()));
        currentPage = Math.min(currentPage, getMaxPage() - 1);
    }

    private void populateInventory() {
        inventory.clear();
        fillBorder(createFiller());

        inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        inventory.setItem(ADD_BAN_SLOT, createAddBanItem());
        inventory.setItem(BACK_SLOT, createBackItem());
        inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());

        int startIndex = currentPage * PLAYER_SLOTS.length;
        for (int i = 0; i < PLAYER_SLOTS.length && startIndex + i < bannedPlayers.size(); i++) {
            inventory.setItem(PLAYER_SLOTS[i], createPlayerHead(bannedPlayers.get(startIndex + i)));
        }

        if (bannedPlayers.isEmpty()) {
            inventory.setItem(22, createItem(Material.BOOK, "&e&lNo Banned Players", List.of(
                "&7No banned players were found on this claim.",
                "",
                "&eUse Ban Player to add one."
            )));
        }
    }

    private ItemStack createPlayerHead(BannedPlayerInfo info) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(info.playerId));
            meta.displayName(colorize("&c" + info.playerName));

            List<String> lore = new ArrayList<>();
            lore.add("&7Claim: &f#" + claimId);
            if (info.lastSeen > 0) {
                long daysSince = Math.max(0L, (System.currentTimeMillis() - info.lastSeen) / (1000L * 60L * 60L * 24L));
                lore.add("&7Last seen: &f" + (daysSince == 0 ? "Today" : daysSince + " days ago"));
            }
            lore.add("");
            lore.add("&eClick to unban player");

            List<net.kyori.adventure.text.Component> loreCmp = new ArrayList<>();
            for (String line : lore) {
                loreCmp.add(colorize(line));
            }
            meta.lore(loreCmp);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack createAddBanItem() {
        String claimName = claimDataStore.getCustomName(claimId).orElse("Claim #" + claimId);
        return createItem(Material.BARRIER, "&c&lBan Player", List.of(
            "&7Ban a player from this claim.",
            "&7Claim: &f" + claimName,
            "",
            "&eClick to enter a player name"
        ));
    }

    private ItemStack createPrevPageItem() {
        if (currentPage > 0) {
            return createItem(Material.ARROW, "&a&lPrevious Page", List.of("&7Page " + currentPage + "/" + getMaxPage()));
        }
        return createFiller();
    }

    private ItemStack createNextPageItem() {
        if (currentPage < getMaxPage() - 1) {
            return createItem(Material.ARROW, "&a&lNext Page", List.of("&7Page " + (currentPage + 2) + "/" + getMaxPage()));
        }
        return createFiller();
    }

    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to trusted players"));
    }

    private int getMaxPage() {
        return Math.max(1, (int) Math.ceil(bannedPlayers.size() / (double) PLAYER_SLOTS.length));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        if (slot == PREV_PAGE_SLOT && currentPage > 0) {
            currentPage--;
            populateInventory();
            return;
        }
        if (slot == NEXT_PAGE_SLOT && currentPage < getMaxPage() - 1) {
            currentPage++;
            populateInventory();
            return;
        }
        if (slot == ADD_BAN_SLOT) {
            openBanInput();
            return;
        }
        if (slot == BACK_SLOT) {
            manager.openClaimTrustedPlayers(player, claim, claimId);
            return;
        }

        int slotIndex = -1;
        for (int i = 0; i < PLAYER_SLOTS.length; i++) {
            if (PLAYER_SLOTS[i] == slot) {
                slotIndex = i;
                break;
            }
        }

        if (slotIndex < 0) {
            return;
        }

        int playerIndex = currentPage * PLAYER_SLOTS.length + slotIndex;
        if (playerIndex >= bannedPlayers.size()) {
            return;
        }

        BannedPlayerInfo info = bannedPlayers.get(playerIndex);
        closeAndRunOnMainThread(() -> {
            runClaimCommand("unban", info.playerName, claimId);
            runLater(() -> BannedPlayersGUI.openAsync(manager, player, claim, claimId), 5L);
        });
    }

    private void openBanInput() {
        player.closeInventory();
        String[] lines = {"", "Ban Player:", "Enter name", ""};
        new SignInputGUI(plugin, player, lines,
            playerName -> plugin.runAtEntity(player, () -> {
                runClaimCommand("ban", playerName, claimId);
                runLater(() -> BannedPlayersGUI.openAsync(manager, player, claim, claimId), 5L);
            }),
            () -> runLater(() -> manager.openBannedPlayers(player, claim, claimId), 1L)
        ).open();
    }

    private static final class BannedPlayerInfo {
        final UUID playerId;
        final String playerName;
        final long lastSeen;

        private BannedPlayerInfo(UUID playerId, String playerName, long lastSeen) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.lastSeen = lastSeen;
        }
    }
}
