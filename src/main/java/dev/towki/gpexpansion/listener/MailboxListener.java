package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import dev.towki.gpexpansion.util.EcoKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import dev.towki.gpexpansion.scheduler.TaskHandle;
import java.io.ByteArrayInputStream;
import java.util.*;

public class MailboxListener implements Listener {
    private final GPExpansionPlugin plugin;
    private final GPBridge gp;
    private final ClaimDataStore claimDataStore;
    private final Map<UUID, Location> viewingChests = new HashMap<>();
    
    // PDC keys
    private NamespacedKey keyKind() { return new NamespacedKey(plugin, "sign.kind"); }
    private NamespacedKey keyClaim() { return new NamespacedKey(plugin, "sign.claimId"); }
    private NamespacedKey keyEcoAmt() { return new NamespacedKey(plugin, "sign.ecoAmt"); }
    private NamespacedKey keyPerClick() { return new NamespacedKey(plugin, "sign.perClick"); }
    private NamespacedKey keyMaxCap() { return new NamespacedKey(plugin, "sign.maxCap"); }
    private NamespacedKey keyItemB64() { return new NamespacedKey(plugin, "item-b64"); }
    private NamespacedKey keyEcoKind() { return new NamespacedKey(plugin, "sign.ecoKind"); }
    /** Comma-separated list of shared player names for self mailbox (display uses "N players"). */
    private NamespacedKey keyMailboxShared() { return new NamespacedKey(plugin, "sign.mailbox.shared"); }

    public MailboxListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.gp = new GPBridge();
        this.claimDataStore = plugin.getClaimDataStore();
        
        // Note: GP3D detection is delayed until needed
        // We'll check when creating mailbox signs instead
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        // Instant [Mailbox] creation: wall sign adjacent to container, no other args
        if (event.line(0) == null) return;
        String line0 = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(event.line(0)).trim().replaceAll("§.", "");
        if (!line0.equalsIgnoreCase("[Mailbox]")) return;

        String line1 = event.line(1) != null ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(event.line(1)).trim().replaceAll("§.", "") : "";
        String line2 = event.line(2) != null ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(event.line(2)).trim().replaceAll("§.", "") : "";
        String line3 = event.line(3) != null ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(event.line(3)).trim().replaceAll("§.", "") : "";

        Player player = event.getPlayer();
        Block signBlock = event.getBlock();
        Material signType = signBlock.getType();
        if (!signType.name().contains("WALL_SIGN")) return;

        BlockData data = signBlock.getBlockData();
        if (!(data instanceof Directional dir)) return;
        Block containerBlock = signBlock.getRelative(dir.getFacing().getOppositeFace());
        if (!isContainerBlock(containerBlock.getType())) return;

        boolean debug = plugin.getConfigManager().isDebugEnabled();
        if (debug) plugin.getLogger().info("[mailbox-instant] " + player.getName() + " placing [Mailbox] on " + containerBlock.getType() + " at " + containerBlock.getLocation());

        java.util.Optional<Object> parentOpt = gp.getClaimAt(containerBlock.getLocation());
        if (parentOpt.isEmpty()) {
            if (debug) plugin.getLogger().info("[mailbox-instant] Fail: container not in claim");
            plugin.getMessages().send(player, "sign-creation.not-in-claim");
            return;
        }

        Object parentClaim = parentOpt.get();
        String parentClaimId = gp.getClaimId(parentClaim).orElse(null);
        if (parentClaimId == null) return;

        // Buyable mailbox: line 1 = kind;amount (e.g. money;100) — claim owner only, no mailbox created yet
        ParsedBuyable parsed = parseBuyableLine(line1);
        if (parsed != null) {
            if (!player.hasPermission("griefprevention.sign.create.mailbox")) {
                plugin.getMessages().send(player, "permissions.create-sign-denied", "{signtype}", "mailbox");
                return;
            }
            UUID playerId = player.getUniqueId();
            if (!gp.isOwner(parentClaim, playerId)) {
                plugin.getMessages().send(player, "sign-creation.not-in-claim");
                return;
            }
            final String claimIdForPdc = parentClaimId;
            final String ecoKindStr = parsed.kind.name();
            final String ecoAmtStr = parsed.amount;
            final String displayLine1 = parsed.displayLine;
            dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocation(plugin, signBlock.getLocation(), () -> {
                if (signBlock.getState() instanceof Sign sign) {
                    PersistentDataContainer pdc = sign.getPersistentDataContainer();
                    pdc.set(keyKind(), PersistentDataType.STRING, "MAILBOX");
                    pdc.set(keyClaim(), PersistentDataType.STRING, claimIdForPdc);
                    pdc.set(keyEcoAmt(), PersistentDataType.STRING, ecoAmtStr);
                    pdc.set(keyEcoKind(), PersistentDataType.STRING, ecoKindStr);
                    sign.setLine(0, ChatColor.BLUE + "" + ChatColor.BOLD + "[Mailbox]");
                    sign.setLine(1, displayLine1);
                    sign.setLine(2, ChatColor.BLACK + "(Click to buy)");
                    sign.setLine(3, "");
                    sign.update(true);
                }
            });
            plugin.getMessages().send(player, "mailbox.buyable-created");
            return;
        }

        // Self mailbox: lines 1–3 empty or player names for shared full-access
        if (!player.hasPermission("griefprevention.sign.create.self-mailbox")) {
            plugin.getMessages().send(player, "permissions.create-sign-denied", "{signtype}", "self-mailbox");
            return;
        }
        if (debug) plugin.getLogger().info("[mailbox-instant] Parent claim " + parentClaimId + ", isGP3D=" + gp.isGP3D());

        boolean realProtocol = plugin.getConfigManager().isMailboxProtocolReal();
        if (realProtocol) {
            if (gp.isGP3D()) {
                if (gp.isSubdivision(parentClaim)) {
                    boolean allowNested = gp.getAllowNestedSubclaims();
                    if (!allowNested) {
                        if (debug) plugin.getLogger().info("[mailbox-instant] Fail: subdivision + AllowNestedSubclaims=false");
                        return;
                    }
                }
            }
        }

        UUID playerId = player.getUniqueId();
        boolean isOwner = gp.isOwner(parentClaim, playerId);
        boolean isRenter = false;
        if (!isOwner) {
            Object topClaim = parentClaim;
            while (true) {
                java.util.Optional<Object> parent = gp.getParentClaim(topClaim);
                if (!parent.isPresent()) break;
                Object next = parent.get();
                if (next == topClaim) break;
                topClaim = next;
            }
            String rentalClaimId = gp.getClaimId(topClaim).orElse(parentClaimId);
            ClaimDataStore.RentalData rental = claimDataStore.getRental(rentalClaimId).orElse(null);
            isRenter = rental != null && rental.renter.equals(playerId);
            if (!isRenter) {
                if (debug) plugin.getLogger().info("[mailbox-instant] Fail: player must own or rent the claim");
                plugin.getMessages().send(player, "mailbox.must-own-or-rent");
                return;
            }
        }

        int maxSelf = plugin.getSignLimitManager().getSelfMailboxLimit(player);
        int count = countSelfMailboxesInClaim(parentClaim, playerId);
        if (count >= maxSelf) {
            plugin.getMessages().send(player, "mailbox.self-limit-reached", "{max}", String.valueOf(maxSelf));
            return;
        }

        String newClaimId;
        if (!realProtocol) {
            // Virtual: no subdivision, use synthetic id keyed by container location
            org.bukkit.World w = containerBlock.getWorld();
            if (w == null) return;
            newClaimId = "v:" + w.getUID() + ":" + containerBlock.getX() + ":" + containerBlock.getY() + ":" + containerBlock.getZ();
            claimDataStore.setMailbox(newClaimId, playerId);
            claimDataStore.setMailboxSignLocation(newClaimId, signBlock.getLocation());
            claimDataStore.setMailboxContainerLocation(newClaimId, containerBlock.getLocation());
            claimDataStore.save();
        } else {
            // Real: create subdivision (3D if GP3D, 2D otherwise) and container-trust public
            java.util.Optional<String> newClaimIdOpt = gp.isGP3D()
                ? gp.create1x1SubdivisionAt(containerBlock.getLocation(), playerId, parentClaim)
                : gp.create1x1Subdivision2DAt(containerBlock.getLocation(), playerId, parentClaim);
            if (newClaimIdOpt.isEmpty()) {
                if (debug) plugin.getLogger().info("[mailbox-instant] Fail: create subdivision returned empty");
                return;
            }
            newClaimId = newClaimIdOpt.get();
            Object newClaim = gp.findClaimById(newClaimId).orElse(null);
            if (newClaim != null) {
                gp.containerTrustPublic(newClaim);
                gp.saveClaim(newClaim);
            }
            claimDataStore.setMailbox(newClaimId, playerId);
            claimDataStore.setMailboxSignLocation(newClaimId, signBlock.getLocation());
            claimDataStore.save();
        }

        final String claimIdForPdc = newClaimId;
        final String ownerName = player.getName();
        // Store shared names in PDC for access check; display shows owner or "N players"
        java.util.List<String> sharedNames = new java.util.ArrayList<>();
        if (!line1.isEmpty()) sharedNames.add(line1.trim());
        if (!line2.isEmpty()) sharedNames.add(line2.trim());
        if (!line3.isEmpty()) sharedNames.add(line3.trim());
        final String sharedPdc = String.join(",", sharedNames);
        final int totalAccess = 1 + sharedNames.size();
        final String displayLine1 = totalAccess == 1
            ? (ChatColor.GREEN + ownerName)
            : (ChatColor.GREEN + String.valueOf(totalAccess) + " players");
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocation(plugin, signBlock.getLocation(), () -> {
            if (signBlock.getState() instanceof Sign sign) {
                PersistentDataContainer pdc = sign.getPersistentDataContainer();
                pdc.set(keyKind(), PersistentDataType.STRING, "MAILBOX");
                pdc.set(keyClaim(), PersistentDataType.STRING, claimIdForPdc);
                pdc.set(keyEcoAmt(), PersistentDataType.STRING, "0");
                pdc.set(keyEcoKind(), PersistentDataType.STRING, "MONEY");
                if (!sharedPdc.isEmpty()) pdc.set(keyMailboxShared(), PersistentDataType.STRING, sharedPdc);
                sign.setLine(0, ChatColor.BLUE + "" + ChatColor.BOLD + "[Mailbox]");
                sign.setLine(1, displayLine1);
                sign.setLine(2, ChatColor.BLACK + "(Click to open)");
                sign.setLine(3, "");
                sign.update(true);
            }
        });

        plugin.getMessages().send(player, "mailbox.self-created");
    }

    private int countSelfMailboxesInClaim(Object parentClaim, UUID playerId) {
        int count = 0;
        java.util.List<String> claimIds = new java.util.ArrayList<>();
        claimIds.add(gp.getClaimId(parentClaim).orElse(null));
        try {
            for (Object child : gp.getSubclaims(parentClaim)) {
                gp.getClaimId(child).ifPresent(claimIds::add);
            }
        } catch (Exception ignored) {}
        for (Map.Entry<String, UUID> e : claimDataStore.getAllMailboxes().entrySet()) {
            if (!e.getValue().equals(playerId)) continue;
            if (claimIds.contains(e.getKey())) {
                count++;
                continue;
            }
            // Virtual mailbox: count if container is inside this parent claim
            if (e.getKey().startsWith("v:")) {
                Location containerLoc = claimDataStore.getMailboxContainerLocation(e.getKey()).orElse(null);
                if (containerLoc != null) {
                    java.util.Optional<Object> at = gp.getClaimAt(containerLoc);
                    if (at.isPresent()) {
                        Object c = at.get();
                        while (c != null) {
                            if (c == parentClaim) {
                                count++;
                                break;
                            }
                            Object parent = gp.getParentClaim(c).orElse(null);
                            if (parent == null || parent == c) break; // top-level or no parent, avoid infinite loop
                            c = parent;
                        }
                    }
                }
            }
        }
        return count;
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

            // Sneak + right-click should behave like vanilla (placing blocks, etc.) and should NOT open mailboxes.
            if (event.getPlayer().isSneaking()) {
                return;
            }

            event.setCancelled(true);
            Player player = event.getPlayer();
            player.setCooldown(event.getMaterial(), 5);
            
            String claimId = pdc.get(keyClaim(), PersistentDataType.STRING);
            
            if (claimId == null) {
                plugin.getMessages().send(player, "mailbox.invalid-sign");
                return;
            }

            // Resolve by claimId; if missing (e.g. after protocol switch / reload), try by sign location
            if (!claimDataStore.isMailbox(claimId)) {
                String resolved = findMailboxClaimIdBySignLocation(block.getLocation());
                if (resolved != null) claimId = resolved;
            }

            // Check if mailbox is already owned
            if (claimDataStore.isMailbox(claimId)) {
                boolean fullAccess = isMailboxFullAccess(player, claimId);
                openMailboxChest(player, claimId, fullAccess);
            } else {
                // Mailbox not owned - show purchase confirmation
                showPurchaseConfirmation(player, claimId, pdc, block.getLocation());
            }
            return;
        }
        
        // Check if clicking on a container block that might be part of a mailbox (harden: always route through our view)
        if (isContainerBlock(block.getType())) {
            Player player = event.getPlayer();

            // Sneak + right-click should behave like vanilla (placing blocks, etc.) and should NOT open mailboxes.
            if (player.isSneaking()) {
                return;
            }

            // 1) Real-protocol mailboxes: container is inside a subdivision claim
            for (String claimId : claimDataStore.getAllMailboxes().keySet()) {
                if (claimId != null && claimId.startsWith("v:")) continue;
                Object claim = gp.findClaimById(claimId).orElse(null);
                if (claim == null) continue;
                GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
                World world = gp.getClaimWorld(claim).map(Bukkit::getWorld).orElse(null);
                if (world == null || corners == null || !world.equals(block.getWorld())) continue;
                if (block.getX() < corners.x1 || block.getX() > corners.x2 ||
                    block.getY() < corners.y1 || block.getY() > corners.y2 ||
                    block.getZ() < corners.z1 || block.getZ() > corners.z2) continue;
                event.setCancelled(true);
                player.setCooldown(event.getMaterial(), 5);
                if (claimDataStore.isMailbox(claimId)) {
                    boolean fullAccess = isMailboxFullAccess(player, claimId);
                    openMailboxChest(player, claimId, fullAccess);
                } else {
                    PersistentDataContainer signPdc = getMailboxSignPDC(claimId);
                    if (signPdc != null) {
                        Location signLoc = claimDataStore.getMailboxSignLocation(claimId).orElse(null);
                        showPurchaseConfirmation(player, claimId, signPdc, signLoc);
                    }
                }
                return;
            }
            // 2) Virtual-protocol mailboxes: container location stored in ClaimDataStore
            for (String claimId : claimDataStore.getAllMailboxes().keySet()) {
                if (claimId == null || !claimId.startsWith("v:")) continue;
                Location containerLoc = claimDataStore.getMailboxContainerLocation(claimId).orElse(null);
                if (containerLoc == null || containerLoc.getWorld() == null) continue;
                if (!containerLoc.getWorld().equals(block.getWorld())) continue;
                if (containerLoc.getBlockX() != block.getX() || containerLoc.getBlockY() != block.getY() || containerLoc.getBlockZ() != block.getZ()) continue;
                event.setCancelled(true);
                player.setCooldown(event.getMaterial(), 5);
                boolean fullAccess = isMailboxFullAccess(player, claimId);
                openMailboxChest(player, claimId, fullAccess);
                return;
            }
            // 3) Buyable mailbox: container has a for-sale sign attached (claimId = parent claim, not yet a mailbox)
            Object parentClaim = gp.getClaimAt(block.getLocation()).orElse(null);
            if (parentClaim != null) {
                String parentClaimId = gp.getClaimId(parentClaim).orElse(null);
                if (parentClaimId != null && !claimDataStore.isMailbox(parentClaimId)) {
                    Block[] faces = {
                        block.getRelative(BlockFace.NORTH), block.getRelative(BlockFace.SOUTH),
                        block.getRelative(BlockFace.EAST), block.getRelative(BlockFace.WEST),
                        block.getRelative(BlockFace.UP), block.getRelative(BlockFace.DOWN)
                    };
                    for (Block face : faces) {
                        if (!(face.getState() instanceof Sign sign)) continue;
                        if (!face.getType().name().contains("WALL_SIGN")) continue;
                        BlockData bd = face.getBlockData();
                        if (!(bd instanceof Directional dir)) continue;
                        if (!face.getRelative(dir.getFacing().getOppositeFace()).equals(block)) continue;
                        PersistentDataContainer pdc = sign.getPersistentDataContainer();
                        if (!"MAILBOX".equals(pdc.get(keyKind(), PersistentDataType.STRING))) continue;
                        String signClaimId = pdc.get(keyClaim(), PersistentDataType.STRING);
                        if (!parentClaimId.equals(signClaimId)) continue;
                        event.setCancelled(true);
                        player.setCooldown(event.getMaterial(), 5);
                        showPurchaseConfirmation(player, parentClaimId, pdc, face.getLocation());
                        return;
                    }
                }
            }
        }
    }

    /** Parsed kind;amount from line 1 for buyable mailbox (e.g. money;100). */
    private static class ParsedBuyable {
        final EcoKind kind;
        final String amount;
        final String displayLine;
        ParsedBuyable(EcoKind kind, String amount, String displayLine) {
            this.kind = kind;
            this.amount = amount;
            this.displayLine = displayLine;
        }
    }

    /** Parse line 1 as kind;amount (e.g. money;100) or symbol+amount / amount+symbol (e.g. $100, 100$) for buyable mailbox. Returns null if not valid. */
    private ParsedBuyable parseBuyableLine(String line1) {
        if (line1 == null || line1.isEmpty()) return null;
        String trimmed = line1.trim();
        // 1) kind;amount format (e.g. money;100, xp;50)
        int sep = trimmed.indexOf(';');
        if (sep > 0 && sep < trimmed.length() - 1) {
            String kindStr = trimmed.substring(0, sep).trim().toLowerCase(java.util.Locale.ROOT);
            String amountStr = trimmed.substring(sep + 1).trim();
            if (!amountStr.isEmpty()) {
                EcoKind kind = null;
                if (kindStr.equals("money") || kindStr.equals("$")) kind = EcoKind.MONEY;
                else if (kindStr.equals("xp") || kindStr.equals("experience") || kindStr.equals("exp")) kind = EcoKind.EXPERIENCE;
                else if (kindStr.equals("claimblocks") || kindStr.equals("blocks") || kindStr.equals("cb")) kind = EcoKind.CLAIMBLOCKS;
                else if (kindStr.equals("item")) kind = EcoKind.ITEM;
                if (kind != null) {
                    String display = ChatColor.GREEN + amountStr + " " + kindStr;
                    return new ParsedBuyable(kind, amountStr, display);
                }
            }
        }
        // 2) Currency symbol + amount or amount + symbol (e.g. $100, 100$, €50) — instant money buyable
        String amountStr = parseMoneyAmountFromSymbolLine(trimmed);
        if (amountStr != null) {
            String display = plugin.formatMoneyForSign(Double.parseDouble(amountStr));
            if (display == null || display.isEmpty()) display = ChatColor.GREEN + amountStr + " money";
            else display = ChatColor.GREEN + display;
            return new ParsedBuyable(EcoKind.MONEY, amountStr, display);
        }
        return null;
    }

    /**
     * Parse a line that is "symbol(s)+amount" or "amount+symbol(s)" (e.g. $100, 100$, €50).
     * Returns the normalized amount string (digits and one decimal, no commas) or null.
     */
    private String parseMoneyAmountFromSymbolLine(String line) {
        if (line == null || line.isEmpty()) return null;
        String symbols = dev.towki.gpexpansion.GPExpansionPlugin.getCurrencySymbolsForParsing();
        StringBuilder symbolClass = new StringBuilder();
        for (int i = 0; i < symbols.length(); i++) {
            char c = symbols.charAt(i);
            if (c == ']' || c == '\\' || c == '-') symbolClass.append('\\');
            symbolClass.append(c);
        }
        // Prefix: symbol(s) then amount
        java.util.regex.Pattern prefixPat = java.util.regex.Pattern.compile("^[" + symbolClass + "]+\\s*([\\d,.]+)\\s*$");
        java.util.regex.Matcher m = prefixPat.matcher(line);
        if (m.matches()) {
            String amount = m.group(1).replace(",", "").trim();
            if (isValidMoneyAmount(amount)) return amount;
        }
        // Suffix: amount then symbol(s)
        java.util.regex.Pattern suffixPat = java.util.regex.Pattern.compile("^\\s*([\\d,.]+)\\s*[" + symbolClass + "]+\\s*$");
        m = suffixPat.matcher(line);
        if (m.matches()) {
            String amount = m.group(1).replace(",", "").trim();
            if (isValidMoneyAmount(amount)) return amount;
        }
        return null;
    }

    private boolean isValidMoneyAmount(String amount) {
        if (amount == null || amount.isEmpty()) return false;
        try {
            double v = Double.parseDouble(amount);
            return v > 0 && Double.isFinite(v);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Find a mailbox claimId by sign block location (e.g. after protocol switch / reload when PDC claimId no longer matches store).
     */
    private String findMailboxClaimIdBySignLocation(Location signBlockLocation) {
        if (signBlockLocation == null || signBlockLocation.getWorld() == null) return null;
        for (String cid : claimDataStore.getAllMailboxes().keySet()) {
            Optional<Location> stored = claimDataStore.getMailboxSignLocation(cid);
            if (stored.isEmpty()) continue;
            Location loc = stored.get();
            if (loc.getWorld() == null) continue;
            if (!loc.getWorld().equals(signBlockLocation.getWorld())) continue;
            if (loc.getBlockX() != signBlockLocation.getBlockX() || loc.getBlockY() != signBlockLocation.getBlockY() || loc.getBlockZ() != signBlockLocation.getBlockZ()) continue;
            return cid;
        }
        return null;
    }

    /**
     * True if the player has full (owner) access: mailbox owner or shared names (PDC or legacy sign lines 1–3).
     */
    private boolean isMailboxFullAccess(Player player, String claimId) {
        UUID mailboxOwner = claimDataStore.getMailboxOwner(claimId).orElse(null);
        if (mailboxOwner != null && mailboxOwner.equals(player.getUniqueId())) return true;
        Location signLoc = claimDataStore.getMailboxSignLocation(claimId).orElse(null);
        if (signLoc == null) return false;
        if (!(signLoc.getBlock().getState() instanceof Sign sign)) return false;
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        String sharedPdc = pdc.get(keyMailboxShared(), PersistentDataType.STRING);
        if (sharedPdc != null && !sharedPdc.isEmpty()) {
            for (String name : sharedPdc.split(",")) {
                String n = name.trim();
                if (n.isEmpty()) continue;
                if (player.getName().equalsIgnoreCase(n)) return true;
                OfflinePlayer op = Bukkit.getOfflinePlayer(n);
                if (op.getUniqueId().equals(player.getUniqueId())) return true;
            }
            return false;
        }
        for (int i = 1; i <= 3; i++) {
            String raw = sign.getLine(i);
            if (raw == null) continue;
            String line = raw.replaceAll("§.", "").trim();
            if (line.isEmpty()) continue;
            if (player.getName().equalsIgnoreCase(line)) return true;
            OfflinePlayer op = Bukkit.getOfflinePlayer(line);
            if (op.getUniqueId().equals(player.getUniqueId())) return true;
        }
        return false;
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
                    ecoFormatted = plugin.formatMoneyForSign(amount);
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
        if (!isOwner) {
            Long until = mailboxCooldownUntil.get(player.getUniqueId());
            if (until != null && System.currentTimeMillis() < until) {
                plugin.getMessages().send(player, "mailbox.cooldown");
                return;
            }
        }
        Block containerBlock;
        if (claimId != null && claimId.startsWith("v:")) {
            // Virtual protocol: container location stored in ClaimDataStore
            Location containerLoc = claimDataStore.getMailboxContainerLocation(claimId).orElse(null);
            if (containerLoc == null || containerLoc.getWorld() == null) {
                plugin.getMessages().send(player, "mailbox.claim-not-found");
                return;
            }
            containerBlock = containerLoc.getBlock();
            if (!isContainerBlock(containerBlock.getType())) {
                plugin.getMessages().send(player, "mailbox.no-container");
                return;
            }
        } else {
            Object claim = gp.findClaimById(claimId).orElse(null);
            if (claim == null) {
                plugin.getMessages().send(player, "mailbox.claim-not-found");
                return;
            }
            containerBlock = findContainerInClaim(claim);
            if (containerBlock == null) {
                plugin.getMessages().send(player, "mailbox.no-container");
                return;
            }
        }
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
    private final Map<UUID, DepositSession> depositSessions = new HashMap<>();
    
    /** Container locations that currently have a non-owner viewer (prevents owner from opening and dupe glitch). */
    private final Map<String, Set<UUID>> containerViewersByKey = new HashMap<>();
    /** Container keys that currently have the owner viewing (prevents non-owner from opening and dupe glitch). */
    private final Set<String> containersOpenByOwner = new HashSet<>();
    /** After auto-kick, player cannot use mailboxes until this time (ms). */
    private final Map<UUID, Long> mailboxCooldownUntil = new HashMap<>();
    
    private static final long AUTO_KICK_TICKS = 5 * 60 * 20;   // 5 minutes
    private static final long COOLDOWN_MS = 30 * 60 * 1000L;    // 30 minutes
    
    private static String containerKey(Location loc) {
        if (loc == null || loc.getWorld() == null) return "";
        return loc.getWorld().getUID() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }
    
    /**
     * Tracks a non-owner's deposit session.
     * Real protocol: virtualInv is null; they open the real container, deposit-only enforced via originalSlotsAtOpen.
     * Virtual protocol: virtualInv is the snapshot; changes applied to real chest on close.
     */
    private static class DepositSession {
        final String claimId;
        final Location containerLoc;
        final String containerKey;
        /** Null for real protocol (open real container); non-null for virtual (snapshot, apply on close). */
        final Inventory virtualInv;
        /** Slots that had items when opened – non-owner cannot take from these (deposit-only). */
        final Set<Integer> originalSlotsAtOpen = new HashSet<>();
        /** Auto-kick task (virtual protocol only); cancel when player closes normally */
        TaskHandle autoKickTask;

        DepositSession(String claimId, Location containerLoc, String containerKey, Inventory virtualInv) {
            this.claimId = claimId;
            this.containerLoc = containerLoc;
            this.containerKey = containerKey;
            this.virtualInv = virtualInv;
        }
    }

    private void createMailboxView(Player player, Block containerBlock, String claimId, boolean isOwner) {
        if (!(containerBlock.getState() instanceof Container container)) {
            plugin.getMessages().send(player, "mailbox.invalid-container");
            return;
        }

        Inventory containerInv = container.getInventory();
        String key = containerKey(containerBlock.getLocation());

        boolean realProtocol = plugin.getConfigManager().isMailboxProtocolReal();

        if (isOwner) {
            // Owner: real = concurrent access (don't block, don't track). Virtual = block when non-owner viewing.
            if (!realProtocol) {
                Set<UUID> viewers = containerViewersByKey.get(key);
                if (viewers != null && !viewers.isEmpty()) {
                    plugin.getMessages().send(player, "mailbox.in-use-by-other");
                    return;
                }
            }
            viewingChests.put(player.getUniqueId(), containerBlock.getLocation());
            isOwnerViewing.put(player.getUniqueId(), true);
            if (!realProtocol) containersOpenByOwner.add(key);
            player.openInventory(containerInv);
            return;
        }

        // Non-owner: real = old implementation (open real container, deposit-only via slot restrictions). Virtual = snapshot, apply on close.
        if (!realProtocol) {
            if (containersOpenByOwner.contains(key)) {
                plugin.getMessages().send(player, "mailbox.in-use-by-other");
                return;
            }
        }

        if (realProtocol) {
            // Real protocol (old implementation): open actual container, track slots that had items so we block taking
            Location containerLoc = containerBlock.getLocation();
            DepositSession session = new DepositSession(claimId, containerLoc, key, null);
            for (int i = 0; i < containerInv.getSize(); i++) {
                ItemStack item = containerInv.getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    session.originalSlotsAtOpen.add(i);
                }
            }
            viewingChests.put(player.getUniqueId(), containerLoc);
            isOwnerViewing.put(player.getUniqueId(), false);
            depositSessions.put(player.getUniqueId(), session);
            playMailboxOpenSound(player, containerBlock.getType());
            player.openInventory(containerInv);
            return;
        }

        // Virtual protocol: create virtual inventory with same size as real container (e.g. 54 for double chest),
        // so snapshot includes all slots and no items are lost when applying on close
        int containerSize = containerInv.getSize();
        Inventory virtualInv = Bukkit.createInventory(null, containerSize, net.kyori.adventure.text.Component.text("Mailbox"));

        Location containerLoc = containerBlock.getLocation();
        DepositSession session = new DepositSession(claimId, containerLoc, key, virtualInv);
        for (int i = 0; i < containerSize; i++) {
            ItemStack item = containerInv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                virtualInv.setItem(i, item.clone());
                session.originalSlotsAtOpen.add(i);
            }
        }

        containerViewersByKey.computeIfAbsent(key, k -> new HashSet<>()).add(player.getUniqueId());
        viewingChests.put(player.getUniqueId(), containerLoc);
        isOwnerViewing.put(player.getUniqueId(), false);
        depositSessions.put(player.getUniqueId(), session);

        session.autoKickTask = dev.towki.gpexpansion.scheduler.SchedulerAdapter.runLaterEntity(
            plugin, player, () -> autoKickFromMailbox(player), AUTO_KICK_TICKS);

        playMailboxOpenSound(player, containerBlock.getType());
        player.openInventory(virtualInv);
    }

    private void playMailboxOpenSound(Player player, Material containerType) {
        if (containerType == null) return;
        if (containerType == Material.CHEST || containerType == Material.TRAPPED_CHEST) {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
        } else if (containerType == Material.BARREL) {
            player.playSound(player.getLocation(), Sound.BLOCK_BARREL_OPEN, 1.0f, 1.0f);
        } else if (containerType == Material.SHULKER_BOX || containerType.name().endsWith("_SHULKER_BOX")) {
            player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 1.0f, 1.0f);
        }
    }

    private void autoKickFromMailbox(Player player) {
        DepositSession session = depositSessions.get(player.getUniqueId());
        if (session == null) return;
        session.autoKickTask = null;
        player.closeInventory();
        mailboxCooldownUntil.put(player.getUniqueId(), System.currentTimeMillis() + COOLDOWN_MS);
        plugin.getMessages().send(player, "mailbox.auto-kicked");
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (!viewingChests.containsKey(player.getUniqueId())) return;

        Boolean isOwner = isOwnerViewing.get(player.getUniqueId());

        // Owner: full access to real container - allow all
        if (isOwner != null && isOwner) {
            return;
        }

        // Non-owner: virtual inventory - can add/move freely EXCEPT cannot take from original slots
        // Original slots = had items in snapshot (owner's items + player's prior deposits)
        DepositSession session = depositSessions.get(player.getUniqueId());
        if (session == null) {
            event.setCancelled(true);
            return;
        }

        Inventory topInv = getTopInventory(event.getView());
        if (topInv == null) {
            event.setCancelled(true);
            return;
        }

        int clickedSlot = event.getRawSlot();
        int topSize = topInv.getSize();
        boolean clickedTop = (clickedSlot >= 0 && clickedSlot < topSize);

        if (clickedTop && session.originalSlotsAtOpen.contains(clickedSlot)) {
            InventoryAction action = event.getAction();
            // Block taking from original slots (deposit-only)
            if (action == InventoryAction.PICKUP_ALL || action == InventoryAction.PICKUP_HALF ||
                action == InventoryAction.PICKUP_ONE || action == InventoryAction.PICKUP_SOME ||
                action == InventoryAction.MOVE_TO_OTHER_INVENTORY || action == InventoryAction.HOTBAR_SWAP ||
                action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.SWAP_WITH_CURSOR) {
                event.setCancelled(true);
                plugin.getMessages().send(player, "mailbox.deposit-only");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (!viewingChests.containsKey(player.getUniqueId())) return;

        Boolean isOwner = isOwnerViewing.get(player.getUniqueId());
        if (isOwner != null && isOwner) return;

        // Virtual protocol non-owner: allow drag. Real protocol (old): block all drags for non-owners.
        DepositSession session = depositSessions.get(player.getUniqueId());
        if (session != null && session.virtualInv != null) return;

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

        if (isOwner != null && isOwner) {
            containersOpenByOwner.remove(containerKey(chestLoc));
            container.update();
            checkStorageWarnings(player, container.getInventory(), chestLoc);
            return;
        }

        // Non-owner: real protocol = items already in chest (no snapshot). Virtual = apply snapshot on close.
        if (session == null) return;

        if (session.autoKickTask != null) {
            session.autoKickTask.cancel();
            session.autoKickTask = null;
        }
        if (session.virtualInv == null) {
            // Real protocol (old implementation): items already in chest, just ensure persistence
            container.update();
            return;
        }

        Set<UUID> viewers = containerViewersByKey.get(session.containerKey);
        if (viewers != null) {
            viewers.remove(player.getUniqueId());
            if (viewers.isEmpty()) containerViewersByKey.remove(session.containerKey);
        }

        Inventory virtualInv = session.virtualInv;

        // Snapshot contents from our virtual inventory
        List<ItemStack> toSave = new ArrayList<>();
        for (int i = 0; i < virtualInv.getSize(); i++) {
            ItemStack item = virtualInv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                toSave.add(item.clone());
            }
        }

        // Session has virtualInv => we're applying a virtual snapshot. If owner currently has the real chest open, don't overwrite (would dupe/conflict) - return items to non-owner. Use session state, not current config, so reload during session doesn't allow overwriting while owner is viewing.
        if (containersOpenByOwner.contains(session.containerKey)) {
            for (ItemStack item : toSave) {
                if (item != null && item.getType() != Material.AIR) {
                    player.getInventory().addItem(item).values().forEach(drop ->
                        player.getWorld().dropItemNaturally(player.getLocation(), drop));
                }
            }
            if (!toSave.isEmpty()) {
                plugin.getMessages().send(player, "mailbox.items-returned");
            }
            return;
        }

        // Apply to real chest using snapshot inventory (required for persistence)
        Block block = session.containerLoc.getBlock();
        if (block.getState() instanceof Container realContainer) {
            Inventory snapshotInv;
            try {
                snapshotInv = realContainer.getSnapshotInventory();
            } catch (NoSuchMethodError | AbstractMethodError e) {
                snapshotInv = realContainer.getInventory(); // fallback for older API
            }
            snapshotInv.clear();
            List<ItemStack> overflow = new ArrayList<>();
            for (ItemStack item : toSave) {
                java.util.Map<Integer, ItemStack> leftover = snapshotInv.addItem(item);
                if (!leftover.isEmpty()) {
                    overflow.addAll(leftover.values());
                }
            }
            try {
                realContainer.update(true, false);
            } catch (NoSuchMethodError e) {
                realContainer.update();
            }

            if (!overflow.isEmpty()) {
                for (ItemStack item : overflow) {
                    if (item != null && item.getType() != Material.AIR) {
                        player.getInventory().addItem(item).values().forEach(drop ->
                            player.getWorld().dropItemNaturally(player.getLocation(), drop));
                    }
                }
                plugin.getMessages().send(player, "mailbox.items-returned");
            }
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
            plugin.getMessages().send(player, "mailbox.full-warning");
        } else if (emptySlots <= 2) {
            plugin.getMessages().send(player, "mailbox.almost-full-warning",
                "{slots}", String.valueOf(emptySlots));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Check owned mailboxes for storage warnings (use Folia-compatible scheduler)
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runLaterEntity(
            plugin, player, () -> checkAllMailboxStorage(player), 100L);
    }

    private void checkAllMailboxStorage(Player player) {
        for (Map.Entry<String, UUID> entry : claimDataStore.getAllMailboxes().entrySet()) {
            if (!entry.getValue().equals(player.getUniqueId())) continue;
            String claimId = entry.getKey();
            if (claimId != null && claimId.startsWith("v:")) {
                Location containerLoc = claimDataStore.getMailboxContainerLocation(claimId).orElse(null);
                if (containerLoc != null) {
                    dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocation(
                        plugin, containerLoc, () -> checkMailboxStorageAtLocation(player, containerLoc));
                }
                continue;
            }
            Object claim = gp.findClaimById(claimId).orElse(null);
            if (claim != null) {
                Location claimLoc = getClaimLocation(claim);
                if (claimLoc != null) {
                    dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocation(
                        plugin, claimLoc, () -> checkMailboxStorageAt(player, claim));
                }
            }
        }
    }
    
    private Location getClaimLocation(Object claim) {
        try {
            Object lesser = claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
            return (Location) lesser;
        } catch (Exception e) {
            return null;
        }
    }
    
    private void checkMailboxStorageAt(Player player, Object claim) {
        Block chestBlock = findContainerInClaim(claim);
        if (chestBlock != null && chestBlock.getState() instanceof Container container) {
            checkMailboxStorageAtLocation(player, chestBlock.getLocation(), container.getInventory());
        }
    }

    private void checkMailboxStorageAtLocation(Player player, Location containerLoc) {
        Block block = containerLoc.getBlock();
        if (block.getState() instanceof Container container) {
            checkMailboxStorageAtLocation(player, containerLoc, container.getInventory());
        }
    }

    private void checkMailboxStorageAtLocation(Player player, Location containerLoc, Inventory chestInv) {
        int emptySlots = 0;
        for (ItemStack item : chestInv.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                emptySlots++;
            }
        }
        int x = containerLoc.getBlockX();
        int y = containerLoc.getBlockY();
        int z = containerLoc.getBlockZ();
        if (emptySlots == 0) {
            plugin.getMessages().send(player, "mailbox.storage-full-warning",
                "{x}", String.valueOf(x), "{y}", String.valueOf(y), "{z}", String.valueOf(z));
        } else if (emptySlots <= 9) {
            plugin.getMessages().send(player, "mailbox.storage-almost-full-warning",
                "{x}", String.valueOf(x), "{y}", String.valueOf(y), "{z}", String.valueOf(z),
                "{slots}", String.valueOf(emptySlots));
        }
    }

    public boolean handlePurchaseConfirmation(Player player, String claimId, Location signLocation) {
        // Check if still available
        if (claimDataStore.isMailbox(claimId)) {
            plugin.getMessages().send(player, "mailbox.already-purchased");
            return true;
        }

        // Buyable sign: claimId is parent claim ID; create mailbox at sign's container then register
        PersistentDataContainer pdc = null;
        Block signBlock = signLocation != null ? signLocation.getBlock() : null;
        if (signBlock != null && signBlock.getState() instanceof Sign signState) {
            pdc = signState.getPersistentDataContainer();
        }
        if (pdc == null) {
            pdc = getMailboxSignPDC(claimId);
            signBlock = claimDataStore.getMailboxSignLocation(claimId).map(Location::getBlock).orElse(null);
        }
        if (pdc == null) {
            plugin.getMessages().send(player, "mailbox.sign-not-found");
            return true;
        }

        String ecoAmt = pdc.get(keyEcoAmt(), PersistentDataType.STRING);
        String kindName = pdc.get(keyEcoKind(), PersistentDataType.STRING);
        EcoKind kind;
        try {
            kind = EcoKind.valueOf(kindName != null ? kindName : "MONEY");
        } catch (IllegalArgumentException e) {
            kind = EcoKind.MONEY;
        }

        // Validate container, claim, and (for virtual) world before charging
        Block containerBlock = null;
        if (signBlock != null && signBlock.getBlockData() instanceof Directional dir) {
            containerBlock = signBlock.getRelative(dir.getFacing().getOppositeFace());
        }
        if (containerBlock == null || !isContainerBlock(containerBlock.getType())) {
            plugin.getMessages().send(player, "mailbox.no-container");
            return true;
        }

        Object parentClaim = gp.findClaimById(claimId).orElse(null);
        if (parentClaim == null) {
            plugin.getMessages().send(player, "mailbox.claim-not-found");
            return true;
        }

        boolean realProtocol = plugin.getConfigManager().isMailboxProtocolReal();
        if (!realProtocol) {
            World w = containerBlock.getWorld();
            if (w == null) {
                plugin.getMessages().send(player, "mailbox.claim-not-found");
                return true;
            }
        }

        if (!processPayment(player, kind, ecoAmt)) {
            plugin.getMessages().send(player, "mailbox.payment-failed");
            return true;
        }

        String newClaimId;
        if (!realProtocol) {
            World w = containerBlock.getWorld();
            newClaimId = "v:" + w.getUID() + ":" + containerBlock.getX() + ":" + containerBlock.getY() + ":" + containerBlock.getZ();
            claimDataStore.setMailbox(newClaimId, player.getUniqueId());
            claimDataStore.setMailboxSignLocation(newClaimId, signLocation);
            claimDataStore.setMailboxContainerLocation(newClaimId, containerBlock.getLocation());
        } else {
            java.util.Optional<String> newClaimIdOpt = gp.isGP3D()
                ? gp.create1x1SubdivisionAt(containerBlock.getLocation(), player.getUniqueId(), parentClaim)
                : gp.create1x1Subdivision2DAt(containerBlock.getLocation(), player.getUniqueId(), parentClaim);
            if (newClaimIdOpt.isEmpty()) {
                refundPayment(player, kind, ecoAmt);
                plugin.getMessages().send(player, "mailbox.instant-creation-failed");
                return true;
            }
            newClaimId = newClaimIdOpt.get();
            Object newClaim = gp.findClaimById(newClaimId).orElse(null);
            if (newClaim != null) {
                gp.containerTrustPublic(newClaim);
                gp.saveClaim(newClaim);
            }
            claimDataStore.setMailbox(newClaimId, player.getUniqueId());
            claimDataStore.setMailboxSignLocation(newClaimId, signLocation);
        }
        claimDataStore.save();

        // Update sign PDC so claimId points to the new mailbox (future clicks open it)
        if (signBlock != null && signBlock.getState() instanceof Sign signForPdc) {
            signForPdc.getPersistentDataContainer().set(keyClaim(), PersistentDataType.STRING, newClaimId);
            signForPdc.update(true);
        }

        // Real protocol: grant container trust to owner
        if (!newClaimId.startsWith("v:")) {
            Object newClaim = gp.findClaimById(newClaimId).orElse(null);
            if (newClaim != null) {
                gp.grantInventoryTrust(player, player.getName(), newClaim);
            }
        }

        updateMailboxSign(newClaimId, player.getName());
        plugin.getMessages().send(player, "mailbox.purchase-success");
        return true;
    }

    public PersistentDataContainer getMailboxSignPDC(String claimId) {
        // First check stored sign location
        Location storedLoc = claimDataStore.getMailboxSignLocation(claimId).orElse(null);
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
                        plugin.getMessages().send(player, "mailbox.economy-not-available");
                        return false;
                    }
                    if (!plugin.hasMoney(player, amt)) {
                        plugin.getMessages().send(player, "mailbox.not-enough-money");
                        return false;
                    }
                    plugin.withdrawMoney(player, amt);
                    break;
                case EXPERIENCE:
                    int totalExp = getPlayerTotalExperience(player);
                    int cost = (int) amt;
                    if (totalExp < cost) {
                        plugin.getMessages().send(player, "mailbox.not-enough-experience");
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

    /**
     * Refund a payment when mailbox creation fails after payment was taken.
     */
    private void refundPayment(Player player, EcoKind kind, String amount) {
        try {
            double amt = Double.parseDouble(amount);
            switch (kind) {
                case MONEY:
                    if (plugin.isEconomyAvailable()) {
                        plugin.depositMoney(player, amt);
                    }
                    break;
                case EXPERIENCE:
                    int cost = (int) amt;
                    int current = getPlayerTotalExperience(player);
                    setTotalExperience(player, current + cost);
                    break;
                case CLAIMBLOCKS:
                case ITEM:
                    break;
            }
        } catch (NumberFormatException ignored) { }
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
        Location storedLoc = claimDataStore.getMailboxSignLocation(claimId).orElse(null);
        if (storedLoc != null) {
            Block block = storedLoc.getBlock();
            if (block.getState() instanceof Sign sign) {
                PersistentDataContainer signPdc = sign.getPersistentDataContainer();
                String signType = signPdc.get(keyKind(), PersistentDataType.STRING);
                if ("MAILBOX".equals(signType)) {
                    sign.setLine(0, ChatColor.BLUE + "" + ChatColor.BOLD + "[Mailbox]");
                    sign.setLine(1, ChatColor.GREEN + ownerName);
                    sign.setLine(2, ChatColor.BLACK + "(Click to open)");
                    sign.setLine(3, "");
                    sign.update(true);
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
                            sign.line(0, Component.text(ChatColor.BLUE + "" + ChatColor.BOLD + "[Mailbox]"));
                            sign.line(1, Component.text(ChatColor.GREEN + ownerName));
                            sign.line(2, Component.text(ChatColor.BLACK + "(Click to open)"));
                            sign.line(3, Component.text(""));
                            sign.update(true);
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
