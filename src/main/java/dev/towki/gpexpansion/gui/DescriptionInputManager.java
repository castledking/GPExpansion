package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages chat-based description input for global claim settings.
 */
public class DescriptionInputManager {
    
    private static class Pending {
        final String claimId;
        final boolean fromSign;
        
        Pending(String claimId, boolean fromSign) {
            this.claimId = claimId;
            this.fromSign = fromSign;
        }
    }
    
    private final GPExpansionPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final GPBridge gp = new GPBridge();
    
    public DescriptionInputManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }
    
    public boolean hasPending(UUID playerId) {
        return pending.containsKey(playerId);
    }
    
    public void begin(Player player, String claimId, boolean fromSign) {
        pending.put(player.getUniqueId(), new Pending(claimId, fromSign));
        plugin.getMessages().send(player, "gui.description-prompt", "{id}", claimId);
    }
    
    public boolean processInput(Player player, String input) {
        Pending entry = pending.remove(player.getUniqueId());
        if (entry == null) return false;
        
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("cancel")) {
            plugin.getMessages().send(player, "gui.description-cancelled", "{id}", entry.claimId);
            reopenGlobalSettings(player, entry.claimId, entry.fromSign);
            return true;
        }
        
        String description = trimmed;
        if (description.length() > 32) {
            description = description.substring(0, 32);
        }
        
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        dataStore.setDescription(entry.claimId, description);
        dataStore.save();
        plugin.getMessages().send(player, "gui.description-set", "{id}", entry.claimId);
        reopenGlobalSettings(player, entry.claimId, entry.fromSign);
        return true;
    }
    
    public void cancel(UUID playerId) {
        pending.remove(playerId);
    }
    
    private void reopenGlobalSettings(Player player, String claimId, boolean fromSign) {
        if (plugin.getGUIManager() == null) return;
        Optional<Object> claimOpt = gp.findClaimById(claimId);
        claimOpt.ifPresent(claim -> plugin.getGUIManager().openGlobalClaimSettings(player, claim, claimId, fromSign));
    }
}
