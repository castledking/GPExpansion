package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.RentalStore;
import dev.towki.gpexpansion.util.EcoKind;
import dev.towki.gpexpansion.util.RenewalSpec;
import dev.towki.gpexpansion.permission.SignLimitManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.apache.commons.lang.StringUtils;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class SignListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final GPBridge gp = new GPBridge();
    private final SignLimitManager signLimitManager;

    // PDC keys
    private NamespacedKey keyKind() { return new NamespacedKey(plugin, "sign.kind"); }
    private NamespacedKey keyClaim() { return new NamespacedKey(plugin, "sign.claimId"); }
    private NamespacedKey keyEcoAmt() { return new NamespacedKey(plugin, "sign.ecoAmt"); }
    private NamespacedKey keyPerClick() { return new NamespacedKey(plugin, "sign.perClick"); }
    private NamespacedKey keyMaxCap() { return new NamespacedKey(plugin, "sign.maxCap"); }
    private NamespacedKey keyItemB64() { return new NamespacedKey(plugin, "item-b64"); }
    private NamespacedKey keyRenter() { return new NamespacedKey(plugin, "rent.renter"); }
    private NamespacedKey keyExpiry() { return new NamespacedKey(plugin, "rent.expiry"); }
    private NamespacedKey keyScrollIdx() { return new NamespacedKey(plugin, "sign.scrollIdx"); }

    public SignListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.signLimitManager = new SignLimitManager(plugin);
        plugin.getLogger().info("SignListener has been instantiated");
        plugin.getLogger().info("Plugin version: " + plugin.getDescription().getVersion());
        plugin.getLogger().info("Server version: " + plugin.getServer().getVersion());
    }

    // Helper method to safely get a line from SignChangeEvent
    private String safeLine(SignChangeEvent event, int line) {
        Component component = event.line(line);
        return component != null ? LegacyComponentSerializer.legacySection().serialize(component) : "";
    }
    
    // Helper method to format duration in a short format (e.g., 1h, 30m, 7d)
    private String formatDurationShort(String duration) {
        if (duration == null || duration.isEmpty()) return "";
        
        // Simple implementation - adjust based on your duration format
        return duration.replace("hour", "h")
                      .replace("minute", "m")
                      .replace("day", "d")
                      .replace(" ", "");
    }

    private String stripLegacyColors(String input) {
        if (input == null || input.isEmpty()) return "";
        return input.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }
    
    // Helper method to format line 3 of sign text
    private String formatLine3(EcoKind kind, RenewalSpec renewal, ItemStack item) {
        if (renewal == null) return "";
        
        // Get amount and per click from renewal spec
        String amount = renewal.ecoAmtRaw;
        String perClick = formatDurationShort(renewal.perClick);
        
        switch (kind) {
            case MONEY:
                return String.format("%s$ per %s", amount, perClick);
            case EXPERIENCE:
                return String.format("%s XP per %s", amount, perClick);
            case CLAIMBLOCKS:
                return String.format("%s blocks per %s", amount, perClick);
            case ITEM:
                String itemName = formatItemName(item);
                return String.format("%s %s per %s", amount, itemName, perClick);
            default:
                return "";
        }
    }
    
    // Parse EcoKind from string input
    private EcoKind parseEcoKind(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Economy kind cannot be empty");
        }
        
        String lower = input.toLowerCase();
        if (lower.startsWith("money") || lower.startsWith("$")) return EcoKind.MONEY;
        if (lower.startsWith("xp") || lower.startsWith("exp") || lower.startsWith("experience")) return EcoKind.EXPERIENCE;
        if (lower.startsWith("claimblocks") || lower.startsWith("blocks") || lower.startsWith("cb")) return EcoKind.CLAIMBLOCKS;
        if (lower.startsWith("item")) return EcoKind.ITEM;
        
        throw new IllegalArgumentException("Invalid economy kind: " + input);
    }
    
    // Parse renewal specification from string
    private RenewalSpec parseRenewal(String input, EcoKind kind) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Renewal specification cannot be empty");
        }
        
        // Split by semicolon and trim each part
        String[] parts = input.split(";");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid renewal format. Expected: <ecoAmount>;<perClick>;<maxCap>");
        }
        
        String amount = parts[0].trim();
        String perClick = parts[1].trim();
        String maxCap = parts[2].trim();
        
        // Validate the amount is a positive number
        if (!amount.matches("^\\d+$")) {
            throw new IllegalArgumentException("Amount must be a positive number");
        }
        
        // Validate durations have valid format (e.g., 7d, 10w, 24h)
        if (!perClick.matches("^\\d+[dhmsw]$")) {
            throw new IllegalArgumentException("Per-click duration must be a number followed by d (days), h (hours), m (minutes), s (seconds), or w (weeks)");
        }
        
        if (!maxCap.matches("^\\d+[dhmsw]$")) {
            throw new IllegalArgumentException("Max duration must be a number followed by d (days), h (hours), m (minutes), s (seconds), or w (weeks)");
        }
        
        return new RenewalSpec(amount, perClick, maxCap);
    }
    
    // Parse formatted ID from string
    private String parseFormattedId(String input) {
        if (input == null || input.isEmpty()) return "";
        return input.replaceAll("[^0-9]", "");
    }
    
    // Parse formatted EcoKind from string
    private EcoKind parseFormattedEcoKind(String input) {
        if (input == null || input.isEmpty()) return null;
        return parseEcoKind(input);
    }
    
    // Parse formatted renewal from string
    private RenewalSpec parseFormattedRenewal(String input, EcoKind kind) {
        return parseRenewal(input, kind);
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignCreate(@NotNull SignChangeEvent event) {
        // Log basic sign creation info
        plugin.getLogger().info("===== SIGN PLACEMENT DETECTED =====");
        plugin.getLogger().info("Player: " + event.getPlayer().getName());
        plugin.getLogger().info("Block Type: " + event.getBlock().getType());
        plugin.getLogger().info("Location: " + event.getBlock().getLocation());
        
        // Log all sign lines
        plugin.getLogger().info("Sign Content:");
        for (int i = 0; i < 4; i++) {
            Component line = event.line(i);
            String lineText = line != null ? line.toString() : "";
            plugin.getLogger().info(String.format("  Line %d: '%s' (Type: %s)", 
                i, 
                lineText,
                line != null ? line.getClass().getSimpleName() : "null"
            ));
        }
                            
        Player player = event.getPlayer();
        Component line0 = event.line(0);
        if (line0 == null) {
            plugin.getLogger().info("Sign creation aborted: Line 0 is null");
            return;
        }
        
        String line0Text = LegacyComponentSerializer.legacySection().serialize(line0).trim();
        String strippedLine0 = stripLegacyColors(line0Text).trim();
        boolean rent = strippedLine0.equalsIgnoreCase("[Rent Claim]") || strippedLine0.equalsIgnoreCase("[Rented]") || strippedLine0.equalsIgnoreCase("[Renew]");
        boolean sell = strippedLine0.equalsIgnoreCase("[Buy Claim]") || strippedLine0.equalsIgnoreCase("[Sell Claim]");
        boolean mailbox = strippedLine0.equalsIgnoreCase("[Mailbox]");
        boolean invalid = strippedLine0.equalsIgnoreCase("[Invalid Rental]") || strippedLine0.equalsIgnoreCase("[Invalid Listing]");

        if (invalid) {
            return;
        }

        if (!rent && !sell && !mailbox) {
            return; // Not a rent/sell/mailbox sign, ignore
        }

        // Debug logging
        plugin.getLogger().info("Creating sign with content:");
        for (int i = 0; i < 4; i++) {
            Component line = event.line(i);
            String lineText = line != null ? line.toString() : "";
            plugin.getLogger().info("Line " + i + ": '" + lineText + "'");
        }

        boolean hasPerm = rent ? player.hasPermission("griefprevention.sign.create.rent") : 
                       (sell ? player.hasPermission("griefprevention.sign.create.buy") : 
                               player.hasPermission("griefprevention.sign.create.mailbox"));
        if (!hasPerm) {
            player.sendMessage(Component.text("You don't have permission to create this sign.", NamedTextColor.RED));
            return; // Let it place but don't format
        }

        // Check sign limits
        if (rent && !signLimitManager.canCreateRentSign(player)) {
            int limit = signLimitManager.getRentLimit(player);
            int current = signLimitManager.getCurrentRentSigns(player);
            player.sendMessage(Component.text("You have reached your rent sign limit (" + current + "/" + limit + ").", NamedTextColor.RED));
            return;
        }
        if (sell && !signLimitManager.canCreateSellSign(player)) {
            int limit = signLimitManager.getSellLimit(player);
            int current = signLimitManager.getCurrentSellSigns(player);
            player.sendMessage(Component.text("You have reached your sell sign limit (" + current + "/" + limit + ").", NamedTextColor.RED));
            return;
        }
        if (mailbox && !signLimitManager.canCreateMailboxSign(player)) {
            int limit = signLimitManager.getMailboxLimit(player);
            int current = signLimitManager.getCurrentMailboxSigns(player);
            player.sendMessage(Component.text("You have reached your mailbox sign limit (" + current + "/" + limit + ").", NamedTextColor.RED));
            return;
        }

        // Read raw user inputs - handle both line-based and semicolon-delimited formats
        String line1 = stripLegacyColors(safeLine(event, 1)).trim();
        String line2 = stripLegacyColors(safeLine(event, 2)).trim();
        String line3 = stripLegacyColors(safeLine(event, 3)).trim();
        
        plugin.getLogger().info("Processing sign creation - Line1: " + line1 + ", Line2: " + line2 + ", Line3: " + line3);
        
        String claimId;
        String ecoKindStr;
        String renewalSpecStr;
        
        // Check if line 1 contains semicolons (semicolon-delimited format)
        if (line1.contains(";")) {
            String[] parts = line1.split(";");
            if (sell) {
                // For sell signs: [Sell];<claimId>;<ecoKind>;<ecoAmount>
                if (parts.length < 4) {
                    player.sendMessage(Component.text("Invalid format. Expected: [Buy Claim];<claimId>;<ecoKind>;<ecoAmount>", NamedTextColor.RED));
                    return;
                }
                claimId = parts[1].trim();
                ecoKindStr = parts[2].trim();
                renewalSpecStr = parts[3].trim();
            } else if (rent) {
                // For rent signs: [Rent];<claimId>;<ecoKind>;<ecoAmount>;<perClick>;<maxCap>
                if (parts.length < 5) {
                    player.sendMessage(Component.text("Invalid format. Expected: [Rent Claim];<claimId>;<ecoKind>;<ecoAmount>;<perClick>;<maxCap>", NamedTextColor.RED));
                    return;
                }
                claimId = parts[1].trim();
                ecoKindStr = parts[2].trim();
                renewalSpecStr = parts[3].trim() + ";" + parts[4].trim() + ";" + (parts.length > 5 ? parts[5].trim() : "");
            } else {
                // For mailbox signs: [Mailbox];<claimId>;<ecoKind>;<amount>
                if (parts.length < 4) {
                    player.sendMessage(Component.text("Invalid format. Expected: [Mailbox];<claimId>;<ecoKind>;<amount>", NamedTextColor.RED));
                    return;
                }
                claimId = parts[1].trim();
                ecoKindStr = parts[2].trim();
                renewalSpecStr = parts[3].trim();
            }
        } else {
            // Old line-based format
            claimId = line1;
            ecoKindStr = line2;
            renewalSpecStr = line3;
        }

        // Validate claim ID exists
        if (claimId.isEmpty()) {
            player.sendMessage(Component.text("Line 2 must be a ClaimID.", NamedTextColor.RED));
            return;
        }
        if (!gp.findClaimById(claimId).isPresent()) {
            player.sendMessage(Component.text("Claim ID not found: " + claimId, NamedTextColor.RED));
            return;
        }

        // For mailbox signs, validate that the claim is a 1x1x1 3D subdivision
        // Note: The sign can be placed anywhere, but it must reference a valid 1x1x1 claim
        if (mailbox) {
            // Check if we're running on GP3D fork
            if (!gp.isGP3D()) {
                player.sendMessage(Component.text("Mailbox signs require GP3D (GriefPrevention 3D) to be installed.", NamedTextColor.RED));
                return;
            }
            
            Object claim = gp.findClaimById(claimId).get();
            
            // Check if it's a subdivision and 3D claim
            if (!gp.isSubdivision(claim) || !gp.is3DClaim(claim)) {
                player.sendMessage(Component.text("Mailbox signs must reference a 3D subdivision.", NamedTextColor.RED));
                return;
            }
            
            int[] dimensions = gp.getClaimDimensions(claim);
            if (dimensions[0] != 1 || dimensions[1] != 1 || dimensions[2] != 1) {
                player.sendMessage(Component.text("Mailbox signs must reference a 1x1x1 subdivision (current: " + 
                    dimensions[0] + "x" + dimensions[1] + "x" + dimensions[2] + ").", NamedTextColor.RED));
                return;
            }
        }

        // Validate eco tag and amount format
        EcoKind kind;
        boolean economyAvailable = plugin.isEconomyAvailable();
        try {
            kind = parseEcoKind(ecoKindStr);
        } catch (IllegalArgumentException e) {
            formatInvalidSign(event, rent, claimId, ecoKindStr, renewalSpecStr);
            player.sendMessage(Component.text("Invalid economy type: " + ecoKindStr, NamedTextColor.RED));
            return;
        }

        if (kind == EcoKind.MONEY && !economyAvailable) {
            formatInvalidSign(event, rent, claimId, ecoKindStr, renewalSpecStr);
            player.sendMessage(Component.text("Money rentals require a Vault economy provider.", NamedTextColor.RED));
            return;
        }

        if (kind == null) {
            formatInvalidSign(event, rent, claimId, ecoKindStr, renewalSpecStr);
            player.sendMessage(Component.text("Invalid economy tag.", NamedTextColor.RED));
            return;
        }
        // Item requires holding an item (not air)
        final ItemStack itemInHand;
        if (kind == EcoKind.ITEM) {
            ItemStack tmp = player.getInventory().getItemInOffHand();
            if (tmp == null || tmp.getType() == Material.AIR) {
                player.sendMessage(Component.text("Hold the item in your offhand when creating a [Buy Claim]/[Rent Claim] Item sign.", NamedTextColor.RED));
                return;
            }
            itemInHand = tmp;
        } else {
            itemInHand = null;
        }

        // Validate renewal: For sell signs, just ecoAmount; for rent signs, <ecoAmt>[L?];<perClickDuration>;<maxCapDuration>
        RenewalSpec renewal;
        try {
            if (sell || mailbox) {
                // For sell and mailbox signs, just validate the economy amount
                renewal = new RenewalSpec(renewalSpecStr, "1", "1"); // dummy values for perClick and maxCap
            } else {
                renewal = parseRenewal(renewalSpecStr, kind);
            }
        } catch (IllegalArgumentException iae) {
            player.sendMessage(Component.text(iae.getMessage(), NamedTextColor.RED));
            return;
        }

        // Format output lines per spec
        String displayName = sell ? "Buy Claim" : (rent ? "Rent Claim" : "Mailbox");
        String colorCode = mailbox ? "&9&l" : "&a&l"; // Blue for mailbox, green for rent/sell
        event.line(0, LegacyComponentSerializer.legacyAmpersand().deserialize(colorCode + "[" + displayName + "]"));
        event.line(1, LegacyComponentSerializer.legacyAmpersand().deserialize("&0ID: &6" + claimId));
        
        // Determine formatted amount
        String amountDisplay;
        switch (kind) {
            case MONEY:
                amountDisplay = formatMoneyForSign(renewal.ecoAmtRaw);
                break;
            case EXPERIENCE:
                amountDisplay = renewal.ecoAmtRaw + " XP";
                break;
            case CLAIMBLOCKS:
                amountDisplay = renewal.ecoAmtRaw + " blocks";
                break;
            case ITEM:
                String itemName = itemInHand != null ? formatItemName(itemInHand) : "Item";
                amountDisplay = renewal.ecoAmtRaw + " " + itemName;
                break;
            default:
                amountDisplay = renewal.ecoAmtRaw;
        }

        // Format line 3 based on sign type
        if (mailbox) {
            event.line(2, Component.text(amountDisplay, NamedTextColor.BLACK));
            event.line(3, Component.text("Click to Buy!", NamedTextColor.GREEN));
        } else if (sell) {
            event.line(2, Component.text(amountDisplay, NamedTextColor.BLACK));
            event.line(3, Component.text("", NamedTextColor.BLACK)); // Empty line for sell signs
        } else {
            String priceLine = amountDisplay + "/" + renewal.perClick;
            event.line(2, Component.text(priceLine, NamedTextColor.BLACK));
            // Format line 4: Max duration (e.g., "Max: 10 weeks")
            String maxDisplay = "Max: " + renewal.maxCap;
            event.line(3, Component.text(maxDisplay, NamedTextColor.BLACK));
        }
        
        String logInfo = sell ? amountDisplay : (amountDisplay + "/" + renewal.perClick + ", Max: " + renewal.maxCap);
        plugin.getLogger().info("Formatted sign - ClaimID: " + claimId + ", Type: " + kind + ", Price: " + logInfo);

        // Persist metadata on the sign
        org.bukkit.block.BlockState state = event.getBlock().getState();
        if (state instanceof Sign) {
            // Schedule the PDC update for the next tick to ensure the sign is fully placed
            final String finalClaimId = claimId;
            final ItemStack finalItemInHand = (kind == EcoKind.ITEM) ? itemInHand : null;
            
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                org.bukkit.block.Block block = event.getBlock();
                if (block.getState() instanceof Sign sign) {
                    PersistentDataContainer pdc = sign.getPersistentDataContainer();
                    String signType = mailbox ? "MAILBOX" : (rent ? "RENT" : "SELL");
                    pdc.set(keyKind(), PersistentDataType.STRING, signType);
                    pdc.set(keyClaim(), PersistentDataType.STRING, finalClaimId);
                    pdc.set(keyEcoAmt(), PersistentDataType.STRING, renewal.ecoAmtRaw);
                    pdc.set(keyPerClick(), PersistentDataType.STRING, renewal.perClick);
                    pdc.set(keyMaxCap(), PersistentDataType.STRING, renewal.maxCap);
                    
                    // Store the economy kind for all sign types
                    pdc.set(new NamespacedKey(plugin, "sign.ecoKind"), PersistentDataType.STRING, kind.name());
                    
                    if (finalItemInHand != null) {
                        pdc.set(keyItemB64(), PersistentDataType.STRING, encodeItem(finalItemInHand));
                    }
                    
                    sign.update(true);
                }
            });
        }

        player.sendMessage(Component.text("Sign created for claim ", NamedTextColor.GREEN)
            .append(Component.text(claimId, NamedTextColor.GOLD))
            .append(Component.text(".", NamedTextColor.GREEN)));
    }

    private void formatInvalidSign(SignChangeEvent event, boolean rent, String claimId, String ecoKindStr, String renewalSpecStr) {
        event.line(0, Component.text(rent ? "[Invalid Rental]" : "[Invalid Listing]", NamedTextColor.DARK_GRAY, TextDecoration.BOLD));
        event.line(1, Component.text(claimId != null ? claimId : "", NamedTextColor.DARK_GRAY));
        event.line(2, Component.text(ecoKindStr != null ? ecoKindStr : "", NamedTextColor.DARK_GRAY));
        event.line(3, Component.text(renewalSpecStr != null ? renewalSpecStr : "", NamedTextColor.DARK_GRAY));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignInteract(@NotNull PlayerInteractEvent event) {
        // Only process right-clicks on blocks with main hand
        if (event.getHand() != EquipmentSlot.HAND || 
            event.getAction() != Action.RIGHT_CLICK_BLOCK || 
            event.getClickedBlock() == null) {
            return;
        }
        
        // Check if the clicked block is a sign
        if (!(event.getClickedBlock().getState() instanceof Sign sign)) {
            return;
        }
        
        // Get the front side of the sign
        SignSide front = sign.getSide(org.bukkit.block.sign.Side.FRONT);
        Component line0Comp = front.line(0);
        if (line0Comp == null) {
            return;
        }
        
        
        final Player player = event.getPlayer();
        final org.bukkit.Location signLocation = event.getClickedBlock().getLocation();

        String line0Plain = stripLegacyColors(LegacyComponentSerializer.legacySection().serialize(line0Comp)).trim();
        boolean rentSign = line0Plain.equalsIgnoreCase("[Rent Claim]") || line0Plain.equalsIgnoreCase("[Rented]") || line0Plain.equalsIgnoreCase("[Renew]");
        boolean sellSign = line0Plain.equalsIgnoreCase("[Buy Claim]") || line0Plain.equalsIgnoreCase("[Sell Claim]");
        boolean mailboxSign = line0Plain.equalsIgnoreCase("[Mailbox]");

        if (!rentSign && !sellSign && !mailboxSign) {
            return;
        }

        // Cancel the event to prevent sign editing for rent and sell signs (but not mailbox)
        if (!mailboxSign) {
            event.setCancelled(true);
        }
        
        if (!player.hasPermission(rentSign ? "griefprevention.sign.use.rent" : 
                                   (sellSign ? "griefprevention.sign.use.buy" : "griefprevention.sign.use.mailbox"))) {
            player.sendMessage(Component.text("You don't have permission to use this sign.", NamedTextColor.RED));
            return;
        }
        
        try {
            // Get the sign again to ensure we have the latest state
            org.bukkit.block.Sign currentSign = (org.bukkit.block.Sign) signLocation.getBlock().getState();
            PersistentDataContainer pdc = currentSign.getPersistentDataContainer();
            
            // Get the sign data from PDC
            String kindStr = pdc.get(keyKind(), PersistentDataType.STRING);
            String claimId = pdc.get(keyClaim(), PersistentDataType.STRING);
            String ecoAmtRaw = pdc.get(keyEcoAmt(), PersistentDataType.STRING);
            String perClick = pdc.get(keyPerClick(), PersistentDataType.STRING);
            String maxCap = pdc.get(keyMaxCap(), PersistentDataType.STRING);
            
            if (kindStr == null || claimId == null) {
                // Fallback to parsing from sign text if PDC data is missing
                SignSide frontSide = currentSign.getSide(org.bukkit.block.sign.Side.FRONT);
                String line2 = LegacyComponentSerializer.legacySection().serialize(frontSide.line(2));
                EcoKind kind = parseEcoKind(line2);
                if (kind == null) {
                    plugin.getLogger().warning("Sign at " + signLocation + " is missing required data");
                    player.sendMessage(Component.text("This sign is missing required data. Please break and recreate it.", NamedTextColor.RED));
                    return;
                }
                kindStr = kind.name();
            }
            
            // Get the economy kind from PDC for all sign types
            String ecoKindStr = pdc.get(new NamespacedKey(plugin, "sign.ecoKind"), PersistentDataType.STRING);
            if (ecoKindStr == null) {
                player.sendMessage(Component.text("This sign is missing economy data. Please break and recreate it.", NamedTextColor.RED));
                return;
            }
            EcoKind kind = EcoKind.valueOf(ecoKindStr);

            if (kind == EcoKind.MONEY && !plugin.isEconomyAvailable()) {
                player.sendMessage(Component.text("This rental requires an economy provider.", NamedTextColor.RED));
                return;
            }

            // Process the sign interaction
            completeSignInteraction(event, player, currentSign, rentSign, sellSign, mailboxSign, kind, claimId, ecoAmtRaw, perClick, maxCap, pdc);
            
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text("Invalid economy type on sign.", NamedTextColor.RED));
        } catch (Exception e) {
            plugin.getLogger().severe("Error processing sign interaction: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage(Component.text("An error occurred while processing this sign.", NamedTextColor.RED));
        }
    }

    private void completeSignInteraction(PlayerInteractEvent event, Player player, Sign sign, boolean rent, boolean sell, boolean mailbox,
                                     EcoKind kind, String claimId, String ecoAmtRaw, String perClick,
                                     String maxCap, PersistentDataContainer pdc) {
        // Skip mailbox signs - they are handled by MailboxListener
        if (mailbox) {
            return;
        }
        
        // Validate claim ID is not null or empty
        if (claimId == null || claimId.trim().isEmpty()) {
            player.sendMessage(Component.text("Invalid claim ID on sign. Please break and recreate the sign.", NamedTextColor.RED));
            return;
        }
        
        // Check if player is the owner of the claim (prevent self-rental)
        Optional<Object> claimOpt = gp.findClaimById(claimId);
        if (claimOpt.isPresent()) {
            Object claim = claimOpt.get();
            try {
                Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
                if (ownerId != null && ownerId.equals(player.getUniqueId())) {
                    player.sendMessage(Component.text("You cannot rent your own claim.", NamedTextColor.RED));
                    return;
                }
            } catch (ReflectiveOperationException ignored) {}
        }

        // RENT: enforce renter-only renew if already rented
        // SELL: permanent transfer, no rental logic needed
        if (rent) {
            RentalStore store = plugin.getRentalStore();
            UUID existingRenter = null;
            long existingExpiry = 0L;
            if (store != null) {
                Optional<RentalStore.Entry> e = Optional.ofNullable(store.all().get(claimId));
                if (e.isPresent()) {
                    existingRenter = e.get().renter;
                    existingExpiry = e.get().expiry;
                }
            }
            long now = System.currentTimeMillis();
            boolean alreadyRented = existingRenter != null && existingExpiry > now;
            if (alreadyRented && !player.getUniqueId().equals(existingRenter)) {
                player.sendMessage(Component.text("This claim is already rented.", NamedTextColor.RED));
                return;
            }
        }

        // Prompt confirmation before proceeding
        String ecoFormatted = ecoDisplayForDialog(kind, ecoAmtRaw, pdc);
        
        // Run on the player's thread for chat messages
        plugin.runAtEntity(player, () -> {
            if (rent) {
                plugin.getConfirmationService().prompt(
                    player,
                    dev.towki.gpexpansion.confirm.ConfirmationService.Action.RENT,
                    claimId,
                    ecoFormatted,
                    kind.name(),
                    ecoAmtRaw,
                    perClick,
                    maxCap,
                    sign.getLocation()
                );
            } else {
                // Both sell and mailbox signs use BUY action
                plugin.getConfirmationService().prompt(
                    player,
                    dev.towki.gpexpansion.confirm.ConfirmationService.Action.BUY,
                    claimId,
                    ecoFormatted,
                    kind.name(),
                    ecoAmtRaw,
                    perClick,
                    maxCap,
                    sign.getLocation()
                );
            }
        });
    }

    // Payments
    public boolean charge(Player player, EcoKind kind, String ecoAmtRaw, PersistentDataContainer pdc) {
        switch (kind) {
            case MONEY: {
                // Try a late hook in case the economy registered after onEnable
                if (!plugin.isEconomyAvailable()) {
                    plugin.refreshEconomy();
                }
                if (!plugin.isEconomyAvailable()) {
                    player.sendMessage(ChatColor.RED + "Economy not available. Please ensure Vault and an economy provider are installed.");
                    return false;
                }
                double amount = Double.parseDouble(ecoAmtRaw);
                amount = Math.min(amount, 9_999_999.99);
                if (!plugin.hasMoney(player, amount)) {
                    player.sendMessage(ChatColor.RED + "You don't have enough money.");
                    return false;
                }
                return plugin.withdrawMoney(player, amount);
            }
            case EXPERIENCE: {
                boolean levels = ecoAmtRaw.toUpperCase().endsWith("L");
                int amt = Integer.parseInt(levels ? ecoAmtRaw.substring(0, ecoAmtRaw.length() - 1) : ecoAmtRaw);
                if (levels) amt = Math.min(amt, 999); else amt = Math.min(amt, 999_999_999);
                if (levels) {
                    if (player.getLevel() < amt) {
                        player.sendMessage(ChatColor.RED + "You need " + amt + " levels.");
                        return false;
                    }
                    player.setLevel(player.getLevel() - amt);
                } else {
                    // points
                    // Bukkit handles negative giveExp to subtract points
                    // Note: this may reduce levels accordingly
                    if (getTotalExp(player) < amt) {
                        player.sendMessage(ChatColor.RED + "You need " + amt + " experience points.");
                        return false;
                    }
                    player.giveExp(-amt);
                }
                return true;
            }
            case ITEM: {
                String b64 = pdc.get(keyItemB64(), PersistentDataType.STRING);
                if (b64 == null || b64.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "This sign is missing item data.");
                    return false;
                }
                ItemStack target = decodeItem(b64);
                if (target == null || target.getType() == Material.AIR) {
                    player.sendMessage(ChatColor.RED + "This sign's item is invalid.");
                    return false;
                }
                int amt = Integer.parseInt(ecoAmtRaw);
                boolean ok = consumeMatchingItem(player, target, amt);
                if (!ok) {
                    player.sendMessage(ChatColor.RED + "You don't have enough items (need " + amt + " of " + formatItemName(target) + ").");
                    return false;
                }
                return true;
            }
            default:
                return false;
        }
    }

    private boolean consumeMatchingItem(Player player, ItemStack target, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null) continue;
            if (isSameItem(stack, target)) {
                int take = Math.min(stack.getAmount(), remaining);
                stack.setAmount(stack.getAmount() - take);
                if (stack.getAmount() <= 0) contents[i] = null;
                remaining -= take;
            }
        }
        player.getInventory().setContents(contents);
        return remaining <= 0;
    }

    private String encodeItem(ItemStack item) {
        try {
            // Use Bukkit's built-in serialization to map to a Base64 string
            org.bukkit.configuration.file.YamlConfiguration conf = new org.bukkit.configuration.file.YamlConfiguration();
            conf.set("i", item.clone());
            String yaml = conf.saveToString();
            return Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    private ItemStack decodeItem(String base64) {
        try {
            if (base64 == null || base64.isEmpty()) {
                return null;
            }
            String yaml = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            org.bukkit.configuration.file.YamlConfiguration conf = new org.bukkit.configuration.file.YamlConfiguration();
            conf.loadFromString(yaml);
            return conf.getItemStack("i");
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSameItem(ItemStack a, ItemStack b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        // Compare basic properties
        if (a.getType() != b.getType()) return false;
        
        // Compare item meta if present
        if (a.hasItemMeta() != b.hasItemMeta()) return false;
        if (a.hasItemMeta()) {
            ItemMeta metaA = a.getItemMeta();
            ItemMeta metaB = b.getItemMeta();
            if (metaA == null || metaB == null) return metaA == metaB;
            
            // Compare display names
            if (metaA.hasDisplayName() != metaB.hasDisplayName()) return false;
            if (metaA.hasDisplayName() && !Objects.equals(metaA.displayName(), metaB.displayName())) return false;
            
            // Compare lore
            if (metaA.hasLore() != metaB.hasLore()) return false;
            if (metaA.hasLore() && !Objects.equals(metaA.lore(), metaB.lore())) return false;
            
            // Compare enchantments
            if (!a.getEnchantments().equals(b.getEnchantments())) return false;
        }
        
        return true;
    }
    
    private String formatItemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return StringUtils.capitalize(item.getType().toString().toLowerCase().replace('_', ' '));
    }
    
    /**
     * Calculate the total amount of experience points a player has
     * @param player The player to check
     * @return The total amount of experience points
     */
    private int getTotalExp(Player player) {
        int level = player.getLevel();
        float progress = player.getExp();
        int exp = 0;
        
        // Calculate experience from levels
        exp += getExpAtLevel(level);
        
        // Add experience from progress to next level
        exp += Math.round(progress * getExpToNextLevel(level));
        
        return exp;
    }
    
    /**
     * Get the total experience needed to reach a certain level
     * @param level The level to reach
     * @return The total experience needed
     */
    private int getExpAtLevel(int level) {
        if (level <= 16) {
            return (int) (Math.pow(level, 2) + 6 * level);
        } else if (level <= 31) {
            return (int) (2.5 * Math.pow(level, 2) - 40.5 * level + 360);
        } else {
            return (int) (4.5 * Math.pow(level, 2) - 162.5 * level + 2220);
        }
    }
    
    /**
     * Get the experience needed to reach the next level from the current level
     * @param level The current level
     * @return The experience needed for the next level
     */
    private int getExpToNextLevel(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        } else if (level <= 30) {
            return 5 * level - 38;
        } else {
            return 9 * level - 158;
        }
    }
    
    private String formatMoneyForSign(String raw) {
        try {
            double amount = Double.parseDouble(raw);
            String formatted = sanitizeMoney(plugin.formatMoney(amount), amount);
            if (formatted.endsWith(".00")) {
                return formatted.substring(0, formatted.length() - 3);
            }
            return formatted;
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private String ecoDisplayForDialog(EcoKind kind, String ecoAmtRaw, PersistentDataContainer pdc) {
        if (ecoAmtRaw == null) return "";
        
        switch (kind) {
            case MONEY: {
                try {
                    double amount = Double.parseDouble(ecoAmtRaw);
                    return sanitizeMoney(plugin.formatMoney(amount), amount);
                } catch (NumberFormatException e) {
                    return ecoAmtRaw;
                }
            }
            case EXPERIENCE: {
                boolean levels = ecoAmtRaw.toUpperCase().endsWith("L");
                return levels ? 
                    ecoAmtRaw.substring(0, ecoAmtRaw.length() - 1) + " Levels" : 
                    ecoAmtRaw + " XP";
            }
            case CLAIMBLOCKS: {
                return ecoAmtRaw + " blocks";
            }
            case ITEM: {
                String b64 = pdc.get(keyItemB64(), PersistentDataType.STRING);
                String name = formatItemName(decodeItem(b64));
                return ecoAmtRaw + " " + name;
            }
            default:
                return ecoAmtRaw;
        }
    }

    // Money formatting: keep only a currency symbol, strip names. Fallback to '$' if provider returns none.
    private String sanitizeMoney(String providerFormatted, double amount) {
        String numeric = String.format(java.util.Locale.US, "%,.2f", amount);
        if (providerFormatted == null || providerFormatted.isEmpty()) return "${numeric}".replace("${numeric}", numeric);
        String s = providerFormatted.trim();
        char lead = s.charAt(0);
        char trail = s.charAt(s.length() - 1);
        if (isCurrencySymbol(lead)) return lead + numeric;
        if (isCurrencySymbol(trail)) return trail + numeric;
        // If provider included a short symbol near start like "$ 1,000.00" or "€1,000.00"
        for (int i = 0; i < Math.min(3, s.length()); i++) {
            if (isCurrencySymbol(s.charAt(i))) return s.charAt(i) + numeric;
        }
        // As a last resort, just use '$'
        return "$" + numeric;
    }

    private boolean isCurrencySymbol(char c) {
        // Common currency symbols
        switch (c) {
            case '$': case '€': case '£': case '¥': case '₩': case '₽': case '₹': case '₺': case '₫': case '₴': case '₦': case '₱': case '₪': case '₡': case '₲': case '₵': case '₸': case '₭': case '₮': case '₨':
                return true;
            default:
                return false;
        }
    }
}
