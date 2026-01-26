package dev.towki.gpexpansion.gui;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last GUI state for each player to allow returning to previous menus.
 * Used by /claim ! command.
 */
public class GUIStateTracker {
    
    private static final Map<UUID, GUIState> lastStates = new ConcurrentHashMap<>();
    
    /**
     * Represents a saved GUI state that can be restored.
     */
    public static class GUIState {
        public final GUIType type;
        public final String searchQuery;
        public final String filterType;
        public final int page;
        public final String claimId; // For claim-specific GUIs
        
        public GUIState(GUIType type, String searchQuery, String filterType, int page, String claimId) {
            this.type = type;
            this.searchQuery = searchQuery;
            this.filterType = filterType;
            this.page = page;
            this.claimId = claimId;
        }
    }
    
    /**
     * Types of GUIs that can be tracked and restored.
     */
    public enum GUIType {
        MAIN_MENU,
        OWNED_CLAIMS,
        TRUSTED_CLAIMS,
        ALL_PLAYER_CLAIMS,
        ADMIN_CLAIMS,
        GLOBAL_CLAIM_LIST,
        CLAIM_OPTIONS,
        BANNED_PLAYERS,
        CHILDREN_CLAIMS,
        CLAIM_SETTINGS,
        SETUP_WIZARDS,
        CLAIM_FLAGS
    }
    
    /**
     * Save the current GUI state for a player.
     */
    public static void saveState(Player player, GUIType type, String searchQuery, String filterType, int page, String claimId) {
        lastStates.put(player.getUniqueId(), new GUIState(type, searchQuery, filterType, page, claimId));
    }
    
    /**
     * Save state with just GUI type (for simple menus).
     */
    public static void saveState(Player player, GUIType type) {
        saveState(player, type, null, null, 0, null);
    }
    
    /**
     * Save state for list GUIs with search/filter.
     */
    public static void saveState(Player player, GUIType type, String searchQuery, String filterType, int page) {
        saveState(player, type, searchQuery, filterType, page, null);
    }
    
    /**
     * Get the last saved state for a player.
     */
    public static GUIState getLastState(UUID playerId) {
        return lastStates.get(playerId);
    }
    
    /**
     * Check if player has a saved state.
     */
    public static boolean hasState(UUID playerId) {
        return lastStates.containsKey(playerId);
    }
    
    /**
     * Clear the saved state for a player.
     */
    public static void clearState(UUID playerId) {
        lastStates.remove(playerId);
    }
    
    /**
     * Restore and open the last GUI for a player.
     * Returns true if successful, false if no state was saved.
     */
    public static boolean restoreLastGUI(GUIManager manager, Player player) {
        GUIState state = lastStates.get(player.getUniqueId());
        if (state == null) {
            return false;
        }
        
        switch (state.type) {
            case MAIN_MENU:
                manager.openMainMenu(player);
                break;
                
            case OWNED_CLAIMS:
                OwnedClaimsGUI.openAsyncWithState(manager, player, state.searchQuery, state.filterType, state.page);
                break;
                
            case TRUSTED_CLAIMS:
                manager.openTrustedClaims(player);
                break;
                
            case ALL_PLAYER_CLAIMS:
                AllPlayerClaimsGUI.openAsyncWithState(manager, player, state.searchQuery, state.filterType, state.page);
                break;
                
            case ADMIN_CLAIMS:
                AdminClaimsGUI.openAsyncWithState(manager, player, state.searchQuery, state.filterType, state.page);
                break;
                
            case GLOBAL_CLAIM_LIST:
                GlobalClaimListGUI.openAsyncWithState(manager, player, state.searchQuery, state.filterType, state.page);
                break;
                
            case CLAIM_OPTIONS:
                if (state.claimId != null) {
                    new dev.towki.gpexpansion.gp.GPBridge().findClaimById(state.claimId).ifPresent(claim -> {
                        manager.openClaimSettings(player, claim, state.claimId);
                    });
                }
                break;
                
            case BANNED_PLAYERS:
                if (state.claimId != null) {
                    new dev.towki.gpexpansion.gp.GPBridge().findClaimById(state.claimId).ifPresent(claim -> {
                        BannedPlayersGUI.openAsyncWithState(manager, player, claim, state.claimId, state.searchQuery, state.page);
                    });
                }
                break;
                
            case CHILDREN_CLAIMS:
                if (state.claimId != null) {
                    new dev.towki.gpexpansion.gp.GPBridge().findClaimById(state.claimId).ifPresent(claim -> {
                        manager.openChildrenClaims(player, claim, state.claimId);
                    });
                }
                break;
                
            case CLAIM_SETTINGS:
                if (state.claimId != null) {
                    new dev.towki.gpexpansion.gp.GPBridge().findClaimById(state.claimId).ifPresent(claim -> {
                        manager.openClaimSettings(player, claim, state.claimId);
                    });
                }
                break;
                
            case SETUP_WIZARDS:
                if (state.claimId != null) {
                    new dev.towki.gpexpansion.gp.GPBridge().findClaimById(state.claimId).ifPresent(claim -> {
                        manager.openSetupWizards(player, claim, state.claimId);
                    });
                }
                break;
                
            case CLAIM_FLAGS:
                if (state.claimId != null) {
                    new dev.towki.gpexpansion.gp.GPBridge().findClaimById(state.claimId).ifPresent(claim -> {
                        manager.openClaimFlags(player, claim, state.claimId);
                    });
                }
                break;
                
            default:
                return false;
        }
        
        return true;
    }
}
