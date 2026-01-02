package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.MailboxStore;
import dev.towki.gpexpansion.util.EcoKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.io.BukkitObjectInputStream;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.*;

public class MailboxListener implements Listener {
    private final GPExpansionPlugin plugin;
    private final GPBridge gp;
    private final MailboxStore mailboxStore;
    private final Map<UUID, Location> viewingChests = new HashMap<>();
    
    // PDC keys
    private NamespacedKey keyKind() { return new NamespacedKey(plugin, "sign.kind"); }
    private NamespacedKey keyClaim() { return new NamespacedKey(plugin, "sign.claimId"); }
    private NamespacedKey keyEcoAmt() { return new NamespacedKey(plugin, "sign.ecoAmt"); }
    private NamespacedKey keyPerClick() { return new NamespacedKey(plugin, "sign.perClick"); }
    private NamespacedKey keyMaxCap() { return new NamespacedKey(plugin, "sign.maxCap"); }
    private NamespacedKey keyItemB64() { return new NamespacedKey(plugin, "item-b64"); }
    private NamespacedKey keyEcoKind() { return new NamespacedKey(plugin, "sign.ecoKind"); }

    public MailboxListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.gp = new GPBridge();
        this.mailboxStore = plugin.getMailboxStore();
        
        // Note: GP3D detection is delayed until needed
        // We'll check when creating mailbox signs instead
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || 
            event.getAction() != Action.RIGHT_CLICK_BLOCK || 
            event.getClickedBlock() == null) {
            return;
        }

        Block block = event.getClickedBlock();
        
        // Check if clicking on a mailbox sign
        if (block.getState() instanceof Sign sign) {
            PersistentDataContainer pdc = sign.getPersistentDataContainer();
            String signType = pdc.get(keyKind(), PersistentDataType.STRING);
            
            if (!"MAILBOX".equals(signType)) {
                return;
            }

            event.setCancelled(true);
            Player player = event.getPlayer();
            player.setCooldown(event.getMaterial(), 5);
            
            String claimId = pdc.get(keyClaim(), PersistentDataType.STRING);
            
            if (claimId == null) {
                player.sendMessage(Component.text("Invalid mailbox sign.", NamedTextColor.RED));
                return;
            }

            // Check if mailbox is already owned
            if (mailboxStore.isMailbox(claimId)) {
                UUID owner = mailboxStore.getOwner(claimId);
                if (owner != null && owner.equals(player.getUniqueId())) {
                    // Owner opening - show full chest access
                    openMailboxChest(player, claimId, true);
                } else {
                    // Non-owner opening - show deposit view
                    openMailboxChest(player, claimId, false);
                }
            } else {
                // Mailbox not owned - show purchase confirmation
                showPurchaseConfirmation(player, claimId, pdc, block.getLocation());
            }
            return;
        }
        
        // Check if clicking on a container block that might be part of a mailbox
        if (isContainerBlock(block.getType())) {
            Player player = event.getPlayer();
            
            // Check all claims to find if this container is in a mailbox claim
            for (String claimId : mailboxStore.all().keySet()) {
                Object claim = gp.findClaimById(claimId).orElse(null);
                if (claim != null) {
                    GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
                    World world = gp.getClaimWorld(claim).map(Bukkit::getWorld).orElse(null);
                    
                    if (world != null && corners != null && world.equals(block.getWorld())) {
                        // Check if block is within the claim boundaries
                        if (block.getX() >= corners.x1 && block.getX() <= corners.x2 &&
                            block.getY() >= corners.y1 && block.getY() <= corners.y2 &&
                            block.getZ() >= corners.z1 && block.getZ() <= corners.z2) {
                            
                            event.setCancelled(true);
                            player.setCooldown(event.getMaterial(), 5);
                            
                            if (mailboxStore.isMailbox(claimId)) {
                                UUID owner = mailboxStore.getOwner(claimId);
                                if (owner != null && owner.equals(player.getUniqueId())) {
                                    openMailboxChest(player, claimId, true);
                                } else {
                                    openMailboxChest(player, claimId, false);
                                }
                            } else {
                                // Find the mailbox sign for this claim
                                PersistentDataContainer signPdc = getMailboxSignPDC(claimId);
                                if (signPdc != null) {
                                    Location signLoc = mailboxStore.getSignLocation(claimId);
                                    showPurchaseConfirmation(player, claimId, signPdc, signLoc);
                                }
                            }
                            return;
                        }
                    }
                }
            }
        }
    }
    
    private boolean isContainerBlock(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST || 
               material == Material.BARREL || material == Material.SHULKER_BOX ||
               material.name().endsWith("_SHULKER_BOX") ||
               material == Material.DISPENSER || material == Material.DROPPER ||
               material == Material.HOPPER;
    }

    private void showPurchaseConfirmation(Player player, String claimId, PersistentDataContainer pdc, Location signLocation) {
        String ecoAmt = pdc.get(keyEcoAmt(), PersistentDataType.STRING);
        String kindName = pdc.get(keyEcoKind(), PersistentDataType.STRING);
        EcoKind kind = EcoKind.valueOf(kindName);
        
        // Format the economy amount for display
        String ecoFormatted;
        switch (kind) {
            case MONEY: {
                try {
                    double amount = Double.parseDouble(ecoAmt);
                    ecoFormatted = plugin.formatMoney(amount);
                } catch (NumberFormatException e) {
                    ecoFormatted = "$" + ecoAmt;
                }
                break;
            }
            case EXPERIENCE: {
                boolean levels = ecoAmt.toUpperCase().endsWith("L");
                ecoFormatted = levels ? 
                    ecoAmt.substring(0, ecoAmt.length() - 1) + " Levels" : 
                    ecoAmt + " XP";
                break;
            }
            case CLAIMBLOCKS: {
                ecoFormatted = ecoAmt + " blocks";
                break;
            }
            case ITEM: {
                String b64 = pdc.get(keyItemB64(), PersistentDataType.STRING);
                String name = formatItemName(decodeItem(b64));
                ecoFormatted = ecoAmt + " " + name;
                break;
            }
            default:
                ecoFormatted = ecoAmt;
        }
        
        // Use the standard confirmation service with sign location for updating after purchase
        plugin.getConfirmationService().prompt(
            player,
            dev.towki.gpexpansion.confirm.ConfirmationService.Action.BUY,
            claimId,
            ecoFormatted,
            kind.name(),
            ecoAmt,
            "1", // dummy perClick
            "1", // dummy maxCap
            signLocation
        );
    }


    private void openMailboxChest(Player player, String claimId, boolean isOwner) {
        // Find the container in the 1x1x1 claim
        Object claim = gp.findClaimById(claimId).orElse(null);
        if (claim == null) {
            player.sendMessage(Component.text("Claim not found.", NamedTextColor.RED));
            return;
        }
        Block containerBlock = findContainerInClaim(claim);
        if (containerBlock == null) {
            player.sendMessage(Component.text("No container found in mailbox claim.", NamedTextColor.RED));
            return;
        }
        
        // Both owner and non-owner use the virtual view for consistency
        createMailboxView(player, containerBlock, claimId, isOwner);
    }

    private Block findContainerInClaim(Object claim) {
        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        World world = gp.getClaimWorld(claim).map(Bukkit::getWorld).orElse(null);
        
        if (world == null || corners == null) return null;
        
        // Check all blocks in the 1x1x1 claim for containers
        for (int y = corners.y1; y <= corners.y2; y++) {
            for (int x = corners.x1; x <= corners.x2; x++) {
                for (int z = corners.z1; z <= corners.z2; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type == Material.CHEST || type == Material.TRAPPED_CHEST || 
                        type == Material.BARREL || type == Material.SHULKER_BOX ||
                        type.name().endsWith("_SHULKER_BOX") ||
                        type == Material.DISPENSER || type == Material.DROPPER ||
                        type == Material.HOPPER) {
                        return block;
                    }
                }
            }
        }
        return null;
    }

    // Track owner viewing sessions
    private final Map<UUID, Boolean> isOwnerViewing = new HashMap<>();
    
    // Secure deposit tracking for non-owners
    // Maps player UUID to their deposit session
    private final Map<UUID, DepositSession> depositSessions = new HashMap<>();
    
    /**
     * Tracks a non-owner's deposit session - remembers which slots had items when opened
     * so we can block them from taking those items (deposit-only access)
     */
    private static class DepositSession {
        // Track which slots in the mailbox had items when opened (can't take these)
        final Set<Integer> originalMailboxSlots = new HashSet<>();
    }

    private void createMailboxView(Player player, Block containerBlock, String claimId, boolean isOwner) {
        if (!(containerBlock.getState() instanceof Container container)) {
            player.sendMessage(Component.text("Invalid container.", NamedTextColor.RED));
            return;
        }
        
        Inventory containerInv = container.getInventory();
        
        // Store tracking data
        viewingChests.put(player.getUniqueId(), containerBlock.getLocation());
        isOwnerViewing.put(player.getUniqueId(), isOwner);
        
        if (!isOwner) {
            // Non-owner: track which slots had items when they opened (can't take these)
            DepositSession session = new DepositSession();
            
            for (int i = 0; i < containerInv.getSize(); i++) {
                ItemStack item = containerInv.getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    session.originalMailboxSlots.add(i);
                }
            }
            
            depositSessions.put(player.getUniqueId(), session);
            plugin.getLogger().info("[Mailbox] Non-owner opening mailbox. Original slots with items: " + session.originalMailboxSlots);
        } else {
            plugin.getLogger().info("[Mailbox] Owner opening mailbox.");
        }
        
        // Open the ACTUAL container inventory directly - no virtual inventory
        player.openInventory(containerInv);
    }
    
    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Get top inventory from InventoryView using reflection (Paper 1.21+ compatibility)
     * In Paper 1.21+, InventoryView changed from class to interface
     */
    private Inventory getTopInventory(Object inventoryView) {
        try {
            // Try to find InventoryView class/interface and get method from there
            Class<?> viewClass = Class.forName("org.bukkit.inventory.InventoryView");
            java.lang.reflect.Method method = viewClass.getMethod("getTopInventory");
            return (Inventory) method.invoke(inventoryView);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get top inventory via reflection: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get bottom inventory from InventoryView using reflection (Paper 1.21+ compatibility)
     */
    private Inventory getBottomInventory(Object inventoryView) {
        try {
            // Try to find InventoryView class/interface and get method from there
            Class<?> viewClass = Class.forName("org.bukkit.inventory.InventoryView");
            java.lang.reflect.Method method = viewClass.getMethod("getBottomInventory");
            return (Inventory) method.invoke(inventoryView);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get bottom inventory via reflection: " + e.getMessage());
            return null;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST) // Run FIRST to cancel before other plugins
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        
        if (!viewingChests.containsKey(player.getUniqueId())) return;
        
        Boolean isOwner = isOwnerViewing.get(player.getUniqueId());
        
        // Owners can do anything - full access to the container
        if (isOwner != null && isOwner) {
            return; // Allow all interactions for owner
        }
        
        // Non-owner: can ADD items but NOT TAKE original items
        DepositSession session = depositSessions.get(player.getUniqueId());
        if (session == null) {
            event.setCancelled(true);
            return;
        }
        
        int clickedSlot = event.getRawSlot();
        Inventory clickedInv = event.getClickedInventory();
        Inventory topInv = getTopInventory(event.getView());
        
        if (topInv == null) {
            event.setCancelled(true);
            return;
        }
        
        int topSize = topInv.getSize();
        boolean clickedTop = (clickedSlot >= 0 && clickedSlot < topSize);
        InventoryAction action = event.getAction();
        
        // If clicking on the mailbox (top inventory)
        if (clickedTop) {
            // Block taking items from slots that had items when opened (original items)
            if (session.originalMailboxSlots.contains(clickedSlot)) {
                // Trying to take an original item - BLOCK
                if (action == InventoryAction.PICKUP_ALL || action == InventoryAction.PICKUP_HALF ||
                    action == InventoryAction.PICKUP_ONE || action == InventoryAction.PICKUP_SOME ||
                    action == InventoryAction.MOVE_TO_OTHER_INVENTORY || action == InventoryAction.HOTBAR_SWAP ||
                    action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.SWAP_WITH_CURSOR) {
                    
                    event.setCancelled(true);
                    player.sendMessage(Component.text("You can only deposit items, not take them!", NamedTextColor.RED));
                    return;
                }
            }
            // Allow placing items in empty slots or slots they added items to
            return;
        }
        
        // Clicking in player's inventory - allow shift-click to deposit
        if (clickedInv != null && clickedInv.equals(player.getInventory())) {
            // Shift-click moves items to container - allow this
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                // Allow - items will go to empty slots in the mailbox
                return;
            }
            // Other interactions in player inventory are fine
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST) // Run FIRST to cancel before other plugins
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        
        if (!viewingChests.containsKey(player.getUniqueId())) return;
        
        Boolean isOwner = isOwnerViewing.get(player.getUniqueId());
        
        // Owners can do anything
        if (isOwner != null && isOwner) {
            return;
        }
        
        // Block all drags for non-owners - they must use click-to-stage
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        
        Location chestLoc = viewingChests.remove(player.getUniqueId());
        Boolean isOwner = isOwnerViewing.remove(player.getUniqueId());
        DepositSession session = depositSessions.remove(player.getUniqueId());
        
        if (chestLoc == null) return;
        
        Block chestBlock = chestLoc.getBlock();
        if (!(chestBlock.getState() instanceof Container container)) return;
        
        // Items save automatically since we're using the real container
        // Just update the block state to ensure persistence
        container.update();
        
        if (isOwner != null && isOwner) {
            plugin.getLogger().info("[Mailbox] Owner closed mailbox - items saved automatically");
        } else {
            plugin.getLogger().info("[Mailbox] Non-owner closed mailbox - deposits saved automatically");
        }
        
        // Check storage warnings for owner
        if (isOwner != null && isOwner) {
            checkStorageWarnings(player, container.getInventory(), chestLoc);
        }
    }

    private void checkStorageWarnings(Player player, Inventory chestInv, Location chestLoc) {
        int emptySlots = 0;
        for (ItemStack item : chestInv.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                emptySlots++;
            }
        }
        
        if (emptySlots == 0) {
            player.sendMessage(Component.text("WARNING: This mailbox is completely full!", NamedTextColor.RED));
        } else if (emptySlots <= 2) {
            player.sendMessage(Component.text("WARNING: This mailbox is almost full (" + emptySlots + " slots left)!", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Check owned mailboxes for storage warnings
        new BukkitRunnable() {
            @Override
            public void run() {
                checkAllMailboxStorage(player);
            }
        }.runTaskLater(plugin, 100L); // Check after 5 seconds
    }

    private void checkAllMailboxStorage(Player player) {
        for (Map.Entry<String, UUID> entry : mailboxStore.all().entrySet()) {
            if (entry.getValue().equals(player.getUniqueId())) {
                String claimId = entry.getKey();
                Object claim = gp.findClaimById(claimId).orElse(null);
                if (claim != null) {
                    Block chestBlock = findContainerInClaim(claim);
                    if (chestBlock != null && chestBlock.getState() instanceof Container container) {
                        Inventory chestInv = container.getInventory();
                        int emptySlots = 0;
                        for (ItemStack item : chestInv.getContents()) {
                            if (item == null || item.getType() == Material.AIR) {
                                emptySlots++;
                            }
                        }
                        
                        if (emptySlots == 0) {
                            player.sendMessage(Component.text("WARNING: Your mailbox at " + 
                                chestBlock.getX() + "," + chestBlock.getY() + "," + chestBlock.getZ() + 
                                " is completely full!", NamedTextColor.RED));
                        } else if (emptySlots <= 9) {
                            player.sendMessage(Component.text("WARNING: Your mailbox at " + 
                                chestBlock.getX() + "," + chestBlock.getY() + "," + chestBlock.getZ() + 
                                " is almost full (" + emptySlots + " slots left)!", NamedTextColor.YELLOW));
                        }
                    }
                }
            }
        }
    }

    public boolean handlePurchaseConfirmation(Player player, String claimId, Location signLocation) {
        // Check if still available
        if (mailboxStore.isMailbox(claimId)) {
            player.sendMessage(Component.text("This mailbox has already been purchased.", NamedTextColor.RED));
            return true;
        }
        
        // Process payment - use provided sign location or try to find it
        PersistentDataContainer pdc = null;
        if (signLocation != null && signLocation.getBlock().getState() instanceof Sign sign) {
            pdc = sign.getPersistentDataContainer();
        }
        if (pdc == null) {
            pdc = getMailboxSignPDC(claimId);
        }
        if (pdc == null) {
            player.sendMessage(Component.text("Mailbox sign not found.", NamedTextColor.RED));
            return true;
        }
        
        String ecoAmt = pdc.get(keyEcoAmt(), PersistentDataType.STRING);
        String kindName = pdc.get(keyEcoKind(), PersistentDataType.STRING);
        EcoKind kind = EcoKind.valueOf(kindName);
        
        if (!processPayment(player, kind, ecoAmt)) {
            player.sendMessage(Component.text("Payment failed.", NamedTextColor.RED));
            return true;
        }
        
        // Register ownership and store sign location
        mailboxStore.setOwner(claimId, player.getUniqueId());
        if (signLocation != null) {
            mailboxStore.setSignLocation(claimId, signLocation);
        }
        
        // Grant container trust on the claim
        Object claim = gp.findClaimById(claimId).orElse(null);
        if (claim != null) {
            gp.grantInventoryTrust(player, player.getName(), claim);
        }
        
        // Update sign display
        updateMailboxSign(claimId, player.getName());
        
        player.sendMessage(Component.text("You have successfully purchased this mailbox!", NamedTextColor.GREEN));
        return true;
    }

    public PersistentDataContainer getMailboxSignPDC(String claimId) {
        // First check stored sign location
        Location storedLoc = mailboxStore.getSignLocation(claimId);
        if (storedLoc != null) {
            Block block = storedLoc.getBlock();
            if (block.getState() instanceof Sign sign) {
                PersistentDataContainer pdc = sign.getPersistentDataContainer();
                String signType = pdc.get(keyKind(), PersistentDataType.STRING);
                if ("MAILBOX".equals(signType)) {
                    return pdc;
                }
            }
        }
        
        // Fallback: search near the claim for legacy signs
        Object claim = gp.findClaimById(claimId).orElse(null);
        if (claim == null) return null;
        
        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        World world = gp.getClaimWorld(claim).map(Bukkit::getWorld).orElse(null);
        
        if (world == null || corners == null) return null;
        
        // Check for signs attached to any side of the claim
        for (int x = corners.x1 - 1; x <= corners.x2 + 1; x++) {
            for (int y = corners.y1; y <= corners.y2 + 1; y++) {
                for (int z = corners.z1 - 1; z <= corners.z2 + 1; z++) {
                    boolean isPerimeter = (x == corners.x1 - 1 || x == corners.x2 + 1 || 
                                          z == corners.z1 - 1 || z == corners.z2 + 1);
                    
                    if (!isPerimeter) continue;
                    
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getState() instanceof Sign sign) {
                        PersistentDataContainer pdc = sign.getPersistentDataContainer();
                        String signType = pdc.get(keyKind(), PersistentDataType.STRING);
                        String signClaimId = pdc.get(keyClaim(), PersistentDataType.STRING);
                        if ("MAILBOX".equals(signType) && claimId.equals(signClaimId)) {
                            return pdc;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean processPayment(Player player, EcoKind kind, String amount) {
        try {
            double amt = Double.parseDouble(amount);
            
            switch (kind) {
                case MONEY:
                    // Try a late hook in case the economy registered after onEnable
                    if (!plugin.isEconomyAvailable()) {
                        plugin.refreshEconomy();
                    }
                    if (!plugin.isEconomyAvailable()) {
                        player.sendMessage(Component.text("Economy not available. Please ensure Vault and an economy provider are installed.", NamedTextColor.RED));
                        return false;
                    }
                    if (!plugin.hasMoney(player, amt)) {
                        player.sendMessage(Component.text("You don't have enough money.", NamedTextColor.RED));
                        return false;
                    }
                    plugin.withdrawMoney(player, amt);
                    break;
                case EXPERIENCE:
                    int totalExp = getPlayerTotalExperience(player);
                    int cost = (int) amt;
                    if (totalExp < cost) {
                        player.sendMessage(Component.text("You don't have enough experience.", NamedTextColor.RED));
                        return false;
                    }
                    takeExperience(player, cost);
                    break;
                case CLAIMBLOCKS:
                    // Implementation needed for claim blocks
                    break;
                case ITEM:
                    // Implementation needed for item payment
                    break;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int getPlayerTotalExperience(Player player) {
        return Math.round(getExpToLevel(player.getLevel()) + 
            (player.getExp() * getExpToLevel(player.getLevel() + 1) - getExpToLevel(player.getLevel())));
    }

    private float getExpToLevel(int level) {
        if (level <= 15) {
            return level * level + 6 * level;
        } else if (level <= 30) {
            return 2.5f * level * level - 40.5f * level + 360;
        } else {
            return 4.5f * level * level - 162.5f * level + 2220;
        }
    }

    private void takeExperience(Player player, int amount) {
        int current = getPlayerTotalExperience(player);
        setTotalExperience(player, current - amount);
    }

    private void setTotalExperience(Player player, int exp) {
        if (exp < 0) exp = 0;
        player.setExp(0);
        player.setLevel(0);
        player.setTotalExperience(0);
        
        while (exp > 0) {
            int expToNext = (int) getExpToLevel(player.getLevel());
            if (exp < expToNext) {
                player.setExp((float) exp / expToNext);
                break;
            }
            exp -= expToNext;
            player.setLevel(player.getLevel() + 1);
        }
    }

    private void updateMailboxSign(String claimId, String ownerName) {
        // First check stored sign location
        Location storedLoc = mailboxStore.getSignLocation(claimId);
        if (storedLoc != null) {
            Block block = storedLoc.getBlock();
            if (block.getState() instanceof Sign sign) {
                PersistentDataContainer signPdc = sign.getPersistentDataContainer();
                String signType = signPdc.get(keyKind(), PersistentDataType.STRING);
                if ("MAILBOX".equals(signType)) {
                    sign.setLine(0, ChatColor.BLUE + "" + ChatColor.BOLD + "[Mailbox]");
                    sign.setLine(1, ChatColor.GREEN + ownerName);
                    sign.setLine(2, ChatColor.BLACK + "(Click to open)");
                    sign.setLine(3, ChatColor.BLACK + "ID: " + ChatColor.GOLD + claimId);
                    sign.update();
                    return;
                }
            }
        }
        
        // Fallback: search near the claim for legacy signs
        Object claim = gp.findClaimById(claimId).orElse(null);
        if (claim == null) return;
        
        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        World world = gp.getClaimWorld(claim).map(Bukkit::getWorld).orElse(null);
        
        if (world == null || corners == null) return;
        
        for (int x = corners.x1 - 1; x <= corners.x2 + 1; x++) {
            for (int y = corners.y1; y <= corners.y2 + 1; y++) {
                for (int z = corners.z1 - 1; z <= corners.z2 + 1; z++) {
                    boolean isPerimeter = (x == corners.x1 - 1 || x == corners.x2 + 1 || 
                                          z == corners.z1 - 1 || z == corners.z2 + 1);
                    
                    if (!isPerimeter) continue;
                    
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getState() instanceof Sign sign) {
                        PersistentDataContainer signPdc = sign.getPersistentDataContainer();
                        String signType = signPdc.get(keyKind(), PersistentDataType.STRING);
                        String signClaimId = signPdc.get(keyClaim(), PersistentDataType.STRING);
                        if ("MAILBOX".equals(signType) && claimId.equals(signClaimId)) {
                            sign.setLine(0, ChatColor.BLUE + "" + ChatColor.BOLD + "[Mailbox]");
                            sign.setLine(1, ChatColor.GREEN + ownerName);
                            sign.setLine(2, ChatColor.BLACK + "(Click to open)");
                            sign.setLine(3, ChatColor.BLACK + "ID: " + ChatColor.GOLD + claimId);
                            sign.update();
                            return;
                        }
                    }
                }
            }
        }
    }
    
    private ItemStack decodeItem(String base64) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
            BukkitObjectInputStream is = new BukkitObjectInputStream(in);
            return (ItemStack) is.readObject();
        } catch (Exception e) {
            return null;
        }
    }
    
    private String formatItemName(ItemStack item) {
        if (item == null) return "Item";
        if (item.getItemMeta() != null) {
            // Use the new API to get the display name
            if (item.getItemMeta().hasDisplayName()) {
                return item.getItemMeta().displayName().toString();
            }
        }
        String name = item.getType().name().toLowerCase().replace('_', ' ');
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}
