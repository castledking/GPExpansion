package codes.castled.gpexpansion.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import codes.castled.gpexpansion.gp.GPBridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Head-based trust list for a single claim.
 */
public class ClaimTrustedPlayersGUI extends BaseGUI {

    private static final int[] PLAYER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34
    };

    private static final int PREV_PAGE_SLOT = 45;
    private static final int ADD_TRUST_SLOT = 47;
    private static final int BANNED_PLAYERS_SLOT = 49;
    private static final int BACK_SLOT = 51;
    private static final int NEXT_PAGE_SLOT = 53;

    private final Object claim;
    private final String claimId;
    private final GPBridge gp;
    private final codes.castled.gpexpansion.storage.ClaimDataStore claimDataStore;
    private final List<TrustedPlayerInfo> trustedPlayers = new ArrayList<>();
    private int currentPage = 0;

    public ClaimTrustedPlayersGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "claim-trusted-players");
        this.claim = claim;
        this.claimId = claimId;
        this.gp = new GPBridge();
        this.claimDataStore = plugin.getClaimDataStore();
    }

    @Override
    public Inventory createInventory() {
        String title = getString("title", "&6&lTrusted Players - #{id}").replace("{id}", claimId);
        inventory = createBaseInventoryWithTitle(title, 54);
        GUIStateTracker.saveState(player, GUIStateTracker.GUIType.CLAIM_TRUSTED_PLAYERS, null, null, currentPage, claimId);
        loadTrustedPlayers();
        populateInventory();
        return inventory;
    }

    private void loadTrustedPlayers() {
        trustedPlayers.clear();

        Map<UUID, EnumSet<GPBridge.TrustLevel>> trusted = gp.getTrustedPlayers(claim);
        Map<UUID, String> cachedNames = claimDataStore.getTrustedPlayerNames(claimId);
        Set<UUID> allPlayers = new LinkedHashSet<>(claimDataStore.getTrustedPlayers(claimId));
        allPlayers.addAll(trusted.keySet());

        boolean cacheChanged = false;
        for (UUID playerId : allPlayers) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            String playerName = cachedNames.get(playerId);
            if (playerName == null || playerName.isBlank()) {
                playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerId.toString().substring(0, 8);
            }

            EnumSet<GPBridge.TrustLevel> levels = trusted.getOrDefault(playerId, EnumSet.noneOf(GPBridge.TrustLevel.class));
            trustedPlayers.add(new TrustedPlayerInfo(playerId, playerName, levels, offlinePlayer.getLastPlayed()));

            if (!claimDataStore.getTrustedPlayers(claimId).contains(playerId) && !levels.isEmpty()) {
                claimDataStore.addTrustedPlayer(claimId, playerId, playerName);
                cacheChanged = true;
            }
        }

        if (cacheChanged) {
            claimDataStore.save();
        }

        trustedPlayers.sort(Comparator.comparing(info -> info.playerName.toLowerCase()));
        currentPage = Math.min(currentPage, getMaxPage() - 1);
    }

    private void populateInventory() {
        inventory.clear();
        fillBorder(createFiller());

        inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        inventory.setItem(ADD_TRUST_SLOT, createAddTrustedPlayerItem());
        inventory.setItem(BANNED_PLAYERS_SLOT, createBannedPlayersItem());
        inventory.setItem(BACK_SLOT, createBackItem());
        inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());

        int startIndex = currentPage * PLAYER_SLOTS.length;
        for (int i = 0; i < PLAYER_SLOTS.length && startIndex + i < trustedPlayers.size(); i++) {
            inventory.setItem(PLAYER_SLOTS[i], createPlayerHead(trustedPlayers.get(startIndex + i)));
        }

        if (trustedPlayers.isEmpty()) {
            inventory.setItem(22, createItem(Material.BOOK, "&e&lNo Trusted Players", List.of(
                "&7No explicit trusted players were found on this claim.",
                "",
                "&eUse Add Trusted Player to create one."
            )));
        }
    }

    private ItemStack createPlayerHead(TrustedPlayerInfo info) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(info.playerId));
            meta.displayName(colorize("&e" + info.playerName));

            List<String> lore = new ArrayList<>();
            lore.add("&7Claim ID: &f" + claimId);
            lore.add("&7Trust:");
            if (info.levels.isEmpty()) {
                lore.add("&7- &8No permissions");
            } else {
                if (info.levels.contains(GPBridge.TrustLevel.MANAGE)) lore.add("&7- &6Manage");
                if (info.levels.contains(GPBridge.TrustLevel.BUILD)) lore.add("&7- &eBuild");
                if (info.levels.contains(GPBridge.TrustLevel.CONTAINERS)) lore.add("&7- &aContainers");
                if (info.levels.contains(GPBridge.TrustLevel.ACCESS)) lore.add("&7- &9Access");
            }
            if (info.lastSeen > 0) {
                long daysSince = Math.max(0L, (System.currentTimeMillis() - info.lastSeen) / (1000L * 60L * 60L * 24L));
                lore.add("");
                lore.add("&7Last seen: &f" + (daysSince == 0 ? "Today" : daysSince + " days ago"));
            }
            lore.add("");
            lore.add("&eClick to edit trust");
            lore.add("&cShift-click to remove from claim and list");

            List<net.kyori.adventure.text.Component> loreComponents = new ArrayList<>();
            for (String line : lore) {
                loreComponents.add(colorize(line));
            }
            meta.lore(loreComponents);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack createAddTrustedPlayerItem() {
        return createItem(Material.PLAYER_HEAD, "&a&lAdd Trusted Player", List.of(
            "&7Open sign input to enter a player name.",
            "",
            "&eClick to add or edit trust"
        ));
    }

    private ItemStack createBannedPlayersItem() {
        return createItem(Material.BARRIER, "&c&lBanned Players", List.of(
            "&7Open the banned players list for this claim.",
            "",
            "&eClick to manage bans"
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
        return createItem(Material.ARROW, "&c&lBack", List.of("&7Return to claim options"));
    }

    private int getMaxPage() {
        return Math.max(1, (int) Math.ceil(trustedPlayers.size() / (double) PLAYER_SLOTS.length));
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
        if (slot == ADD_TRUST_SLOT) {
            openAddTrustedPlayerInput();
            return;
        }
        if (slot == BANNED_PLAYERS_SLOT) {
            manager.openBannedPlayers(player, claim, claimId);
            return;
        }
        if (slot == BACK_SLOT) {
            manager.openClaimOptions(player, claim, claimId);
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
        if (playerIndex >= trustedPlayers.size()) {
            return;
        }

        TrustedPlayerInfo info = trustedPlayers.get(playerIndex);
        if (isShiftLeftClick(event) || isShiftRightClick(event)) {
            hardRemovePlayer(info);
            return;
        }

        manager.openClaimTrustEditor(player, claim, claimId, info.playerName, info.playerId);
    }

    private void openAddTrustedPlayerInput() {
        player.closeInventory();
        String[] lines = {"", "Trusted Player:", "Enter name", ""};
        new SignInputGUI(plugin, player, lines,
            playerName -> runLater(() -> manager.openClaimTrustEditor(player, claim, claimId, playerName), 1L),
            () -> runLater(() -> manager.openClaimTrustedPlayers(player, claim, claimId), 1L)
        ).open();
    }

    private void hardRemovePlayer(TrustedPlayerInfo info) {
        closeAndRunOnMainThread(() -> {
            runClaimCommand("untrust", info.playerName, claimId);
            claimDataStore.removeTrustedPlayer(claimId, info.playerId);
            claimDataStore.save();
            runLater(() -> manager.openClaimTrustedPlayers(player, claim, claimId), 5L);
        });
    }

    private static final class TrustedPlayerInfo {
        final UUID playerId;
        final String playerName;
        final EnumSet<GPBridge.TrustLevel> levels;
        final long lastSeen;

        private TrustedPlayerInfo(UUID playerId, String playerName, EnumSet<GPBridge.TrustLevel> levels, long lastSeen) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.levels = levels.clone();
            this.lastSeen = lastSeen;
        }
    }
}
