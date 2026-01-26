package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Optional;
import java.util.UUID;

public class CommandInterceptListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final GPBridge gp = new GPBridge();

    public CommandInterceptListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreprocess(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (msg == null) return;
        if (!msg.startsWith("/")) return;
        String noSlash = msg.substring(1);
        // Ignore if explicitly namespaced already
        if (noSlash.startsWith("gpexpansion:claim")) return;

        String lower = noSlash.toLowerCase();
        if (lower.equals("claim") || lower.startsWith("claim ")) {
            // Reroute to our namespaced /claim
            String rest = noSlash.length() > 5 ? noSlash.substring(5).trim() : "";
            String reroute = "gpexpansion:claim" + (rest.isEmpty() ? "" : " " + rest);
            event.setCancelled(true);
            Bukkit.dispatchCommand(event.getPlayer(), reroute);
            return;
        }

        // Support overriding /claimlist by rerouting to our /claim list
        if (lower.equals("claimlist") || lower.startsWith("claimlist ")) {
            String rest = noSlash.length() > 9 ? noSlash.substring(9).trim() : "";
            String reroute = "gpexpansion:claim list" + (rest.isEmpty() ? "" : " " + rest);
            event.setCancelled(true);
            Bukkit.dispatchCommand(event.getPlayer(), reroute);
            return;
        }

        // Intercept untrust commands to prevent untrusting renters
        if (lower.equals("untrust") || lower.startsWith("untrust ")) {
            if (interceptUntrustCommand(event)) {
                return;
            }
        }
    }

    private boolean interceptUntrustCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage();

        // Parse the untrust command to get the target player name
        String[] parts = command.split("\\s+");
        if (parts.length < 2) return false; // Not enough arguments

        String targetName = parts[1];

        // Resolve target player UUID
        UUID targetUuid = null;
        Player targetPlayer = Bukkit.getPlayerExact(targetName);
        if (targetPlayer != null) {
            targetUuid = targetPlayer.getUniqueId();
        } else {
            OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
            if (offlineTarget != null) {
                targetUuid = offlineTarget.getUniqueId();
            }
        }

        if (targetUuid == null) return false; // Unknown player

        // Check if the target is renting any claims owned by this player
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        for (var entry : dataStore.getAllRentals().entrySet()) {
            String claimId = entry.getKey();
            ClaimDataStore.RentalData rental = entry.getValue();
            if (rental != null && rental.renter.equals(targetUuid)) {
                    // Check if this player owns the claim
                    Optional<Object> claimOpt = gp.findClaimById(claimId);
                    if (claimOpt.isPresent()) {
                        Object claim = claimOpt.get();
                        try {
                            Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
                            if (ownerId != null && ownerId.equals(player.getUniqueId())) {
                                // Player owns this claim and target is renting it
                                event.setCancelled(true);
                                OfflinePlayer renter = Bukkit.getOfflinePlayer(targetUuid);
                                String renterName = renter.getName() != null ? renter.getName() : targetName;

                                plugin.getMessages().send(player, "claim.untrust-renter",
                                        "{renter}", renterName);
                                plugin.getMessages().send(player, "claim.untrust-renter-hint",
                                        "{command}", "/claim evict " + renterName);
                                return true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        return false; // Allow the command to proceed
    }
}
