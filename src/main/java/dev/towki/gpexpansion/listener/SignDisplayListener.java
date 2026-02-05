package dev.towki.gpexpansion.listener;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.util.EcoKind;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class SignDisplayListener implements Listener {
    private final GPExpansionPlugin plugin;

    private final NamespacedKey keyKind;
    private final NamespacedKey keyClaim;
    private final NamespacedKey keyEcoAmt;
    private final NamespacedKey keyEcoKind;
    private final NamespacedKey keyPerClick;
    private final NamespacedKey keyMaxCap;
    private final NamespacedKey keyItemB64;
    private final NamespacedKey keyRenter;
    private final NamespacedKey keyExpiry;
    private final NamespacedKey keyScrollIdx;

    private final Map<java.util.UUID, Long> lastScan = new HashMap<>();
    private final Map<java.util.UUID, Long> lastAnimate = new HashMap<>();

    public SignDisplayListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        keyKind = new NamespacedKey(plugin, "sign.kind");
        keyClaim = new NamespacedKey(plugin, "sign.claimId");
        keyEcoAmt = new NamespacedKey(plugin, "sign.ecoAmt");
        keyEcoKind = new NamespacedKey(plugin, "sign.ecoKind");
        keyPerClick = new NamespacedKey(plugin, "sign.perClick");
        keyMaxCap = new NamespacedKey(plugin, "sign.maxCap");
        keyItemB64 = new NamespacedKey(plugin, "sign.itemMeta");
        keyRenter = new NamespacedKey(plugin, "rent.renter");
        keyExpiry = new NamespacedKey(plugin, "rent.expiry");
        keyScrollIdx = new NamespacedKey(plugin, "sign.scrollIdx");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ())) return;

        long now = System.currentTimeMillis();
        long last = lastScan.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < 250L) return; // throttle scans
        lastScan.put(p.getUniqueId(), now);

        // Scan nearby 4-block radius cube
        int r = 4;
        Location base = p.getLocation();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = base.getWorld().getBlockAt(base.getBlockX() + dx, base.getBlockY() + dy, base.getBlockZ() + dz);
                    Material type = b.getType();
                    if (!type.name().endsWith("_SIGN") && !type.name().endsWith("_WALL_SIGN")) continue;
                    Sign sign = (Sign) b.getState();
                    PersistentDataContainer pdc = sign.getPersistentDataContainer();
                    if (!pdc.has(keyKind, PersistentDataType.STRING)) continue;
                    String signType = pdc.get(keyKind, PersistentDataType.STRING);
                    String claimId = pdc.get(keyClaim, PersistentDataType.STRING);
                    String renterStr = pdc.get(keyRenter, PersistentDataType.STRING);
                    Long expiry = pdc.get(keyExpiry, PersistentDataType.LONG);
                    boolean isRented = renterStr != null && expiry != null && expiry > now;
                    boolean isRenter = isRented && renterStr.equalsIgnoreCase(p.getUniqueId().toString());
                    
                    // Check if this is a mailbox and handle display
                    if ("MAILBOX".equals(signType)) {
                        dev.towki.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();
                        if (dataStore.isMailbox(claimId)) {
                            UUID owner = dataStore.getMailboxOwner(claimId).orElse(null);
                            if (owner != null) {
                                String ownerName = Bukkit.getOfflinePlayer(owner).getName();
                                if (ownerName == null) ownerName = "Unknown";
                                
                                // Update sign display for owned mailbox
                                if (!sign.getLine(0).contains(ChatColor.BLUE + "" + ChatColor.BOLD + "[Mailbox]")) {
                                    sign.setLine(0, ChatColor.BLUE + "" + ChatColor.BOLD + "[Mailbox]");
                                    sign.setLine(1, ChatColor.GREEN + ownerName);
                                    sign.setLine(2, ChatColor.BLACK + "(Click to open)");
                                    sign.setLine(3, "");
                                    sign.update();
                                }
                            }
                        }
                        // Continue to next sign for mailboxes
                        continue;
                    }

                    // Toggle display for renter near sign
                    String line0Plain = ChatColor.stripColor(sign.getLine(0) == null ? "" : sign.getLine(0));
                    
                    // Check if there's an eviction pending for this claim
                    boolean isEvicted = false;
                    long evictionRemaining = 0;
                    if (claimId != null && !claimId.isEmpty()) {
                        dev.towki.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();
                        dev.towki.gpexpansion.storage.ClaimDataStore.EvictionData eviction = dataStore.getEviction(claimId).orElse(null);
                        if (eviction != null && System.currentTimeMillis() < eviction.effectiveAt) {
                            isEvicted = true;
                            evictionRemaining = eviction.effectiveAt - System.currentTimeMillis();
                        }
                    }
                    
                    if (isRenter) {
                        if (isEvicted) {
                            // Show [Evicted] with countdown for renter being evicted
                            if (!line0Plain.equalsIgnoreCase("[Evicted]")) {
                                sign.setLine(0, ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Evicted]");
                            }
                            sign.setLine(2, ChatColor.DARK_RED + "Remaining Time");
                            sign.setLine(3, ChatColor.RED + formatDuration(evictionRemaining));
                            sign.update();
                        } else {
                            // Show [Renew] for renter not being evicted
                            if (!line0Plain.equalsIgnoreCase("[Renew]")) {
                                sign.setLine(0, ChatColor.GREEN + "" + ChatColor.BOLD + "[Renew]");
                                sign.update();
                            }
                        }
                    } else {
                        if (line0Plain.equalsIgnoreCase("[Renew]") || line0Plain.equalsIgnoreCase("[Evicted]")) {
                            // Revert to [Rented] if rented, else [Rent Claim] or [Buy Claim]
                            if (isRented) {
                                sign.setLine(0, ChatColor.RED + "" + ChatColor.BOLD + "[Rented]");
                                String renterName = resolveRenterName(renterStr);
                                sign.setLine(1, ChatColor.BLACK + renterName);
                                String ecoAmt = pdc.get(keyEcoAmt, PersistentDataType.STRING);
                                String ecoKindStr = pdc.get(keyEcoKind, PersistentDataType.STRING);
                                String perClick = pdc.get(keyPerClick, PersistentDataType.STRING);
                                String maxCap = pdc.get(keyMaxCap, PersistentDataType.STRING);
                                if (ecoAmt == null) ecoAmt = "";
                                if (perClick == null) perClick = "";
                                if (maxCap == null) maxCap = "";
                                String ecoFormatted = formatEcoForSign(ecoKindStr, ecoAmt);
                                sign.setLine(2, ChatColor.BLACK + ecoFormatted + ChatColor.BLACK + "/" + perClick);
                                sign.setLine(3, ChatColor.BLACK + "Max: " + maxCap);
                            } else {
                                // Check if this is a sell sign by looking at the PDC data
                                // Hanging signs have less space; use shortened text [Rent]/[Sell]
                                boolean hanging = isHangingSign(type);
                                String kind = pdc.get(keyKind, PersistentDataType.STRING);
                                String displayKey = "RENT".equals(kind)
                                    ? (hanging ? "sign-interaction.sign-display-rent-hanging" : "sign-interaction.sign-display-rent-full")
                                    : (hanging ? "sign-interaction.sign-display-buy-hanging" : "sign-interaction.sign-display-buy-full");
                                String display = ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getRaw(displayKey));
                                sign.setLine(0, display);
                            }
                            sign.update();
                        }
                    }

                    // Animate item name when player is looking at this sign
                    Block target = p.getTargetBlockExact(5);
                    if (target != null && target.getLocation().equals(b.getLocation())) {
                        long la = lastAnimate.getOrDefault(p.getUniqueId(), 0L);
                        if (now - la >= 200L) { // animate every 0.2s while looking
                            lastAnimate.put(p.getUniqueId(), now);
                            animateItemLine(sign, pdc);
                        }
                    }
                }
            }
        }
    }

    private void animateItemLine(Sign sign, PersistentDataContainer pdc) {
        String signType = pdc.get(keyKind, PersistentDataType.STRING);
        if (!"ITEM".equals(signType) && !"MAILBOX".equals(signType)) return;
        String perClick = pdc.get(keyPerClick, PersistentDataType.STRING);
        String ecoAmt = pdc.get(keyEcoAmt, PersistentDataType.STRING);
        String itemName = "Item";
        try {
            String b64 = pdc.get(keyItemB64, PersistentDataType.STRING);
            if (b64 != null && !b64.isEmpty()) {
                org.bukkit.configuration.file.YamlConfiguration conf = new org.bukkit.configuration.file.YamlConfiguration();
                String yaml = new String(java.util.Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
                conf.loadFromString(yaml);
                org.bukkit.inventory.ItemStack it = conf.getItemStack("i");
                if (it != null) itemName = prettifyItemName(it.getType().name());
            }
        } catch (Exception ignored) {}
        int amt = 0;
        try { amt = Integer.parseInt(ecoAmt); } catch (Exception ignored) {}
        String base = ChatColor.GREEN + "" + amt + " " + itemName;
        // compute room for base before "/per"
        int max = 15;
        int room = Math.max(0, max - (1 + (perClick == null ? 0 : perClick.length())));
        String scrollSrc = ChatColor.stripColor(base);
        if (scrollSrc.length() <= room) {
            sign.setLine(2, ChatColor.BLACK + ChatColor.stripColor(base) + ChatColor.BLACK + "/" + perClick);
            sign.update();
            return;
        }
        
        // Get current animation state
        int idx = pdc.getOrDefault(keyScrollIdx, PersistentDataType.INTEGER, 0);
        int totalLength = scrollSrc.length();
        
        // Calculate scroll position with pause at the beginning and end
        int pauseLength = 20; // Number of frames to pause at start/end
        int totalFrames = totalLength + pauseLength * 2;
        int currentFrame = idx % totalFrames;
        
        String view;
        if (currentFrame < pauseLength) {
            // Pause at start
            view = scrollSrc.substring(0, room);
        } else if (currentFrame < totalLength - pauseLength) {
            // Scrolling
            int scrollPos = currentFrame - pauseLength;
            int endPos = Math.min(scrollPos + room, totalLength);
            view = scrollSrc.substring(scrollPos, endPos);
            if (view.length() < room && endPos == totalLength) {
                // Pad with spaces at the end
                view += " ".repeat(room - view.length());
            }
        } else {
            // Pause at end (showing the end of the text)
            int startPos = Math.max(0, totalLength - room);
            view = scrollSrc.substring(startPos, totalLength);
        }
        
        pdc.set(keyScrollIdx, PersistentDataType.INTEGER, idx + 1);
        sign.setLine(2, ChatColor.BLACK + view + ChatColor.BLACK + "/" + perClick);
        sign.update();
    }

    /** Hanging signs (OAK_HANGING_SIGN, OAK_WALL_HANGING_SIGN, etc.) have less display space. */
    private boolean isHangingSign(Material material) {
        return material != null && material.name().contains("HANGING_SIGN");
    }

    private String prettifyItemName(String enumName) {
        String s = enumName.toLowerCase().replace('_', ' ');
        String[] parts = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
    
    private String resolveClaimOwnerName(String claimId) {
        if (claimId == null || claimId.isEmpty()) return "Unknown";
        try {
            GPBridge gpBridge = new GPBridge();
            java.util.Optional<Object> claimOpt = gpBridge.findClaimById(claimId);
            if (!claimOpt.isPresent()) return "Unknown";
            Object ownerId = claimOpt.get().getClass().getMethod("getOwnerID").invoke(claimOpt.get());
            if (!(ownerId instanceof UUID)) return "Unknown";
            String name = Bukkit.getOfflinePlayer((UUID) ownerId).getName();
            return name != null ? name : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String resolveRenterName(String renterUuidStr) {
        if (renterUuidStr == null || renterUuidStr.isEmpty()) return "Unknown";
        try {
            UUID renterId = UUID.fromString(renterUuidStr);
            String name = Bukkit.getOfflinePlayer(renterId).getName();
            return name != null ? name : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String formatEcoForSign(String ecoKindStr, String ecoAmtRaw) {
        if (ecoAmtRaw == null) return "";
        if (ecoKindStr == null) ecoKindStr = "MONEY";
        try {
            EcoKind kind = EcoKind.valueOf(ecoKindStr.toUpperCase());
            switch (kind) {
                case MONEY:
                    try {
                        double amount = Double.parseDouble(ecoAmtRaw);
                        return plugin.formatMoneyForSign(amount);
                    } catch (NumberFormatException ignored) {
                        return "$" + ecoAmtRaw;
                    }
                case EXPERIENCE:
                    return ecoAmtRaw.toUpperCase().endsWith("L")
                        ? ecoAmtRaw.substring(0, ecoAmtRaw.length() - 1) + " Levels"
                        : ecoAmtRaw + " XP";
                case CLAIMBLOCKS:
                    return ecoAmtRaw + " blocks";
                case ITEM:
                    return ecoAmtRaw + " items";
                default:
                    return ecoAmtRaw;
            }
        } catch (IllegalArgumentException ignored) {
            return ecoAmtRaw;
        }
    }

    private String formatDuration(long millis) {
        if (millis <= 0) return "0s";
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            hours %= 24;
            return days + "d " + hours + "h";
        } else if (hours > 0) {
            minutes %= 60;
            return hours + "h " + minutes + "m";
        } else if (minutes > 0) {
            seconds %= 60;
            return minutes + "m " + seconds + "s";
        } else {
            return seconds + "s";
        }
    }
}
