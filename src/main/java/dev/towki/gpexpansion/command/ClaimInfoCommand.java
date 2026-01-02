package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.NameStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /claiminfo [id] - Shows detailed information about a claim
 */
public class ClaimInfoCommand implements CommandExecutor, TabCompleter {
    
    private final GPExpansionPlugin plugin;
    private final GPBridge gp;
    private final NameStore nameStore;
    
    public ClaimInfoCommand(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.gp = new GPBridge();
        this.nameStore = plugin.getNameStore();
    }
    
    private UUID getClaimOwnerUUID(Object claim) {
        if (claim == null) return null;
        try {
            java.lang.reflect.Method getOwnerID = claim.getClass().getMethod("getOwnerID");
            return (UUID) getOwnerID.invoke(claim);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().get("general.player-only"));
            return true;
        }
        
        if (!player.hasPermission("griefprevention.claiminfo")) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }
        
        Object claim = null;
        String claimId = null;
        
        if (args.length == 0) {
            // Use claim at player's location
            Optional<Object> claimOpt = gp.getClaimAt(player.getLocation());
            if (!claimOpt.isPresent()) {
                player.sendMessage(Component.text("You are not standing in a claim.", NamedTextColor.RED));
                return true;
            }
            claim = claimOpt.get();
            claimId = gp.getClaimId(claim).orElse("?");
        } else {
            // Use provided claim ID
            claimId = args[0];
            Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (!claimOpt.isPresent()) {
                player.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", claimId));
                return true;
            }
            claim = claimOpt.get();
        }
        
        // Check ownership permission
        boolean isOwner = gp.isOwner(claim, player.getUniqueId());
        boolean canViewOthers = player.hasPermission("griefprevention.claiminfo.other") || player.isOp();
        
        if (!isOwner && !canViewOthers) {
            player.sendMessage(Component.text("You don't have permission to view info for claims you don't own.", NamedTextColor.RED));
            return true;
        }
        
        // Display claim info
        displayClaimInfo(player, claim, claimId);
        return true;
    }
    
    private void displayClaimInfo(Player player, Object claim, String claimId) {
        // Get claim details
        String claimName = nameStore.get(claimId).orElse(null);
        UUID ownerUuid = getClaimOwnerUUID(claim);
        String ownerName = "Unknown";
        if (ownerUuid != null) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUuid);
            ownerName = owner.getName() != null ? owner.getName() : ownerUuid.toString();
        }
        
        String worldName = gp.getClaimWorld(claim).orElse("unknown");
        int[] dimensions = gp.getClaimDimensions(claim);
        int area = gp.getClaimArea(claim);
        
        // Get center coordinates
        String coords = gp.getClaimCorners(claim)
            .map(corners -> {
                int centerX = (corners.x1 + corners.x2) / 2;
                int centerZ = (corners.z1 + corners.z2) / 2;
                return "x" + centerX + ", z" + centerZ;
            })
            .orElse("unknown");
        
        // Determine claim type
        String claimType = gp.isSubdivision(claim) ? "Subdivision" : "Main Claim";
        if (gp.isAdminClaim(claim)) {
            claimType = "Admin Claim";
        }
        
        // Send header
        player.sendMessage(plugin.getMessages().get("claim.info-header"));
        
        // Basic info
        player.sendMessage(plugin.getMessages().get("claim.info-id", "{id}", claimId));
        
        if (claimName != null && !claimName.isEmpty()) {
            player.sendMessage(plugin.getMessages().get("claim.info-name", "{name}", claimName));
        }
        
        player.sendMessage(plugin.getMessages().get("claim.info-owner", "{owner}", ownerName));
        player.sendMessage(plugin.getMessages().get("claim.info-world", "{world}", worldName));
        player.sendMessage(plugin.getMessages().get("claim.info-location", "{x}", coords.split(", ")[0].replace("x", ""), "{z}", coords.split(", ")[1].replace("z", "")));
        
        // Size info
        String sizeStr = dimensions[0] + " x " + dimensions[2] + " (" + area + " blocks)";
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
            "&7Size: &e" + sizeStr));
        
        player.sendMessage(plugin.getMessages().get("claim.info-type", "{type}", claimType));
        
        // Check for sign usage (rent/sell/mailbox)
        checkSignUsage(player, claim, claimId);
        
        // Show subclaim info if this is a parent claim
        if (!gp.isSubdivision(claim)) {
            List<Object> subclaims = gp.getSubclaims(claim);
            if (!subclaims.isEmpty()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&7Subdivisions: &e" + subclaims.size()));
            }
        } else {
            // Show parent claim ID
            gp.getParentClaim(claim).ifPresent(parent -> {
                String parentId = gp.getClaimId(parent).orElse("?");
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&7Parent Claim: &e" + parentId));
            });
        }
    }
    
    private void checkSignUsage(Player player, Object claim, String claimId) {
        // Check if this claim has any associated signs by searching for signs in the claim
        // We'll check the claim's world and boundaries
        Optional<GPBridge.ClaimCorners> cornersOpt = gp.getClaimCorners(claim);
        if (!cornersOpt.isPresent()) return;
        
        GPBridge.ClaimCorners corners = cornersOpt.get();
        String worldName = gp.getClaimWorld(claim).orElse(null);
        if (worldName == null) return;
        
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        
        // Track what sign types we find
        boolean hasRentSign = false;
        boolean hasSellSign = false;
        boolean hasMailboxSign = false;
        String rentStatus = null;
        String sellStatus = null;
        
        // Search for signs within the claim bounds (limit to surface level search for performance)
        int minY = Math.max(corners.y1, world.getMinHeight());
        int maxY = Math.min(corners.y2, world.getMaxHeight());
        
        // Limit search area for large claims
        int searchRadius = Math.min(50, Math.max(corners.x2 - corners.x1, corners.z2 - corners.z1));
        int centerX = (corners.x1 + corners.x2) / 2;
        int centerZ = (corners.z1 + corners.z2) / 2;
        
        int startX = Math.max(corners.x1, centerX - searchRadius);
        int endX = Math.min(corners.x2, centerX + searchRadius);
        int startZ = Math.max(corners.z1, centerZ - searchRadius);
        int endZ = Math.min(corners.z2, centerZ + searchRadius);
        
        NamespacedKey keyKind = new NamespacedKey(plugin, "sign.kind");
        NamespacedKey keyClaim = new NamespacedKey(plugin, "sign.claimId");
        
        // Search through claim looking for signs
        searchLoop:
        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getState() instanceof Sign sign) {
                        PersistentDataContainer pdc = sign.getPersistentDataContainer();
                        String signKind = pdc.get(keyKind, PersistentDataType.STRING);
                        String signClaimId = pdc.get(keyClaim, PersistentDataType.STRING);
                        
                        // Check if this sign references our claim
                        if (signClaimId != null && signClaimId.equals(claimId)) {
                            if ("RENT".equals(signKind)) {
                                hasRentSign = true;
                                // Check if currently rented
                                String renter = pdc.get(new NamespacedKey(plugin, "sign.renter"), PersistentDataType.STRING);
                                if (renter != null && !renter.isEmpty()) {
                                    OfflinePlayer renterPlayer = Bukkit.getOfflinePlayer(UUID.fromString(renter));
                                    rentStatus = "Rented by " + (renterPlayer.getName() != null ? renterPlayer.getName() : "Unknown");
                                } else {
                                    rentStatus = "Available for rent";
                                }
                            } else if ("SELL".equals(signKind)) {
                                hasSellSign = true;
                                sellStatus = "Listed for sale";
                            } else if ("MAILBOX".equals(signKind)) {
                                hasMailboxSign = true;
                            }
                        }
                        
                        // Early exit if we found all types
                        if (hasRentSign && hasSellSign && hasMailboxSign) {
                            break searchLoop;
                        }
                    }
                }
            }
        }
        
        // Display sign usage info
        if (hasRentSign || hasSellSign || hasMailboxSign) {
            player.sendMessage(Component.text(""));
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&6=== Sign Usage ==="));
            
            if (hasRentSign) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&7Rent Sign: &a" + (rentStatus != null ? rentStatus : "Yes")));
            }
            if (hasSellSign) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&7Sell Sign: &a" + (sellStatus != null ? sellStatus : "Yes")));
            }
            if (hasMailboxSign) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&7Mailbox Sign: &aYes"));
            }
        }
    }
    
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("griefprevention.claiminfo")) {
            return new ArrayList<>();
        }
        
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            
            // If player, suggest their own claim IDs
            if (sender instanceof Player player) {
                List<Object> claims = gp.getClaimsFor(player);
                for (Object claim : claims) {
                    gp.getClaimId(claim).ifPresent(id -> {
                        if (id.toLowerCase().startsWith(partial)) {
                            completions.add(id);
                        }
                    });
                }
                
                // If they can view others, also suggest nearby claims
                if (player.hasPermission("griefprevention.claiminfo.other")) {
                    gp.getClaimAt(player.getLocation()).ifPresent(claim -> {
                        gp.getClaimId(claim).ifPresent(id -> {
                            if (id.toLowerCase().startsWith(partial) && !completions.contains(id)) {
                                completions.add(id);
                            }
                        });
                    });
                }
            }
        }
        
        return completions;
    }
}
