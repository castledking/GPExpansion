package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.EvictionStore;
import dev.towki.gpexpansion.storage.RentalStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SignProtectionListener implements Listener {
    private final GPExpansionPlugin plugin;
    private final GPBridge gp = new GPBridge();
    private final NamespacedKey keyKind;
    private final NamespacedKey keyClaim;
    private final NamespacedKey keyRenter;

    private final Map<String, Long> confirmMap = new HashMap<>(); // key by world:x:y:z + playerUUID

    public SignProtectionListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.keyKind = new NamespacedKey(plugin, "sign.kind");
        this.keyClaim = new NamespacedKey(plugin, "sign.claimId");
        this.keyRenter = new NamespacedKey(plugin, "rent.renter");
    }

    private boolean isOurSign(Block b) {
        if (b == null) return false;
        Material t = b.getType();
        if (!t.name().endsWith("_SIGN") && !t.name().endsWith("_WALL_SIGN")) return false;
        Sign sign = (Sign) b.getState();

        // Check if sign has our persistent data
        if (!sign.getPersistentDataContainer().has(keyKind, PersistentDataType.STRING)) {
            return false;
        }

        // Check if this is a [Sell] sign (permanent transfer)
        // [Sell] signs are labeled as "[Buy Claim]" while [Rent] signs are "[Rent Claim]"
        Component signTextComponent = sign.getSide(org.bukkit.block.sign.Side.FRONT).line(0);
        if (signTextComponent != null) {
            String plainText = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(signTextComponent);
            if (plainText.contains("[Buy Claim]")) {
                // [Sell] signs represent permanent transfers and should not be protected
                // Once purchased, they should be removable by anyone
                return false;
            }
        }

        // Protect [Rent] signs and other managed signs
        return true;
    }

    private Block getSupport(Block signBlock) {
        Material t = signBlock.getType();
        if (t.name().endsWith("_WALL_SIGN")) {
            // Determine attached face from block data
            try {
                org.bukkit.block.data.type.WallSign data = (org.bukkit.block.data.type.WallSign) signBlock.getBlockData();
                BlockFace face = data.getFacing().getOppositeFace();
                return signBlock.getRelative(face);
            } catch (ClassCastException ignored) {
                return signBlock.getRelative(BlockFace.NORTH);
            }
        } else if (t.name().endsWith("_SIGN")) {
            return signBlock.getRelative(BlockFace.DOWN);
        }
        return null;
    }

    private boolean canAdminister(Player p) {
        return p.isOp() || p.hasPermission("griefprevention.admin") || p.hasPermission("gpexpansion.sign.admin");
    }

    private String formatDuration(long milliseconds) {
        if (milliseconds <= 0) return "0 seconds";
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        StringBuilder result = new StringBuilder();
        if (days > 0) {
            result.append(days).append("d");
            hours %= 24;
        }
        if (hours > 0) {
            if (result.length() > 0) result.append(" ");
            result.append(hours).append("h");
        }
        if (result.length() == 0 && minutes > 0) {
            result.append(minutes).append("m");
        }
        if (result.length() == 0) {
            result.append("<1m");
        }
        return result.toString();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        Player p = event.getPlayer();

        // Protect supporting blocks of managed signs
        if (!isOurSign(b)) {
            // Check if this block is a support block for a managed sign
            if (isSupportOfManagedSign(b)) {
                if (!handleBreakOfManagedSignSupport(b, p, event)) {
                    event.setCancelled(true);
                }
            }
            return; // not our sign or support block
        }

        if (!handleBreakOfManagedSign(b, p, event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignInteract(PlayerInteractEvent event) {
        // Only process right-clicks on blocks with main hand
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND ||
            event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK ||
            event.getClickedBlock() == null) {
            return;
        }

        // Check if the clicked block is a sign
        if (!(event.getClickedBlock().getState() instanceof Sign)) {
            return;
        }

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();

        if (isOurSign(block)) {
            // Handle sneak + right click confirmation for managed signs
            if (player.isSneaking()) {
                Sign sign = (Sign) block.getState();
                String claimId = sign.getPersistentDataContainer().get(keyClaim, PersistentDataType.STRING);
                String renterStr = sign.getPersistentDataContainer().get(keyRenter, PersistentDataType.STRING);
                
                // Check ownership or admin bypass
                boolean adminBypass = canAdminister(player);
                boolean owner = false;
                if (claimId != null && !claimId.isEmpty()) {
                    java.util.Optional<Object> claimOpt = gp.findClaimById(claimId);
                    if (claimOpt.isPresent()) {
                        try {
                            Object claim = claimOpt.get();
                            Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
                            owner = ownerId != null && ownerId.equals(player.getUniqueId());
                        } catch (ReflectiveOperationException ignored) {}
                    }
                }
                
                if (!(owner || adminBypass)) {
                    player.sendMessage(ChatColor.RED + "You cannot manage this sign.");
                    event.setCancelled(true);
                    return;
                }
                
                // Check eviction status before allowing deletion (unless player has bypass permission)
                boolean evictionBypass = player.hasPermission("gpexpansion.eviction.bypass");
                if (!evictionBypass && renterStr != null && !renterStr.isEmpty() && claimId != null && !claimId.isEmpty()) {
                    RentalStore rentalStore = plugin.getRentalStore();
                    RentalStore.Entry rental = rentalStore != null ? rentalStore.all().get(claimId) : null;
                    boolean hasActiveRental = (rental != null && rental.expiry > System.currentTimeMillis()) || (rental == null);
                    
                    if (hasActiveRental) {
                        EvictionStore evictionStore = plugin.getEvictionStore();
                        if (evictionStore == null || !evictionStore.hasPendingEviction(claimId)) {
                            player.sendMessage(ChatColor.RED + "This claim has an active renter.");
                            player.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.GOLD + "/claim evict " + claimId + ChatColor.YELLOW + " to start a 14-day eviction notice.");
                            event.setCancelled(true);
                            return;
                        }
                        
                        EvictionStore.EvictionEntry eviction = evictionStore.getEviction(claimId);
                        if (!eviction.isEffective() && !adminBypass) {
                            long remaining = eviction.getRemainingTime();
                            String timeRemaining = formatDuration(remaining);
                            player.sendMessage(ChatColor.RED + "Eviction notice is still pending.");
                            player.sendMessage(ChatColor.YELLOW + timeRemaining + " remaining before you can remove the renter.");
                            event.setCancelled(true);
                            return;
                        }
                    }
                }
                
                String key = block.getWorld().getName()+":"+block.getX()+":"+block.getY()+":"+block.getZ()+":"+player.getUniqueId();
                long now = System.currentTimeMillis();
                Long prev = confirmMap.get(key);

                // Check if there's a pending confirmation within the time window
                if (prev != null && (now - prev) <= 10000L) {
                    // Execute confirmation - clear rental and remove sign
                    confirmMap.remove(key);
                    performDelete(block);
                    player.sendMessage(ChatColor.GREEN + "Rental sign removed and rental cleared.");
                    event.setCancelled(true);
                    return;
                }
            }

            // Cancel the event to prevent sign editing interface, but allow rental confirmation to work
            event.setCancelled(true);
        }
    }

    private boolean handleBreakOfManagedSignSupport(Block supportBlock, Player p, BlockBreakEvent event) {
        // Find the sign that this block supports
        Block signBlock = null;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN, BlockFace.UP}) {
            Block adj = supportBlock.getRelative(face);
            if (isOurSign(adj)) {
                // Check if this support block actually supports this sign
                Block support = getSupport(adj);
                if (support != null && support.equals(supportBlock)) {
                    signBlock = adj;
                    break;
                }
            }
        }

        if (signBlock == null) return true; // No sign found, allow break

        // Use the same logic as breaking a sign
        return handleBreakOfManagedSign(signBlock, p, event);
    }

    private boolean handleBreakOfManagedSign(Block signBlock, Player p, BlockBreakEvent event) {
        Sign sign = (Sign) signBlock.getState();
        String claimId = sign.getPersistentDataContainer().get(keyClaim, PersistentDataType.STRING);
        if (claimId == null) claimId = "";
        Optional<Object> claimOpt = gp.findClaimById(claimId);
        boolean adminBypass = canAdminister(p);
        boolean owner = false;
        if (claimOpt.isPresent()) {
            try {
                Object claim = claimOpt.get();
                Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
                owner = ownerId != null && ownerId.equals(p.getUniqueId());
            } catch (ReflectiveOperationException ignored) {}
        }
        if (!(owner || adminBypass)) {
            p.sendMessage(ChatColor.RED + "You cannot break this sign.");
            return false;
        }
        
        // Check if this rental sign has an active renter - require eviction process (unless player has bypass permission)
        boolean evictionBypass = p.hasPermission("gpexpansion.eviction.bypass");
        String renterStr = sign.getPersistentDataContainer().get(keyRenter, PersistentDataType.STRING);
        
        // DEBUG: Log eviction check conditions
        plugin.getLogger().info("[DEBUG] evictionBypass=" + evictionBypass + ", renterStr=" + renterStr + ", claimId=" + claimId);
        
        if (!evictionBypass && renterStr != null && !renterStr.isEmpty() && !claimId.isEmpty()) {
            // Sign has a renter in PDC - always require eviction to protect the renter
            // This applies regardless of RentalStore state (could be expired, missing, or data mismatch)
            {
                EvictionStore evictionStore = plugin.getEvictionStore();
                
                // If no eviction process started, or eviction is not yet effective, block the break
                if (!evictionStore.hasPendingEviction(claimId)) {
                    // No eviction started - tell owner to use /claim evict
                    p.sendMessage(ChatColor.RED + "This claim has an active renter.");
                    p.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.GOLD + "/claim evict " + claimId + ChatColor.YELLOW + " to start a 14-day eviction notice.");
                    p.sendMessage(ChatColor.GRAY + "You cannot break this sign or remove the renter until the eviction period has passed.");
                    return false;
                }
                
                EvictionStore.EvictionEntry eviction = evictionStore.getEviction(claimId);
                if (!eviction.isEffective() && !adminBypass) {
                    // Eviction started but 14 days haven't passed yet
                    long remaining = eviction.getRemainingTime();
                    String timeRemaining = formatDuration(remaining);
                    p.sendMessage(ChatColor.RED + "Eviction notice is still pending.");
                    p.sendMessage(ChatColor.YELLOW + timeRemaining + " remaining before you can remove the renter.");
                    p.sendMessage(ChatColor.GRAY + "Use " + ChatColor.GOLD + "/claim evict status " + claimId + ChatColor.GRAY + " to check status.");
                    return false;
                }
                // Eviction is effective - allow proceeding with confirmation
            }
        }
        
        if (!p.isSneaking()) {
            p.sendMessage(ChatColor.YELLOW + "Sneak and break to manage this sign.");
            return false;
        }
        String key = signBlock.getWorld().getName()+":"+signBlock.getX()+":"+signBlock.getY()+":"+signBlock.getZ()+":"+p.getUniqueId();
        long now = System.currentTimeMillis();
        Long prev = confirmMap.get(key);
        if (prev == null || (now - prev) > 10000L) {
            confirmMap.put(key, now);
            // Compose confirmation message
            // renterStr already declared above, reuse it
            if (renterStr == null) {
                renterStr = sign.getPersistentDataContainer().get(keyRenter, PersistentDataType.STRING);
            }

            // Check if this is a currently rented sign
            if (renterStr != null && !renterStr.isEmpty()) {
                try {
                    UUID renterId = UUID.fromString(renterStr);
                    OfflinePlayer renter = Bukkit.getOfflinePlayer(renterId);
                    String renterName = renter.getName() != null ? renter.getName() : renterId.toString();

                    Component message = Component.text(ChatColor.YELLOW + "Warning: " + ChatColor.WHITE + renterName + ChatColor.YELLOW + " is currently renting this from you. Try ")
                            .append(Component.text("/claim evict " + renterName, NamedTextColor.GOLD)
                                    .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/claim evict " + renterName))
                                    .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, Component.text("Click to run /claim evict " + renterName))))
                            .append(Component.text(" while standing in the claim to start the eviction process.", NamedTextColor.YELLOW));
                    p.sendMessage(message);
                } catch (IllegalArgumentException ignored) {
                    // Fallback to regular message if UUID parsing fails
                    Component base = Component.text(ChatColor.YELLOW + "Click here to delete the rental or break the sign again while sneaking.")
                            .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.RUN_COMMAND, "/gpexpansion:rentalsignconfirm "+signBlock.getWorld().getName()+" "+signBlock.getX()+" "+signBlock.getY()+" "+signBlock.getZ()))
                            .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, Component.text("Confirm deletion")));
                    p.sendMessage(base);
                    p.sendMessage(ChatColor.GRAY + "You can also confirm by " + ChatColor.GOLD + "sneak + right clicking " + ChatColor.GRAY + "the sign.");
                }
            } else {
                // Not currently rented - use regular confirmation
                Component base = Component.text(ChatColor.YELLOW + "Click here to delete the rental or break the sign again while sneaking.")
                        .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.RUN_COMMAND, "/gpexpansion:rentalsignconfirm "+signBlock.getWorld().getName()+" "+signBlock.getX()+" "+signBlock.getY()+" "+signBlock.getZ()))
                        .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, Component.text("Confirm deletion")));
                p.sendMessage(base);
                p.sendMessage(ChatColor.GRAY + "You can also confirm by " + ChatColor.GOLD + "sneak + right clicking " + ChatColor.GRAY + "the sign.");
            }

            if (renterStr != null && !renterStr.isEmpty()) {
                try {
                    UUID rid = UUID.fromString(renterStr);
                    OfflinePlayer off = Bukkit.getOfflinePlayer(rid);
                    p.sendMessage(ChatColor.GOLD + "Warning: trust will be removed for " + ChatColor.YELLOW + (off != null ? off.getName() : rid));
                } catch (IllegalArgumentException ignored) {}
            }
            return false; // cancel this break
        }
        // Second break within window: allow; cleanup via event listener for command also
        confirmMap.remove(key);
        // Proceed with delete handling here: clear rental + revoke trust
        performDelete(signBlock);
        return true; // allow break
    }

    private void performDelete(Block signBlock) {
        try {
            Sign sign = (Sign) signBlock.getState();
            String claimId = sign.getPersistentDataContainer().get(keyClaim, PersistentDataType.STRING);
            String renterStr = sign.getPersistentDataContainer().get(keyRenter, PersistentDataType.STRING);
            // Clear store
            RentalStore store = plugin.getRentalStore();
            if (store != null && claimId != null) {
                store.clear(claimId);
                store.save();
            }
            // Revoke trust if any
            if (claimId != null && renterStr != null) {
                Optional<Object> claimOpt = gp.findClaimById(claimId);
                if (claimOpt.isPresent()) {
                    UUID renter = UUID.fromString(renterStr);
                    String renterName = Bukkit.getOfflinePlayer(renter).getName();
                    if (renterName != null) {
                        // Use GPBridge to untrust from this specific claim only
                        boolean untrusted = gp.untrust(renterName, claimOpt.get());
                        if (untrusted) {
                            plugin.getLogger().info("Removed trust for " + renterName + " from claim " + claimId);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> isOurSign(b) || isSupportOfManagedSign(b));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> isOurSign(b) || isSupportOfManagedSign(b));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnderman(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Enderman)) return;
        Block b = event.getBlock();
        if (isOurSign(b) || isSupportOfManagedSign(b)) {
            event.setCancelled(true);
        }
    }

    private boolean isSupportOfManagedSign(Block b) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN}) {
            Block adj = b.getRelative(face);
            if (isOurSign(adj)) {
                Block support = getSupport(adj);
                return support != null && support.equals(b);
            }
        }
        return false;
    }
}
