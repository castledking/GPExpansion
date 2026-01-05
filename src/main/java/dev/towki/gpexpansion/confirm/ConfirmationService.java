package dev.towki.gpexpansion.confirm;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.listener.SignListener;
import dev.towki.gpexpansion.storage.PendingRentStore;
import dev.towki.gpexpansion.storage.RentalStore;
import dev.towki.gpexpansion.util.EcoKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ConfirmationService {
    public enum Action { RENT, BUY }

    private static class Pending {
        final UUID player;
        final Action action;
        final String claimId;
        final String kind; // MONEY, EXPERIENCE, CLAIMBLOCKS, ITEM
        final String ecoAmtRaw;
        final String perClick;
        final String maxCap;
        final Location signLoc;
        final long createdAt;
        Pending(UUID player, Action action, String claimId, String kind, String ecoAmtRaw, String perClick, String maxCap, Location signLoc) {
            this.player = player;
            this.action = action;
            this.claimId = claimId;
            this.kind = kind;
            this.ecoAmtRaw = ecoAmtRaw;
            this.perClick = perClick;
            this.maxCap = maxCap;
            this.signLoc = signLoc;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final GPExpansionPlugin plugin;
    private final SignListener signListener;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ConfirmationService(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.signListener = new SignListener(plugin);
    }

    public void prompt(Player player, Action action, String claimId, String ecoFormatted, String kind, String ecoAmtRaw, String perClick, String maxCap, Location signLoc) {
        cleanup();
        String token = UUID.randomUUID().toString().replace("-", "");
        pending.put(token, new Pending(player.getUniqueId(), action, claimId, kind, ecoAmtRaw, perClick, maxCap, signLoc));
        String time = perClick; // display per click duration

        // Always use chat-based confirmation
        plugin.runAtEntity(player, () -> {
            showChatConfirmation(player, action, claimId, ecoFormatted, time, token);
        });
    }

    private void showChatConfirmation(Player player, Action action, String claimId, String ecoFormatted, String time, String token) {
        // Get messages from lang
        Component headerLine = plugin.getMessages().get("sign-interaction.confirmation-header-line");
        String titleKey = action == Action.BUY ? "sign-interaction.confirmation-title-purchase" : "sign-interaction.confirmation-title-rent";
        Component title = plugin.getMessages().get(titleKey);
        Component itemLine = plugin.getMessages().get("sign-interaction.confirmation-item", "{id}", claimId);
        Component durationLine = plugin.getMessages().get("sign-interaction.confirmation-duration", "{duration}", time);
        Component costLine = plugin.getMessages().get("sign-interaction.confirmation-cost", "{cost}", ecoFormatted);
        
        Component confirmBtn = plugin.getMessages().get("sign-interaction.confirmation-button-confirm");
        Component cancelBtn = plugin.getMessages().get("sign-interaction.confirmation-button-cancel");
        String hoverConfirmKey = action == Action.BUY ? "sign-interaction.confirmation-hover-confirm-purchase" : "sign-interaction.confirmation-hover-confirm-rent";
        Component hoverConfirm = plugin.getMessages().get(hoverConfirmKey);
        Component hoverCancel = plugin.getMessages().get("sign-interaction.confirmation-hover-cancel");

        // Build the main message
        Component header = Component.newline()
                .append(title)
                .append(Component.newline())
                .append(itemLine)
                .append(Component.newline())
                .append(durationLine)
                .append(Component.newline())
                .append(costLine);

        // Build the clickable buttons
        Component buttons = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(confirmBtn
                        .hoverEvent(HoverEvent.showText(hoverConfirm))
                        .clickEvent(ClickEvent.runCommand("/gpxconfirm " + token + " accept")))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(cancelBtn
                        .hoverEvent(HoverEvent.showText(hoverCancel))
                        .clickEvent(ClickEvent.runCommand("/gpxconfirm " + token + " cancel")))
                .append(Component.text("]", NamedTextColor.DARK_GRAY));

        // Send the messages with improved header/footer
        player.sendMessage(headerLine);
        player.sendMessage(header);
        player.sendMessage(Component.text(""));
        player.sendMessage(buttons);
        player.sendMessage(headerLine);
    }

    public boolean handle(Player player, String token, boolean accept) {
        Pending p = pending.remove(token);
        if (p == null) return false;
        if (!p.player.equals(player.getUniqueId())) return false;
        if (!accept) {
            plugin.getMessages().send(player, "sign-interaction.confirmation-cancelled");
            return true;
        }
        // Execute action now (Folia safe)
        if (p.action == Action.BUY) {
            // BUY does not require interacting with the sign block; run on player's entity thread and global thread for command
            performBuy(player, p.claimId, p.kind, p.ecoAmtRaw, p.signLoc);
            return true;
        } else if (p.action == Action.RENT) {
            if (p.signLoc == null || p.signLoc.getWorld() == null) return false;
            
            // Check if there's a pending eviction - if so, the renter cannot extend
            dev.towki.gpexpansion.storage.EvictionStore evictionStore = plugin.getEvictionStore();
            if (evictionStore != null && evictionStore.hasPendingEviction(p.claimId)) {
                player.sendMessage(Component.text("You cannot extend this rental - an eviction notice is in progress.", NamedTextColor.RED));
                return true;
            }
            
            // Charge on player's entity thread first
            plugin.runAtEntity(player, () -> {
                if (!signListener.charge(player, EcoKind.valueOf(p.kind), p.ecoAmtRaw, null)) return; // message already sent on failure
                // Then update the sign and store on the sign's region thread
                plugin.runAtLocation(p.signLoc, () -> completeRent(player, p.claimId, p.perClick, p.maxCap, p.signLoc, p.kind, p.ecoAmtRaw));
            });
            return true;
        } else {
            return false;
        }
    }

    private void performBuy(Player player, String claimId, String kind, String ecoAmtRaw, Location signLoc) {
        plugin.runAtEntity(player, () -> {
            // Check if this is a mailbox purchase by checking the sign's PDC directly
            boolean isMailbox = false;
            if (signLoc != null && signLoc.getBlock().getState() instanceof Sign sign) {
                String signType = sign.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey(plugin, "sign.kind"), PersistentDataType.STRING);
                isMailbox = "MAILBOX".equals(signType);
            }
            
            if (isMailbox) {
                // This is a mailbox, handle through MailboxListener (pass sign location)
                if (!plugin.getMailboxListener().handlePurchaseConfirmation(player, claimId, signLoc)) {
                    return; // Error message already sent
                }
                return;
            }
            
            // Regular buy sign processing
            if (!signListener.charge(player, EcoKind.valueOf(kind), ecoAmtRaw, null)) return;

            GPBridge gpBridge = new GPBridge();
            java.util.Optional<Object> claimOpt = gpBridge.findClaimById(claimId);
            if (!claimOpt.isPresent()) {
                player.sendMessage(Component.text("Claim ID not found: " + claimId, NamedTextColor.RED));
                return;
            }
            
            Object claim = claimOpt.get();
            
            // Handle payment to owner BEFORE transfer (so we know the original owner)
            handleOwnerPayment(claim, player.getName(), claimId, kind, ecoAmtRaw, true);
            
            // Transfer claim ownership directly using GPBridge
            boolean transferred = gpBridge.transferClaimOwner(claim, player.getUniqueId());
            if (!transferred) {
                plugin.getLogger().warning("Direct claim transfer failed for " + player.getName() + " on claim " + claimId);
                player.sendMessage(Component.text("Failed to transfer claim ownership. Please contact an admin.", NamedTextColor.RED));
                return;
            } else {
                plugin.getLogger().info("Claim " + claimId + " ownership transferred to " + player.getName() + " via direct API");
            }

            // Update the sign to show ownership instead of removing it
            if (signLoc != null && signLoc.getWorld() != null) {
                plugin.runAtLocation(signLoc, () -> {
                    org.bukkit.block.Block signBlock = signLoc.getBlock();
                    if (signBlock.getState() instanceof Sign sign) {
                        // Update sign to show owned status
                        sign.getSide(Side.FRONT).line(0, LegacyComponentSerializer.legacyAmpersand().deserialize("&2&l[Owned]"));
                        sign.getSide(Side.FRONT).line(1, LegacyComponentSerializer.legacyAmpersand().deserialize("&0ID: &6" + claimId));
                        sign.getSide(Side.FRONT).line(2, LegacyComponentSerializer.legacyAmpersand().deserialize("&0Owner: " + player.getName()));
                        sign.getSide(Side.FRONT).line(3, Component.text("", NamedTextColor.BLACK));
                        sign.update(true);
                        plugin.getLogger().info("Updated buy sign at " + signLoc + " after claim purchase by " + player.getName());
                    }
                });
            }

            RentalStore store = plugin.getRentalStore();
            if (store != null) {
                store.clear(claimId);
                store.save();
            }
            player.sendMessage(Component.text("You have purchased claim " + claimId + ". The claim is now yours!", NamedTextColor.GREEN));
        });
    }

    // Must be called on the sign's region thread. Charging is expected to be done beforehand.
    private void completeRent(Player player, String claimId, String perClick, String maxCap, Location signLoc, String kind, String ecoAmtRaw) {
        org.bukkit.block.Block b = signLoc.getBlock();
        if (!(b.getState() instanceof Sign)) return;
        Sign sign = (Sign) b.getState();
        PersistentDataContainer pdc = sign.getPersistentDataContainer();

        long now = System.currentTimeMillis();
        long add = durationToMillis(perClick);
        long cap = now + durationToMillis(maxCap);
        RentalStore store = plugin.getRentalStore();
        Long currentExpiry = pdc.get(new org.bukkit.NamespacedKey(plugin, "rent.expiry"), PersistentDataType.LONG);
        if (store != null) {
            Map<String, RentalStore.Entry> allRentals = store.all();
            RentalStore.Entry existingEntry = allRentals.get(claimId);
            if (existingEntry != null) {
                long persisted = existingEntry.expiry;
                if (persisted > (currentExpiry == null ? 0L : currentExpiry)) currentExpiry = persisted;
            }
        }
        long base = currentExpiry != null && currentExpiry > now ? currentExpiry : now;
        long newExpiry = Math.min(base + add, cap);
        pdc.set(new org.bukkit.NamespacedKey(plugin, "rent.renter"), PersistentDataType.STRING, player.getUniqueId().toString());
        pdc.set(new org.bukkit.NamespacedKey(plugin, "rent.expiry"), PersistentDataType.LONG, newExpiry);

        // Grant trust permissions to the renter for this claim
        GPBridge gpBridge = new GPBridge();
        java.util.Optional<Object> claimOpt = gpBridge.findClaimById(claimId);
        if (claimOpt.isPresent()) {
            Object claim = claimOpt.get();
            
            // Grant build trust to the player for this claim using GPBridge direct method
            boolean trusted = gpBridge.trust(player, player.getName(), claim);
            if (trusted) {
                plugin.getLogger().info("Granted trust permissions to " + player.getName() + " for claim " + claimId);
            } else {
                plugin.getLogger().warning("Failed to grant trust permissions to " + player.getName() + " for claim " + claimId);
            }
        }

        // Update the sign display using Adventure components for consistency
        if (b.getState() instanceof Sign) {
            Sign s = (Sign) b.getState();
            
            // Re-set the renter PDC on this sign state since we got a fresh state
            s.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "rent.renter"), PersistentDataType.STRING, player.getUniqueId().toString());
            s.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "rent.expiry"), PersistentDataType.LONG, newExpiry);

            // Set the sign lines using Adventure components to match the creation format
            s.getSide(Side.FRONT).line(0, LegacyComponentSerializer.legacyAmpersand().deserialize("&c&l[Rented]"));
            s.getSide(Side.FRONT).line(1, LegacyComponentSerializer.legacyAmpersand().deserialize("&0ID: &6" + claimId));

            String ecoAmt = pdc.get(new org.bukkit.NamespacedKey(plugin, "sign.ecoAmt"), PersistentDataType.STRING);
            String per = pdc.get(new org.bukkit.NamespacedKey(plugin, "sign.perClick"), PersistentDataType.STRING);
            if (ecoAmt == null) ecoAmt = "";
            if (per == null) per = "";

            String line2Text = "&0" + ecoAmt + "&0/" + per;
            s.getSide(Side.FRONT).line(2, LegacyComponentSerializer.legacyAmpersand().deserialize(line2Text));

            long remain = Math.max(0L, newExpiry - System.currentTimeMillis());
            String line3Text = "&0" + formatDuration(remain);
            s.getSide(Side.FRONT).line(3, LegacyComponentSerializer.legacyAmpersand().deserialize(line3Text));

            s.update(true);
        }

        if (store != null) {
            store.set(claimId, player.getUniqueId(), newExpiry);
            store.save();
        }
        long addedMillis = newExpiry - now;
        String costFormatted = formatEcoAmount(kind, ecoAmtRaw);
        plugin.getMessages().send(player, "sign-interaction.rent-success",
            "{id}", claimId,
            "{duration}", formatDuration(addedMillis),
            "{cost}", costFormatted);
    }

    private void handleOwnerPayment(Object claim, String renterName, String claimId, String kind, String amount, boolean isPurchase) {
        try {
            Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
            if (ownerId instanceof UUID) {
                UUID ownerUuid = (UUID) ownerId;
                org.bukkit.entity.Player owner = Bukkit.getPlayer(ownerUuid);

                if (owner != null && owner.isOnline()) {
                    // Owner is online - give payment immediately
                    givePaymentToPlayer(owner, kind, amount, isPurchase);
                    String action = isPurchase ? "purchased" : "rented";
                    owner.sendMessage(Component.text(renterName + " has " + action + " your rental: [" + claimId + "]", NamedTextColor.GREEN));
                } else {
                    // Owner is offline - store for later collection
                    PendingRentStore pendingStore = plugin.getPendingRentStore();
                    if (pendingStore != null) {
                        pendingStore.addPendingRent(claimId, ownerUuid, renterName, kind, amount, isPurchase);
                        pendingStore.save();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void givePaymentToPlayer(org.bukkit.entity.Player player, String kind, String amount, boolean isPurchase) {
        try {
            double amt = Double.parseDouble(amount);
            switch (kind) {
                case "MONEY":
                    if (plugin.isEconomyAvailable()) {
                        // Apply tax if enabled
                        double taxAmount = 0;
                        double netAmount = amt;
                        if (plugin.isTaxEnabled()) {
                            taxAmount = plugin.calculateTax(amt);
                            netAmount = amt - taxAmount;
                            // Deposit tax to tax account
                            if (taxAmount > 0) {
                                plugin.depositToAccount(plugin.getTaxAccountName(), taxAmount);
                            }
                        }
                        plugin.depositMoney(player, netAmount);
                    }
                    break;
                case "EXPERIENCE":
                    player.giveExp((int) amt);
                    break;
                case "CLAIMBLOCKS":
                    // Add claim blocks to player (this would need GP integration)
                    // For now, just send a message
                    player.sendMessage(Component.text("You received " + amt + " claim blocks from rental!", NamedTextColor.GREEN));
                    break;
                case "ITEM":
                    // Items would need more complex handling
                    // Add custom item handling here
                    // For example:
                    // player.getInventory().addItem(new ItemStack(Material.DIAMOND, (int) amt));
                    player.sendMessage(Component.text("You received items from rental!", NamedTextColor.GREEN));
                    break;
            }
        } catch (NumberFormatException ignored) {}
    }


    private long durationToMillis(String s) {
        if (s == null) return 0L;
        String numStr = s.replaceAll("[^0-9]", "");
        if (numStr.isEmpty()) return 0L;
        long n = Long.parseLong(numStr);
        char unit = Character.toLowerCase(s.charAt(s.length() - 1));
        switch (unit) {
            case 'd': return n * 24L * 60 * 60 * 1000;
            case 'w': return n * 7L * 24 * 60 * 60 * 1000;
            case 'm': return n * 30L * 24 * 60 * 60 * 1000;
            case 'y': return n * 365L * 24 * 60 * 60 * 1000;
            default: return 0L;
        }
    }

    private String formatDuration(long millis) {
        if (millis <= 0) return "0d";
        long seconds = millis / 1000L;

        long years = seconds / (365L * 24 * 3600);
        seconds %= 365L * 24 * 3600;
        long months = seconds / (30L * 24 * 3600);
        seconds %= 30L * 24 * 3600;
        long weeks = seconds / (7L * 24 * 3600);
        seconds %= 7L * 24 * 3600;
        long days = seconds / (24L * 3600);
        seconds %= 24L * 3600;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;

        StringBuilder sb = new StringBuilder();
        int parts = 0;
        if (years > 0 && parts < 2) { sb.append(years).append("y"); parts++; }
        if (months > 0 && parts < 2) { if (sb.length() > 0) sb.append(' '); sb.append(months).append("m"); parts++; }
        if (weeks > 0 && parts < 2) { if (sb.length() > 0) sb.append(' '); sb.append(weeks).append("w"); parts++; }
        if (days > 0 && parts < 2) { if (sb.length() > 0) sb.append(' '); sb.append(days).append("d"); parts++; }
        if (hours > 0 && parts < 2) { if (sb.length() > 0) sb.append(' '); sb.append(hours).append("h"); parts++; }
        if (minutes > 0 && parts < 2) { if (sb.length() > 0) sb.append(' '); sb.append(minutes).append("min"); parts++; }

        if (sb.length() == 0) return "<1min";
        return sb.toString();
    }

    private String formatEcoAmount(String kind, String ecoAmtRaw) {
        if (kind == null || ecoAmtRaw == null) return ecoAmtRaw;
        switch (kind.toUpperCase()) {
            case "MONEY":
                try {
                    double amount = Double.parseDouble(ecoAmtRaw);
                    return plugin.formatMoney(amount);
                } catch (NumberFormatException e) {
                    return "$" + ecoAmtRaw;
                }
            case "EXPERIENCE":
                boolean levels = ecoAmtRaw.toUpperCase().endsWith("L");
                return levels ? 
                    ecoAmtRaw.substring(0, ecoAmtRaw.length() - 1) + " Levels" : 
                    ecoAmtRaw + " XP";
            case "CLAIMBLOCKS":
                return ecoAmtRaw + " blocks";
            case "ITEM":
                return ecoAmtRaw + " items";
            default:
                return ecoAmtRaw;
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now - e.getValue().createdAt > 60_000L);
    }

    // Build inline JSON for minecraft:confirmation dialog with run_command actions
    @SuppressWarnings("unused")
    private String buildConfirmationDialogJson(String tooltipText, String yesCommand, String noCommand) {
        String t = escapeJson(tooltipText);
        String y = escapeJson(yesCommand);
        String n = escapeJson(noCommand);
        // Keep labels short; put the full prompt in tooltips
        return "{"
                + "\"type\":\"minecraft:confirmation\"," 
                + "\"yes\":{"
                + "\"label\":{\"text\":\"Confirm\",\"color\":\"green\"},"
                + "\"tooltip\":{\"text\":\"" + t + "\"},"
                + "\"action\":{\"type\":\"run_command\",\"command\":\"" + y + "\"}"
                + "},"
                + "\"no\":{"
                + "\"label\":{\"text\":\"Cancel\",\"color\":\"red\"},"
                + "\"tooltip\":{\"text\":\"" + t + "\"},"
                + "\"action\":{\"type\":\"run_command\",\"command\":\"" + n + "\"}"
                + "}"
                + "}";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
