package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.command.ClaimCommand;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
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
            if (plugin.isGp3dClaimMode()) {
                // GP3D present: only intercept our subcommands, let GP3D handle the rest (e.g. /claim help)
                String sub = noSlash.length() > 5 ? noSlash.substring(5).trim().split("\\s+")[0].toLowerCase() : "";
                boolean weHandle = sub.isEmpty() || ClaimCommand.HANDLED_SUBCOMMANDS.contains(sub);
                if (weHandle) {
                    ClaimCommand claimCmd = plugin.getClaimCommand();
                    if (claimCmd != null) {
                        String[] args = noSlash.length() > 5 ? noSlash.substring(5).trim().split("\\s+", -1) : new String[0];
                        Command stub = new Command("claim") {
                            @Override
                            public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                                return false;
                            }
                        };
                        event.setCancelled(true);
                        claimCmd.onCommand(event.getPlayer(), stub, "claim", args);
                    }
                }
            } else {
                // GPExpansion owns /claim: reroute to our namespaced handler
                String rest = noSlash.length() > 5 ? noSlash.substring(5).trim() : "";
                String reroute = "gpexpansion:claim" + (rest.isEmpty() ? "" : " " + rest);
                event.setCancelled(true);
                Bukkit.dispatchCommand(event.getPlayer(), reroute);
            }
            return;
        }

        // Support overriding /claimlist and /claimslist by rerouting to our /claim list
        if (lower.equals("claimlist") || lower.startsWith("claimlist ") || lower.equals("claimslist") || lower.startsWith("claimslist ")) {
            int prefixLen = lower.startsWith("claimslist") ? 10 : 9;
            ClaimCommand claimCmd = plugin.getClaimCommand();
            if (claimCmd != null) {
                event.setCancelled(true);
                String rest = noSlash.length() > prefixLen ? noSlash.substring(prefixLen).trim() : "";
                String[] args = rest.isEmpty() ? new String[]{"list"} : ("list " + rest).split("\\s+", -1);
                Command stub = new Command("claimlist") {
                    @Override
                    public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] a) {
                        return false;
                    }
                };
                claimCmd.onCommand(event.getPlayer(), stub, "claimlist", args);
            } else if (!plugin.isGp3dClaimMode()) {
                // GPExpansion owns /claim: reroute to namespaced handler
                event.setCancelled(true);
                String rest = noSlash.length() > prefixLen ? noSlash.substring(prefixLen).trim() : "";
                Bukkit.dispatchCommand(event.getPlayer(), "gpexpansion:claim list" + (rest.isEmpty() ? "" : " " + rest));
            }
            return;
        }

        // Support overriding /adminclaimlist and /adminclaimslist with our enhanced display (ID + name)
        if (lower.equals("adminclaimlist") || lower.startsWith("adminclaimlist ") || lower.equals("adminclaimslist") || lower.startsWith("adminclaimslist ")) {
            int prefixLen = lower.startsWith("adminclaimslist") ? 15 : 14;
            ClaimCommand claimCmd = plugin.getClaimCommand();
            if (claimCmd != null) {
                event.setCancelled(true);
                String rest = noSlash.length() > prefixLen ? noSlash.substring(prefixLen).trim() : "";
                String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+", -1);
                Command stub = new Command("adminclaimlist") {
                    @Override
                    public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] a) {
                        return false;
                    }
                };
                claimCmd.onCommand(event.getPlayer(), stub, "adminclaimlist", args);
            }
            return;
        }

        // Intercept untrust commands to prevent untrusting renters
        if (lower.equals("untrust") || lower.startsWith("untrust ")) {
            if (interceptUntrustCommand(event)) {
                return;
            }
        }
    }

    /**
     * When gp3dClaimMode, GP3D owns /claim - do NOT inject our tab completions.
     * Our completions would overwrite GP3D's (e.g. abandon toplevel, claim-specific suggestions).
     */

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
