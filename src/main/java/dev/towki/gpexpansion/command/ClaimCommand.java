package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.BanStore;
import dev.towki.gpexpansion.storage.NameStore;
import dev.towki.gpexpansion.storage.PendingRentStore;
import dev.towki.gpexpansion.storage.RentalStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

public class ClaimCommand implements CommandExecutor, TabCompleter {

    private final GPBridge gp;
    private final GPExpansionPlugin plugin;
    
    // Helper method to check if running on Folia
    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    // Helper to check if a string is numeric
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        for (char c : str.toCharArray()) {
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    // Helper method to format duration in milliseconds to human-readable format
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
            minutes %= 60;
        }
        if (minutes > 0) {
            if (result.length() > 0) result.append(" ");
            result.append(minutes).append("m");
            seconds %= 60;
        }
        if (seconds > 0 || result.length() == 0) {
            if (result.length() > 0) result.append(" ");
            result.append(seconds).append("s");
        }

        return result.toString();
    }

    public ClaimCommand(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.gp = new GPBridge();
    }

    private static final List<String> SUBS = Arrays.asList(
            // Our features
            "name", "list", "create", "adminlist",
            // Mapped GP commands (exact set requested)
            "abandon",           // -> abandonclaim
            "abandonall",        // -> abandonallclaims
            "explosions",        // -> claimexplosions
            "trust",             // -> trust
            "untrust",           // -> untrust (supports 'all')
            "accesstrust",       // -> accesstrust
            "containertrust",    // -> containertrust
            "trustlist",         // -> trustlist
            "subdivideclaim",    // -> subdivideclaims
            "3dsubdivideclaim",  // -> 3dsubdivideclaims
            "restrictsubclaim",  // -> restrictsubclaim
            "basic",             // -> basicclaims
            "permissiontrust",   // -> permissiontrust
            "abandonall",        // -> abandonallclaims
            "transfer",          // -> transfer (wraps GP's transferclaim and adds ID support)
            "rentalsignconfirm",
            "evict",             // -> evict player from rental
            "collectrent",       // -> collect pending rental payments
            // Moderation placeholders
            "ban", "unban", "banlist"
    );

    // Remove legacy color codes if sender lacks color permission. Keeps formatting for now.
    private String enforceNamePermissions(CommandSender sender, String legacy) {
        if (sender.hasPermission("griefprevention.claim.name.color")) {
            return legacy;
        }
        String s = legacy;
        // Strip hex color sequences: §x§R§R§G§G§B§B (case-insensitive)
        s = s.replaceAll("(?i)§x(§[0-9A-F]){6}", "");
        s = s.replaceAll("(?i)&x(&[0-9A-F]){6}", "");
        // Strip standard color codes §0-§9 §a-§f and & variants (preserve formatting like k-o and r)
        s = s.replaceAll("(?i)[§&][0-9A-F]", "");
        return s;
    }

    // Convert legacy section sign codes (§) to ampersand codes (&) for GP-friendly storage/display
    private String toAmpersand(String legacy) {
        if (legacy == null || legacy.isEmpty()) return legacy;
        return legacy.replace('\u00A7', '&');
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Run on the main thread if this is a player
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (isFolia()) {
                player.getScheduler().execute(plugin, () -> executeCommand(sender, command, label, args), null, 1L);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> executeCommand(sender, command, label, args));
            }
            return true;
        }
        // Console or command block - execute directly
        return executeCommand(sender, command, label, args);
    }
    
    private boolean executeCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Support standalone /trustlist command routed through this executor
        if (command.getName().equalsIgnoreCase("trustlist") || label.equalsIgnoreCase("trustlist")) {
            return handleDispatch(sender, "trustlist", args);
        }
        // Support standalone /adminclaimlist command routed through this executor (including alias adminclaimslist)
        if (command.getName().equalsIgnoreCase("adminclaimlist") || label.equalsIgnoreCase("adminclaimlist") || label.equalsIgnoreCase("adminclaimslist")) {
            return handleAdminClaimsList(sender, args);
        }
        // Support standalone /claimslist command routed through this executor (including alias claimlist)
        if (command.getName().equalsIgnoreCase("claimslist") || label.equalsIgnoreCase("claimslist") || label.equalsIgnoreCase("claimlist")) {
            return handleList(sender, args);
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("/" + label + " <" + String.join("|", SUBS) + ">", NamedTextColor.YELLOW));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "trust":
                return handleDispatch(sender, "trust", subArgs);
            case "untrust":
                return handleDispatch(sender, "untrust", subArgs);
            case "containertrust":
                return handleDispatch(sender, "containertrust", subArgs);
            case "accesstrust":
                return handleDispatch(sender, "accesstrust", subArgs);
            case "permissiontrust":
                return handleDispatch(sender, "permissiontrust", subArgs);
            case "subdivideclaim":
                return handleDispatch(sender, "subdivideclaims", subArgs);
            case "3dsubdivideclaim":
                return handleDispatch(sender, "3dsubdivideclaims", subArgs);
            case "restrictsubclaim":
                return handleDispatch(sender, "restrictsubclaim", subArgs);
            case "abandon":
                return handleDispatch(sender, "abandonclaim", subArgs);
            case "abandonall":
                return handleDispatch(sender, "abandonallclaims", subArgs);
            case "basic":
                return handleDispatch(sender, "basicclaims", subArgs);
            case "explosions":
                return handleDispatch(sender, "claimexplosions", subArgs);
            case "trustlist":
                return handleDispatch(sender, "trustlist", subArgs);
            case "name":
                return handleName(sender, subArgs);
            case "create":
                // Alias to GP's /createclaim [radius] to avoid recursion
                return handleDispatch(sender, "createclaim", subArgs);
            case "ban":
                return handleBan(sender, subArgs);
            case "unban":
                return handleUnban(sender, subArgs);
            case "banlist":
                return handleBanList(sender, subArgs);
            case "list":
                return handleList(sender, subArgs);
            case "adminlist":
                return handleAdminClaimsList(sender, subArgs);
            case "transfer":
                return handleTransferClaim(sender, subArgs);
            case "rentalsignconfirm":
                return handleRentalSignConfirm(sender, subArgs);
            case "evict":
                return handleEvict(sender, subArgs);
            case "collectrent":
                return handleCollectRent(sender, subArgs);
            default:
                sender.sendMessage(Component.text("Unknown subcommand. Try: " + String.join(", ", SUBS), NamedTextColor.RED));
                return true;
        }
    }

    private boolean requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getMessages().get("general.player-only"));
            return false;
        }
        return true;
    }

    private boolean handleDispatch(CommandSender sender, String base, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;

        // Build the final command string first
        final String finalCmd = (args.length > 0) ? base + " " + String.join(" ", args) : base;
        
        // Commands that support optional trailing claim ID
        // Commands requiring args before ID (e.g., trust <player> [id])
        boolean supportsIdWithArgs = base.equals("trust") || base.equals("untrust") || base.equals("containertrust") || 
                           base.equals("accesstrust") || base.equals("permissiontrust");
        // Commands that can take just an ID with no other args (e.g., abandonclaim [id])
        boolean supportsIdOnly = base.equals("abandonclaim") || base.equals("claimexplosions") || 
                           base.equals("restrictsubclaim") || base.equals("trustlist");
        
        // Check if we have an ID at the end
        boolean hasIdArg = false;
        if (supportsIdWithArgs && args.length >= 2) {
            hasIdArg = isNumeric(args[args.length - 1]);
        } else if (supportsIdOnly && args.length >= 1) {
            hasIdArg = isNumeric(args[args.length - 1]);
        }
        
        if (hasIdArg) {
            String possibleId = args[args.length - 1];
            String[] passArgs = java.util.Arrays.copyOf(args, args.length - 1);
            String finalBaseCmd = base;
            
            // Run the claim lookup and command execution on the global scheduler
            Runnable task = () -> {
                try {
                    java.util.Optional<Object> claimOpt = gp.findClaimById(possibleId);
                    if (!claimOpt.isPresent()) {
                        sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", possibleId));
                        return;
                    }
                    
                    Object claim = claimOpt.get();
                    java.util.Optional<org.bukkit.Location> centerOpt = gp.getClaimCenter(claim);
                    if (!centerOpt.isPresent()) {
                        sender.sendMessage(Component.text("Could not compute a safe location in claim " + possibleId + ".", NamedTextColor.RED));
                        return;
                    }
                    
                    // Now execute the command on the player's thread
                    Runnable playerTask = () -> {
                        org.bukkit.Location original = player.getLocation();
                        plugin.teleportEntity(player, centerOpt.get());
                        
                        // Execute the command with the player as the sender
                        String cmd = finalBaseCmd;
                        if (passArgs.length > 0) {
                            cmd += " " + String.join(" ", passArgs);
                        }
                        
                        boolean ok = Bukkit.dispatchCommand(sender, cmd);
                        if (!ok) {
                            sender.sendMessage(Component.text("Command failed to execute: /" + finalBaseCmd, NamedTextColor.RED));
                        }
                        
                        // Teleport back
                        plugin.teleportEntity(player, original);
                    };
                    
                    if (isFolia()) {
                        player.getScheduler().execute(plugin, playerTask, null, 1L);
                    } else {
                        Bukkit.getScheduler().runTask(plugin, playerTask);
                    }
                } catch (Exception e) {
                    sender.sendMessage(Component.text("An error occurred while processing the command: " + e.getMessage(), NamedTextColor.RED));
                    e.printStackTrace();
                }
            };
            
            if (isFolia()) {
                // Run on global region for claim lookup
                Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
            } else {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
            return true;
        }
        try {
            boolean ok = Bukkit.dispatchCommand(sender, finalCmd);
            if (!ok) {
                sender.sendMessage(Component.text("Command failed to execute: /" + base, NamedTextColor.RED));
            }
        } catch (Exception e) {
            sender.sendMessage(Component.text("An error occurred while executing the command: " + e.getMessage(), NamedTextColor.RED));
            e.printStackTrace();
        }
        return true;
    }
    // Helper methods for command handling
    private boolean handleList(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return true;
        }
        Player player = (Player)sender;
        List<Object> claims = gp.getClaimsFor(player);
        NameStore store = plugin.getNameStore();
        gp.getPlayerClaimStats(player).ifPresent(stats -> {
            sender.sendMessage(Component.text(String.format("%d blocks from play + %d bonus = %d total.",
                stats.accrued, stats.bonus, stats.total), NamedTextColor.YELLOW));
        });

        // Group claims by their parent claim, but only include subclaims that the player owns
        LinkedHashMap<Object, List<Object>> grouped = new LinkedHashMap<>();
        for (Object c : claims) {
            Object parent = toMainClaim(c);
            if (c != parent) { // This is a subclaim
                // Only add subclaims that the player owns
                if (gp.isOwner(c, player.getUniqueId())) {
                    grouped.computeIfAbsent(parent, k -> new ArrayList<>()).add(c);
                }
            }
        }

        // Also add parent claims (including those where player only owns subclaims)
        for (Object c : claims) {
            Object parent = toMainClaim(c);
            if (c == parent) { // This is a parent claim
                grouped.computeIfAbsent(parent, k -> new ArrayList<>());
            }
        }

        sender.sendMessage(Component.text(String.format("Claims (%d):", grouped.size()), NamedTextColor.YELLOW));

        for (Map.Entry<Object, List<Object>> e : grouped.entrySet()) {
            Object parent = e.getKey();
            String id = gp.getClaimId(parent).orElse("?");
            String parentName = store.get(id).orElse("unnamed");
            String name = formatClaimLine(parent, id, parentName);
            sender.sendMessage(parseColorCodes(name));

            // Get subclaims for this parent that the player owns
            List<Object> subs = e.getValue();
            if (subs.isEmpty()) {
                // If no subclaims in the grouped list, check if there are any subclaims the player owns
                List<Object> allSubs = getSubclaims(parent);
                for (Object sub : allSubs) {
                    if (gp.isOwner(sub, player.getUniqueId())) {
                        subs.add(sub);
                    }
                }
            }

            for (Object sub : subs) {
                String subId = gp.getClaimId(sub).orElse("");
                String subName = store.get(subId).orElse("");
                String subLine = formatSubclaimLine(sub, id, subName);
                // Parse color codes in the line
                sender.sendMessage(parseColorCodes("    " + subLine));
            }
        }

        // Get trusted claims and filter out any that the player owns
        List<Object> trusted = getTrustedClaimsFor(player).stream()
            .filter(c -> !gp.isOwner(c, player.getUniqueId()))
            .collect(Collectors.toList());

        if (!trusted.isEmpty()) {
            sender.sendMessage(Component.text(String.format("Trusted Claims (%d):", trusted.size()), NamedTextColor.YELLOW));
            for (Object c : trusted) {
                String id = gp.getClaimId(c).orElse("?");
                String parentId = gp.getClaimId(toMainClaim(c)).orElse("?");
                String name = store.get(id).orElse("");
                String line = formatTrustedClaimLine(c, id, name, parentId, player);
                // Parse color codes in the line
                sender.sendMessage(parseColorCodes(line));

                // Show subclaims of trusted claims if the player is trusted on them
                List<Object> trustedSubs = getSubclaims(c);
                for (Object sub : trustedSubs) {
                    if (gp.getClaimsWhereTrusted(player.getUniqueId()).contains(sub)) {
                        String subId = gp.getClaimId(sub).orElse("");
                        String subName = store.get(subId).orElse("");
                        String subLine = formatSubclaimLine(sub, id, subName);
                        sender.sendMessage(parseColorCodes("  " + subLine));
                    }
                }
            }
        }

        gp.getPlayerClaimStats(player).ifPresent(stats -> {
            sender.sendMessage(Component.text(String.format("= %d blocks left to spend", stats.remaining), NamedTextColor.YELLOW));
        });
        return true;
    }
    
    private boolean handleAdminClaimsList(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return true;
        }
        Player player = (Player)sender;
        if (!player.isOp() && !player.hasPermission("gpexpansion.adminclaimslist")) {
            sender.sendMessage(Component.text("You lack permission: gpexpansion.adminclaimslist", NamedTextColor.RED));
            return true;
        }
        
        NameStore store = plugin.getNameStore();
        List<Object> all = gp.getAllClaims();
        List<Object> admins = new ArrayList<>();
        
        for (Object c : all) {
            if (gp.isAdminClaim(c)) {
                admins.add(c);
            }
        }

        if (admins.isEmpty()) {
            sender.sendMessage(Component.text("No admin claims found.", NamedTextColor.YELLOW));
            return true;
        }
        
        sender.sendMessage(Component.text(String.format("Admin Claims (%d):", admins.size()), NamedTextColor.YELLOW));
        
        for (Object c : admins) {
            String id = gp.getClaimId(c).orElse("?");
            String name = store.get(id).orElse("unnamed");
            String line = formatClaimLine(c, id, name);
            sender.sendMessage(parseColorCodes(line));
            
            for (Object sub : getSubclaims(c)) {
                String subId = gp.getClaimId(sub).orElse("");
                String subName = store.get(subId).orElse("");
                String subLine = formatSubclaimLine(sub, id, subName);
                // Parse color codes in the line
                sender.sendMessage(parseColorCodes("    " + subLine));
            }
        }
        
        return true;
    }
    
    private boolean handleTransferClaim(CommandSender sender, String[] args) {
        if (!sender.hasPermission("griefprevention.transferclaim")) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }

        boolean playerSender = sender instanceof Player;

        if (playerSender) {
            Player player = (Player) sender;
            Optional<Object> currentClaim = gp.getClaimAt(player.getLocation(), player);
            if (currentClaim.isPresent()) {
                Object claim = currentClaim.get();
                Object main = gp.getParentClaim(claim).orElse(claim);
                String currentId = gp.getClaimId(main).orElse(null);
                if (args.length >= 2 && currentId != null && currentId.equalsIgnoreCase(args[0])) {
                    String[] trimmed = Arrays.copyOfRange(args, 1, args.length);
                    return handleDispatch(sender, "transferclaim", trimmed);
                }
                return handleDispatch(sender, "transferclaim", args);
            }
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /claim transfer <claimId> <player>", NamedTextColor.YELLOW));
            return true;
        }

        String claimId = args[0];
        String targetName = args[1];

        Optional<Object> claimOpt = gp.findClaimById(claimId);
        if (!claimOpt.isPresent()) {
            sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", claimId));
            return true;
        }

        UUID targetUuid = resolvePlayerUuid(targetName);
        if (targetUuid == null) {
            sender.sendMessage(plugin.getMessages().get("general.unknown-player", "{player}", targetName));
            return true;
        }

        Object claim = claimOpt.get();
        Object mainClaim = gp.getParentClaim(claim).orElse(claim);
        String mainClaimId = gp.getClaimId(mainClaim).orElse(claimId);

        if (playerSender) {
            Player player = (Player) sender;
            boolean admin = sender.isOp() || sender.hasPermission("griefprevention.admin");
            if (!admin && !gp.isOwner(mainClaim, player.getUniqueId())) {
                sender.sendMessage(plugin.getMessages().get("claim.not-owner", "{id}", mainClaimId));
                return true;
            }
        }

        boolean transferred = gp.transferClaimOwner(mainClaim, targetUuid);
        if (!transferred) {
            sender.sendMessage(plugin.getMessages().get("claim.transfer-failed", "{id}", mainClaimId));
            return true;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetUuid);
        String displayName = offline.getName() != null ? offline.getName() : targetName;

        sender.sendMessage(plugin.getMessages().get("claim.transfer-success", "{id}", mainClaimId, "{player}", displayName));

        Player targetOnline = Bukkit.getPlayer(targetUuid);
        if (targetOnline != null) {
            targetOnline.sendMessage(plugin.getMessages().get("claim.transfer-received", "{id}", mainClaimId));
        }

        return true;
    }
    
    private boolean handleName(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        if (!sender.hasPermission("gpexpansion.claim.name")) {
            sender.sendMessage(Component.text("You lack permission: gpexpansion.claim.name", NamedTextColor.RED));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("gpexpansion.claim.name.other");

        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /claim name <newName> [claimId]", NamedTextColor.YELLOW));
            return true;
        }

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;
        String[] nameParts = args;

        if (allowOther && args.length >= 2) {
            String possibleId = args[args.length - 1];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (looked.isPresent()) {
                explicitClaim = looked;
                explicitId = possibleId;
                nameParts = Arrays.copyOf(args, args.length - 1);
            }
        }

        String legacyName = String.join(" ", nameParts).trim();
        if (legacyName.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /claim name <newName> [claimId]", NamedTextColor.YELLOW));
            return true;
        }

        String enforced = enforceNamePermissions(sender, legacyName);
        String stored = toAmpersand(enforced);

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, "rename this claim");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        NameStore store = plugin.getNameStore();
        store.set(ctx.claimId, stored);
        store.save();

        String display = stored.isEmpty() ? "&7unnamed" : stored;
        String feedback = String.format("&aClaim %s renamed to %s", ctx.claimId, display);
        sender.sendMessage(parseColorCodes(feedback));
        return true;
    }
    
    private boolean handleBan(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        if (!sender.hasPermission("griefprevention.claim.ban")) {
            sender.sendMessage(Component.text("You lack permission: griefprevention.claim.ban", NamedTextColor.RED));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.ban.other");

        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /claim ban <player|public> [claimId]", NamedTextColor.YELLOW));
            return true;
        }

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;
        String[] workingArgs = args;

        if (allowOther && args.length >= 2) {
            String possibleId = args[args.length - 1];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (!looked.isPresent()) {
                sender.sendMessage(Component.text("Claim ID not found: " + possibleId, NamedTextColor.RED));
                return true;
            }
            explicitClaim = looked;
            explicitId = possibleId;
            workingArgs = Arrays.copyOf(args, args.length - 1);
        }

        if (workingArgs.length == 0) {
            sender.sendMessage(Component.text("Usage: /claim ban <player|public> [claimId]", NamedTextColor.YELLOW));
            return true;
        }

        String targetName = workingArgs[0];
        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, "ban players here");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        BanStore banStore = plugin.getBanStore();

        if (targetName.equalsIgnoreCase("public")) {
            if (banStore.isPublicBanned(ctx.mainClaimId)) {
                sender.sendMessage(Component.text("Claim " + ctx.mainClaimId + " already has a public ban.", NamedTextColor.YELLOW));
                return true;
            }
            banStore.setPublic(ctx.mainClaimId, true);
            banStore.save();
            sender.sendMessage(Component.text("Claim " + ctx.mainClaimId + " is now public-banned.", NamedTextColor.GREEN));
            return true;
        }

        UUID targetUuid = resolvePlayerUuid(targetName);

        if (targetUuid == null) {
            sender.sendMessage(Component.text("Unknown player: " + targetName, NamedTextColor.RED));
            return true;
        }

        if (targetUuid.equals(player.getUniqueId())) {
            sender.sendMessage(Component.text("You cannot ban yourself.", NamedTextColor.RED));
            return true;
        }

        banStore.add(ctx.mainClaimId, targetUuid);
        banStore.save();

        sender.sendMessage(Component.text("Banned " + targetName + " from claim " + ctx.mainClaimId + ".", NamedTextColor.GREEN));

        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null) {
            boolean insideClaim = gp.getClaimAt(target.getLocation(), target)
                    .flatMap(found -> {
                        Object main = gp.getParentClaim(found).orElse(found);
                        return gp.getClaimId(main);
                    })
                    .map(ctx.mainClaimId::equals)
                    .orElse(false);

            if (insideClaim) {
                Optional<Location> eject = gp.getClaimCenter(ctx.mainClaim);
                eject.ifPresent(location -> plugin.teleportEntity(target, location));
            }
        }

        return true;
    }
    
    private boolean handleUnban(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        if (!sender.hasPermission("griefprevention.claim.unban")) {
            sender.sendMessage(Component.text("You lack permission: griefprevention.claim.unban", NamedTextColor.RED));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.unban.other");

        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /claim unban <player|public> [claimId]", NamedTextColor.YELLOW));
            return true;
        }

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;
        String[] workingArgs = args;

        if (allowOther && args.length >= 2) {
            String possibleId = args[args.length - 1];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (looked.isPresent()) {
                explicitClaim = looked;
                explicitId = possibleId;
                workingArgs = Arrays.copyOf(args, args.length - 1);
            }
        }

        if (workingArgs.length == 0) {
            sender.sendMessage(Component.text("Usage: /claim unban <player|public> [claimId]", NamedTextColor.YELLOW));
            return true;
        }

        String targetName = workingArgs[0];
        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, "unban players here");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        BanStore banStore = plugin.getBanStore();

        if (targetName.equalsIgnoreCase("public")) {
            if (!banStore.isPublicBanned(ctx.mainClaimId)) {
                sender.sendMessage(Component.text("Claim " + ctx.mainClaimId + " is not public-banned.", NamedTextColor.YELLOW));
                return true;
            }
            banStore.setPublic(ctx.mainClaimId, false);
            banStore.save();
            sender.sendMessage(Component.text("Claim " + ctx.mainClaimId + " no longer has a public ban.", NamedTextColor.GREEN));
            return true;
        }

        UUID targetUuid = resolvePlayerUuid(targetName);

        if (targetUuid == null) {
            String onlineNames = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(", "));
            Component message = Component.text("Unknown player: " + targetName + ".", NamedTextColor.RED);
            if (!onlineNames.isEmpty()) {
                message = message.append(Component.text(" Online players: " + onlineNames, NamedTextColor.YELLOW));
            }
            sender.sendMessage(message);
            return true;
        }

        if (!banStore.getPlayers(ctx.mainClaimId).contains(targetUuid)) {
            sender.sendMessage(Component.text(targetName + " is not banned from claim " + ctx.mainClaimId + ".", NamedTextColor.YELLOW));
            return true;
        }

        banStore.remove(ctx.mainClaimId, targetUuid);
        banStore.save();

        sender.sendMessage(Component.text("Unbanned " + targetName + " from claim " + ctx.mainClaimId + ".", NamedTextColor.GREEN));
        return true;
    }
    
    private boolean handleBanList(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        if (!sender.hasPermission("griefprevention.claim.ban")) {
            sender.sendMessage(Component.text("You lack permission: griefprevention.claim.ban", NamedTextColor.RED));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.ban.other");

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;

        if (allowOther && args.length >= 1) {
            String possibleId = args[args.length - 1];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (!looked.isPresent()) {
                sender.sendMessage(Component.text("Claim ID not found: " + possibleId, NamedTextColor.RED));
                return true;
            }
            explicitClaim = looked;
            explicitId = possibleId;
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, "view the ban list for this claim");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        BanStore banStore = plugin.getBanStore();
        BanStore.BanEntry entry = banStore.get(ctx.mainClaimId);

        sender.sendMessage(Component.text("Ban list for claim " + ctx.mainClaimId + ":", NamedTextColor.YELLOW));

        if (entry.banPublic) {
            sender.sendMessage(Component.text(" - Public banned", NamedTextColor.GOLD));
        }

        if (entry.players.isEmpty()) {
            sender.sendMessage(Component.text(" - No players banned", NamedTextColor.GRAY));
            return true;
        }

        for (UUID uuid : entry.players) {
            String name = entry.names.getOrDefault(uuid, Bukkit.getOfflinePlayer(uuid).getName());
            if (name == null) name = uuid.toString();
            sender.sendMessage(Component.text(" - " + name, NamedTextColor.RED));
        }
        return true;
    }

    private static class ClaimContext {
        final Object mainClaim;
        final String claimId;
        final String mainClaimId;

        ClaimContext(Object mainClaim, String claimId, String mainClaimId) {
            this.mainClaim = mainClaim;
            this.claimId = claimId;
            this.mainClaimId = mainClaimId;
        }
    }

    private Optional<ClaimContext> resolveClaimContext(CommandSender sender, Player player, Optional<Object> explicitClaim,
                                                       String explicitId, boolean allowOther, boolean requireOwnership,
                                                       String actionDescription) {
        Object claim = explicitClaim.orElse(null);
        String claimId = explicitId;

        if (claim == null) {
            Optional<Object> claimOpt = gp.getClaimAt(player.getLocation(), player);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(Component.text("You are not standing in a claim." + (explicitId == null ? " Provide a claim ID." : ""), NamedTextColor.RED));
                return Optional.empty();
            }
            claim = claimOpt.get();
        }

        if (claimId == null) {
            claimId = gp.getClaimId(claim).orElse(null);
        }
        if (claimId == null) {
            sender.sendMessage(Component.text("Could not determine claim ID for this action.", NamedTextColor.RED));
            return Optional.empty();
        }

        Object mainClaim = gp.getParentClaim(claim).orElse(claim);
        Optional<String> mainIdOpt = gp.getClaimId(mainClaim);
        if (!mainIdOpt.isPresent()) {
            sender.sendMessage(Component.text("Could not determine main claim ID.", NamedTextColor.RED));
            return Optional.empty();
        }
        String mainClaimId = mainIdOpt.get();

        boolean isOwner = gp.isOwner(mainClaim, player.getUniqueId());
        if (requireOwnership && !isOwner && !allowOther) {
            sender.sendMessage(Component.text("You must own this claim to " + actionDescription + ".", NamedTextColor.RED));
            return Optional.empty();
        }

        return Optional.of(new ClaimContext(mainClaim, claimId, mainClaimId));
    }

    private UUID resolvePlayerUuid(String name) {
        if (name == null || name.isEmpty()) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (offline != null && offline.getUniqueId() != null) {
                return offline.getUniqueId();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private Component parseColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        
        // Replace & color codes with TextColor components
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
    
    // Helper methods for claim formatting
    private String formatClaimLine(Object claim, String id, String name) {
        // Format: "&eID <claimId> (<name>|unnamed) <world>: x<x>, z<z> (-<usedBLocksAmt> blocks)"
        String displayName = name.isEmpty() ? "unnamed" : name;
        String worldName = gp.getClaimWorld(claim).orElse("unknown");
        String coords = getClaimCenterCoords(claim);
        int area = gp.getClaimArea(claim);
        
        return String.format("&eID %s &e(%s&e) %s: %s (-%d blocks)", 
            id, displayName, worldName, coords, area);
    }
    
    private String formatSubclaimLine(Object subclaim, String parentId, String name) {
        // Format: "&7- ID &f<id> &7(unnamed) &fworld&7: &fx&7<x>&f, z&7<z> &8(&6Child of <parentId>&8)"
        String id = gp.getClaimId(subclaim).orElse("");
        String displayName = name.isEmpty() ? "unnamed" : name;
        String worldName = gp.getClaimWorld(subclaim).orElse("unknown");
        String coords = getClaimCenterCoords(subclaim);
        
        // Format coordinates to match the example: "x-599 z-1056"
        String formattedCoords = coords.replace("x", "").replace(", z", " ").replace(",", "");
        
        return String.format("&7- ID &f%s &7(&7%s&7) &f%s&7: &f%s&7, &f%s &8(&6Child of %s&8)", 
            id, displayName, worldName, 
            formattedCoords.contains(" ") ? "x" + formattedCoords.split(" ")[0] : "x" + formattedCoords,
            formattedCoords.contains(" ") ? "z" + formattedCoords.split(" ")[1] : "z" + formattedCoords,
            parentId);
    }
    
    private String formatTrustedClaimLine(Object claim, String id, String name, String parentId, Player player) {
        // Format: "&eID <claimId> (<name>|unnamed) <world>: x<x>, z<z> (-<usedBLocksAmt> blocks)"
        String displayName = name.isEmpty() ? "unnamed" : name;
        String worldName = gp.getClaimWorld(claim).orElse("unknown");
        String coords = getClaimCenterCoords(claim);
        int area = gp.getClaimArea(claim);
        
        return String.format("&eID %s &e(%s) %s: %s (-%d blocks)", 
            id, displayName, worldName, coords, area);
    }
    
    private String getClaimCenterCoords(Object claim) {
        // Format: "x<x>, z<z>" (center of the claim)
        return gp.getClaimCorners(claim)
            .map(corners -> {
                int centerX = (corners.x1 + corners.x2) / 2;
                int centerZ = (corners.z1 + corners.z2) / 2;
                return String.format("x%d, z%d", centerX, centerZ);
            })
            .orElse("unknown location");
    }
    
    private Object toMainClaim(Object claim) {
        // Get the main/parent claim if this is a subclaim
        return gp.getParentClaim(claim).orElse(claim);
    }
    
    private List<Object> getSubclaims(Object claim) {
        // Get all subclaims for a claim
        return gp.getSubclaims(claim);
    }
    
    private List<Object> getTrustedClaimsFor(Player player) {
        // Get all claims where player is trusted
        return gp.getClaimsWhereTrusted(player.getUniqueId());
    }
    
    private boolean handleEvict(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        if (!sender.hasPermission("griefprevention.evict")) {
            sender.sendMessage(Component.text("You lack permission: griefprevention.evict", NamedTextColor.RED));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.evict.other");

        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /claim evict [claimId]", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("Starts a 14-day eviction notice for the renter.", NamedTextColor.GRAY));
            return true;
        }

        // Check for cancel subcommand
        if (args[0].equalsIgnoreCase("cancel")) {
            return handleEvictCancel(sender, Arrays.copyOfRange(args, 1, args.length));
        }

        // Check for status subcommand
        if (args[0].equalsIgnoreCase("status")) {
            return handleEvictStatus(sender, Arrays.copyOfRange(args, 1, args.length));
        }

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;

        // Check if argument is a claim ID
        String possibleId = args[0];
        Optional<Object> looked = gp.findClaimById(possibleId);
        if (looked.isPresent()) {
            explicitClaim = looked;
            explicitId = possibleId;
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, "evict players from");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();

        // Check if the claim is currently rented
        RentalStore store = plugin.getRentalStore();
        Optional<RentalStore.Entry> rentalOpt = Optional.ofNullable(store.all().get(ctx.mainClaimId));
        if (!rentalOpt.isPresent()) {
            sender.sendMessage(Component.text("This claim is not currently rented.", NamedTextColor.RED));
            return true;
        }

        RentalStore.Entry rental = rentalOpt.get();
        dev.towki.gpexpansion.storage.EvictionStore evictionStore = plugin.getEvictionStore();

        // Check if there's already a pending eviction
        if (evictionStore.hasPendingEviction(ctx.mainClaimId)) {
            dev.towki.gpexpansion.storage.EvictionStore.EvictionEntry existing = evictionStore.getEviction(ctx.mainClaimId);
            if (existing.isEffective()) {
                sender.sendMessage(Component.text("The eviction notice period has passed. You can now remove the renter.", NamedTextColor.GREEN));
                sender.sendMessage(Component.text("Use /claim evict cancel " + ctx.mainClaimId + " to cancel or break the rental sign.", NamedTextColor.GRAY));
            } else {
                String remaining = formatDuration(existing.getRemainingTime());
                sender.sendMessage(Component.text("An eviction is already in progress. " + remaining + " remaining before you can remove the renter.", NamedTextColor.YELLOW));
            }
            return true;
        }

        // Start the 14-day eviction notice
        evictionStore.initiateEviction(ctx.mainClaimId, player.getUniqueId(), rental.renter);

        // Mark the rental as being evicted
        rental.beingEvicted = true;
        store.update(ctx.mainClaimId, rental);
        store.save();

        String renterName = Bukkit.getOfflinePlayer(rental.renter).getName();
        if (renterName == null) renterName = rental.renter.toString();

        sender.sendMessage(Component.text("Eviction notice started for " + renterName + ".", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("They have 14 days before you can remove them from the claim or break the sign.", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("During this time, the renter cannot extend their rental.", NamedTextColor.GRAY));

        // Notify the renter if they're online
        Player renter = Bukkit.getPlayer(rental.renter);
        if (renter != null) {
            renter.sendMessage(Component.text("You have received an eviction notice for claim " + ctx.mainClaimId + ".", NamedTextColor.RED));
            renter.sendMessage(Component.text("You have 14 days before you will be removed from this claim.", NamedTextColor.YELLOW));
            renter.sendMessage(Component.text("You will not be able to extend your rental during this time.", NamedTextColor.GRAY));
        }

        return true;
    }

    private boolean handleEvictCancel(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.evict.other");

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;

        if (args.length > 0) {
            String possibleId = args[0];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (looked.isPresent()) {
                explicitClaim = looked;
                explicitId = possibleId;
            }
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, "cancel eviction for");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        dev.towki.gpexpansion.storage.EvictionStore evictionStore = plugin.getEvictionStore();

        if (!evictionStore.hasPendingEviction(ctx.mainClaimId)) {
            sender.sendMessage(Component.text("There is no pending eviction for this claim.", NamedTextColor.RED));
            return true;
        }

        // Cancel the eviction
        evictionStore.cancelEviction(ctx.mainClaimId);

        // Update rental store
        RentalStore store = plugin.getRentalStore();
        RentalStore.Entry rental = store.all().get(ctx.mainClaimId);
        if (rental != null) {
            rental.beingEvicted = false;
            store.update(ctx.mainClaimId, rental);
            store.save();

            // Notify the renter if online
            Player renter = Bukkit.getPlayer(rental.renter);
            if (renter != null) {
                renter.sendMessage(Component.text("The eviction notice for claim " + ctx.mainClaimId + " has been cancelled.", NamedTextColor.GREEN));
            }
        }

        sender.sendMessage(Component.text("Eviction cancelled for claim " + ctx.mainClaimId + ".", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleEvictStatus(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.evict.other");

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;

        if (args.length > 0) {
            String possibleId = args[0];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (looked.isPresent()) {
                explicitClaim = looked;
                explicitId = possibleId;
            }
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, false, "check eviction status for");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        dev.towki.gpexpansion.storage.EvictionStore evictionStore = plugin.getEvictionStore();

        if (!evictionStore.hasPendingEviction(ctx.mainClaimId)) {
            sender.sendMessage(Component.text("There is no pending eviction for this claim.", NamedTextColor.GRAY));
            return true;
        }

        dev.towki.gpexpansion.storage.EvictionStore.EvictionEntry eviction = evictionStore.getEviction(ctx.mainClaimId);
        String renterName = Bukkit.getOfflinePlayer(eviction.renterId).getName();
        if (renterName == null) renterName = eviction.renterId.toString();

        if (eviction.isEffective()) {
            sender.sendMessage(Component.text("Eviction for " + renterName + " is now effective.", NamedTextColor.GREEN));
            sender.sendMessage(Component.text("You can now remove them from the claim or break the rental sign.", NamedTextColor.GRAY));
        } else {
            String remaining = formatDuration(eviction.getRemainingTime());
            sender.sendMessage(Component.text("Eviction notice for " + renterName + " is pending.", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text(remaining + " remaining before you can remove them.", NamedTextColor.GRAY));
        }

        return true;
    }

    private boolean handleRentalSignConfirm(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;

        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /claim rentalsignconfirm <world> <x> <y> <z>", NamedTextColor.YELLOW));
            return true;
        }

        String worldName = args[0];
        int x, y, z;

        try {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Coordinates must be integers.", NamedTextColor.RED));
            return true;
        }

        org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(Component.text("Unknown world: " + worldName, NamedTextColor.RED));
            return true;
        }

        org.bukkit.block.Block b = world.getBlockAt(x, y, z);
        if (!(b.getState() instanceof org.bukkit.block.Sign)) {
            sender.sendMessage(Component.text("No managed sign found at that location.", NamedTextColor.RED));
            return true;
        }

        org.bukkit.block.Sign sign = (org.bukkit.block.Sign) b.getState();
        org.bukkit.NamespacedKey keyKind = new org.bukkit.NamespacedKey(plugin, "sign.kind");
        org.bukkit.NamespacedKey keyClaim = new org.bukkit.NamespacedKey(plugin, "sign.claimId");
        org.bukkit.NamespacedKey keyRenter = new org.bukkit.NamespacedKey(plugin, "rent.renter");

        if (!sign.getPersistentDataContainer().has(keyKind, org.bukkit.persistence.PersistentDataType.STRING)) {
            sender.sendMessage(Component.text("That sign is not managed by GPExpansion.", NamedTextColor.RED));
            return true;
        }

        String claimId = sign.getPersistentDataContainer().get(keyClaim, org.bukkit.persistence.PersistentDataType.STRING);
        String renterStr = sign.getPersistentDataContainer().get(keyRenter, org.bukkit.persistence.PersistentDataType.STRING);

        // Permission: must own claim or be admin
        boolean admin = sender.isOp() || sender.hasPermission("griefprevention.admin") || sender.hasPermission("griefprevention.sign.admin");
        boolean owner = false;

        if (claimId != null) {
            java.util.Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (claimOpt.isPresent()) {
                try {
                    Object claim = claimOpt.get();
                    Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
                    owner = ownerId != null && ownerId.equals(player.getUniqueId());
                } catch (ReflectiveOperationException ignored) {}
            }
        }

        if (!(owner || admin)) {
            sender.sendMessage(Component.text("You don't have permission to use this sign.", NamedTextColor.RED));
            return true;
        }

        // Clear rental store
        if (claimId != null) {
            dev.towki.gpexpansion.storage.RentalStore store = plugin.getRentalStore();
            if (store != null) {
                store.clear(claimId);
                store.save();
            }
        }

        // Revoke trust of renter if present
        if (claimId != null && renterStr != null) {
            try {
                java.util.Optional<Object> claimOpt = gp.findClaimById(claimId);
                if (claimOpt.isPresent()) {
                    java.util.UUID renter = java.util.UUID.fromString(renterStr);
                    String renterName = org.bukkit.Bukkit.getOfflinePlayer(renter).getName();
                    if (renterName != null) {
                        // Use GPBridge to untrust from this specific claim only
                        boolean untrusted = gp.untrust(renterName, claimOpt.get());
                        if (untrusted) {
                            plugin.getLogger().info("Removed trust for " + renterName + " from claim " + claimId);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Remove the sign block
        b.setType(org.bukkit.Material.AIR);
        sender.sendMessage(Component.text("Rental sign removed and rental cleared.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleCollectRent(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;

        PendingRentStore pendingStore = plugin.getPendingRentStore();
        if (pendingStore == null) {
            sender.sendMessage(Component.text("Pending rent system is not available.", NamedTextColor.RED));
            return true;
        }

        // Check if player has any pending rents to collect
        boolean hasPending = false;
        double totalMoney = 0;
        int totalExp = 0;
        int totalClaimBlocks = 0;

        for (PendingRentStore.PendingRentEntry entry : pendingStore.all().values()) {
            if (entry.owner.equals(player.getUniqueId())) {
                hasPending = true;
                try {
                    double amount = Double.parseDouble(entry.amount);
                    switch (entry.kind) {
                        case "MONEY":
                            totalMoney += amount;
                            break;
                        case "EXPERIENCE":
                            totalExp += (int) amount;
                            break;
                        case "CLAIMBLOCKS":
                            totalClaimBlocks += (int) amount;
                            break;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        if (!hasPending) {
            sender.sendMessage(Component.text("You have no pending rental payments to collect.", NamedTextColor.YELLOW));
            return true;
        }

        // Give the player their pending payments
        boolean success = true;

        if (totalMoney > 0 && plugin.isEconomyAvailable()) {
            if (!plugin.depositMoney(player, totalMoney)) {
                success = false;
                sender.sendMessage(Component.text("Failed to give you $" + totalMoney + " from rentals.", NamedTextColor.RED));
            }
        }

        if (totalExp > 0) {
            player.giveExp(totalExp);
        }

        if (totalClaimBlocks > 0) {
            // Note: Claim blocks would need GP integration here
            sender.sendMessage(Component.text("You received " + totalClaimBlocks + " claim blocks from rentals!", NamedTextColor.GREEN));
        }

        if (success) {
            // Clear all pending rents for this player
            pendingStore.all().entrySet().removeIf(entry -> {
                if (entry.getValue().owner.equals(player.getUniqueId())) {
                    pendingStore.removePendingRent(entry.getKey());
                    return true;
                }
                return false;
            });
            pendingStore.save();

            sender.sendMessage(Component.text("Successfully collected all pending rental payments!", NamedTextColor.GREEN));
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (sender instanceof Player && isFolia()) {
            // For Folia, we need to handle tab completion on the main thread
            Player player = (Player) sender;
            CompletableFuture<List<String>> future = new CompletableFuture<>();
            player.getScheduler().execute(plugin, () -> {
                future.complete(completeTab(sender, command, alias, args));
            }, null, 1L);
            try {
                return future.join();
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
        return completeTab(sender, command, alias, args);
    }
    
    private @Nullable List<String> completeTab(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length >= 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            // Basic suggestions; can be expanded
            switch (sub) {
                case "name":
                    return Collections.singletonList("<name...>");
                case "create":
                    return Collections.singletonList("<radius>");
                case "adminclaimslist":
                case "adminlist":
                    return new ArrayList<>();
                case "ban":
                case "unban":
                    if (args.length == 2) return Collections.singletonList("<player>");
                    if (args.length == 3) return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
                case "trust":
                case "untrust":
                case "containertrust":
                case "accesstrust":
                case "permissiontrust":
                    if (args.length == 2) return Collections.singletonList("<player>");
                    if (args.length == 3) return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
                case "evict":
                    if (args.length == 2) return Arrays.asList("cancel", "status", "[claimId]");
                    return new ArrayList<>();
                case "trustlist":
                case "restrictsubclaim":
                case "explosions":
                    if (args.length == 2) return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
                case "abandon":
                    if (args.length == 2) return Arrays.asList("all", "[claimId]");
                    return new ArrayList<>();
                case "list":
                case "banlist":
                case "subdivideclaim":
                case "3dsubdivideclaim":
                case "abandonall":
                case "basic":
                default:
                    return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }
}
