package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.BanStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * GUI to display and manage banned players for claims.
 */
public class BannedPlayersGUI extends BaseGUI {
    
    private static final int[] PLAYER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    
    private static final int PREV_PAGE_SLOT = 45;
    private static final int FILTER_SLOT = 49;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int BAN_PLAYER_SLOT = 47;
    private static final int BACK_SLOT = 51;
    
    private final GPBridge gp;
    private final BanStore banStore;
    
    // Original claim context (for single claim view and back button)
    private final Object originClaim;
    private final String originClaimId;
    
    private FilterMode filterMode = FilterMode.SINGLE_CLAIM;
    private int currentPage = 0;
    private List<BannedPlayerInfo> bannedPlayers = new ArrayList<>();
    
    public enum FilterMode {
        SINGLE_CLAIM("Single Claim", Material.GRASS_BLOCK),
        SUBDIVISIONS_ONLY("Subdivisions Only", Material.STONE),
        ALL_CLAIMS("All My Claims", Material.DIAMOND);
        
        private final String displayName;
        private final Material icon;
        
        FilterMode(String displayName, Material icon) {
            this.displayName = displayName;
            this.icon = icon;
        }
        
        public String getDisplayName() { return displayName; }
        public Material getIcon() { return icon; }
        
        public FilterMode next() {
            FilterMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }
    
    public BannedPlayersGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "banned-players");
        this.gp = new GPBridge();
        this.banStore = plugin.getBanStore();
        this.originClaim = claim;
        this.originClaimId = claimId;
    }
    
    @Override
    public Inventory createInventory() {
        inventory = createBaseInventory("Banned Players", 54);
        loadBannedPlayers();
        populateInventory();
        return inventory;
    }
    
    private void loadBannedPlayers() {
        bannedPlayers.clear();
        
        switch (filterMode) {
            case SINGLE_CLAIM:
                loadBannedForClaim(originClaimId, originClaim);
                break;
            case SUBDIVISIONS_ONLY:
                // Load from all subdivisions of player's claims
                for (Object claim : gp.getClaimsFor(player)) {
                    for (Object subclaim : gp.getSubclaims(claim)) {
                        gp.getClaimId(subclaim).ifPresent(id -> loadBannedForClaim(id, subclaim));
                    }
                }
                break;
            case ALL_CLAIMS:
                // Load from all player's claims and subdivisions
                for (Object claim : gp.getClaimsFor(player)) {
                    gp.getClaimId(claim).ifPresent(id -> loadBannedForClaim(id, claim));
                    for (Object subclaim : gp.getSubclaims(claim)) {
                        gp.getClaimId(subclaim).ifPresent(id -> loadBannedForClaim(id, subclaim));
                    }
                }
                break;
        }
        
        // Sort by player name
        bannedPlayers.sort(Comparator.comparing(info -> info.playerName.toLowerCase()));
    }
    
    @SuppressWarnings("deprecation")
    private void loadBannedForClaim(String claimId, Object claim) {
        BanStore.BanEntry entry = banStore.get(claimId);
        if (entry == null || entry.players.isEmpty()) return;
        
        String claimName = plugin.getNameStore().get(claimId).orElse("Claim #" + claimId);
        boolean isSubdivision = gp.isSubdivision(claim);
        
        for (UUID playerId : entry.players) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            String playerName = entry.names.getOrDefault(playerId, 
                offlinePlayer.getName() != null ? offlinePlayer.getName() : playerId.toString().substring(0, 8));
            
            bannedPlayers.add(new BannedPlayerInfo(
                playerId,
                playerName,
                claimId,
                claimName,
                isSubdivision,
                offlinePlayer.getLastPlayed()
            ));
        }
    }
    
    private void populateInventory() {
        inventory.clear();
        
        // Fill border
        fillBorder(createFiller());
        
        // Navigation
        inventory.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        inventory.setItem(FILTER_SLOT, createFilterItem());
        inventory.setItem(BAN_PLAYER_SLOT, createBanPlayerItem());
        inventory.setItem(BACK_SLOT, createBackItem());
        
        // Populate player heads
        int startIndex = currentPage * PLAYER_SLOTS.length;
        for (int i = 0; i < PLAYER_SLOTS.length && startIndex + i < bannedPlayers.size(); i++) {
            BannedPlayerInfo info = bannedPlayers.get(startIndex + i);
            inventory.setItem(PLAYER_SLOTS[i], createPlayerHead(info));
        }
    }
    
    private ItemStack createPlayerHead(BannedPlayerInfo info) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            if (info.playerId != null) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(info.playerId));
            }
            meta.displayName(colorize("&c" + info.playerName));
            
            List<String> lore = new ArrayList<>();
            lore.add("&7Banned from: &f" + info.claimName);
            lore.add("&7Claim ID: &f" + info.claimId);
            lore.add("&7Type: &f" + (info.isSubdivision ? "Subdivision" : "Main Claim"));
            if (info.lastSeen > 0) {
                long daysSince = (System.currentTimeMillis() - info.lastSeen) / (1000 * 60 * 60 * 24);
                lore.add("&7Last seen: &f" + (daysSince == 0 ? "Today" : daysSince + " days ago"));
            }
            lore.add("");
            lore.add("&e▸ Click to unban player");
            
            List<net.kyori.adventure.text.Component> loreCmp = new ArrayList<>();
            for (String line : lore) {
                loreCmp.add(colorize(line));
            }
            meta.lore(loreCmp);
            head.setItemMeta(meta);
        }
        return head;
    }
    
    private ItemStack createFilterItem() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Current: &f" + filterMode.getDisplayName());
        lore.add("");
        for (FilterMode mode : FilterMode.values()) {
            String prefix = mode == filterMode ? "&a▸ " : "&7  ";
            lore.add(prefix + mode.getDisplayName());
        }
        lore.add("");
        lore.add("&eClick to cycle filter");
        
        return createItem(Material.HOPPER, "&6&lFilter View", lore);
    }
    
    private ItemStack createBanPlayerItem() {
        String claimName = plugin.getNameStore().get(originClaimId).orElse("Claim #" + originClaimId);
        boolean isSubdivision = gp.isSubdivision(originClaim);
        String type = isSubdivision ? "subdivision" : "claim";
        
        List<String> lore = List.of(
            "&7Ban a player from this " + type,
            "&7Claim: &f" + claimName,
            "",
            "&eClick to ban a player"
        );
        
        return createItem(Material.BARRIER, "&c&lBan Player", lore);
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
        return createItem(Material.DARK_OAK_DOOR, "&c&lBack", List.of("&7Return to claim settings"));
    }
    
    private int getMaxPage() {
        return Math.max(1, (int) Math.ceil(bannedPlayers.size() / (double) PLAYER_SLOTS.length));
    }
    
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();
        
        if (slot == FILTER_SLOT) {
            filterMode = filterMode.next();
            currentPage = 0;
            loadBannedPlayers();
            populateInventory();
            return;
        }
        
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
        
        if (slot == BAN_PLAYER_SLOT) {
            // Open sign input for banning
            player.closeInventory();
            String[] lines = {"", "Ban Player:", "Enter name", ""};
            new SignInputGUI(plugin, player, lines,
                playerName -> {
                    player.performCommand("claim ban " + originClaimId + " " + playerName);
                    runLater(() -> manager.openBannedPlayers(player, originClaim, originClaimId), 5L);
                },
                () -> manager.openBannedPlayers(player, originClaim, originClaimId)).open();
            return;
        }
        
        if (slot == BACK_SLOT) {
            manager.openClaimSettings(player, originClaim, originClaimId);
            return;
        }
        
        // Check if clicking on a player head
        int slotIndex = -1;
        for (int i = 0; i < PLAYER_SLOTS.length; i++) {
            if (PLAYER_SLOTS[i] == slot) {
                slotIndex = i;
                break;
            }
        }
        
        if (slotIndex >= 0) {
            int playerIndex = currentPage * PLAYER_SLOTS.length + slotIndex;
            if (playerIndex < bannedPlayers.size()) {
                BannedPlayerInfo info = bannedPlayers.get(playerIndex);
                // Unban the player
                player.performCommand("claim unban " + info.claimId + " " + info.playerName);
                // Refresh after a short delay
                runLater(() -> {
                    loadBannedPlayers();
                    populateInventory();
                }, 5L);
            }
        }
    }
    
    private static class BannedPlayerInfo {
        final UUID playerId;
        final String playerName;
        final String claimId;
        final String claimName;
        final boolean isSubdivision;
        final long lastSeen;
        
        BannedPlayerInfo(UUID playerId, String playerName, String claimId, String claimName, boolean isSubdivision, long lastSeen) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.claimId = claimId;
            this.claimName = claimName;
            this.isSubdivision = isSubdivision;
            this.lastSeen = lastSeen;
        }
    }
}
