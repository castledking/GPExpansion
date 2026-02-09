package dev.towki.gpexpansion.command;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.storage.ClaimDataStore;
import net.kyori.adventure.text.Component;
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

    /** Subcommands we handle - used when sharing /claim with GP3D (only intercept these) */
    public static final java.util.Set<String> HANDLED_SUBCOMMANDS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(Arrays.asList(
            "!", "name", "list", "create", "adminlist", "tp", "teleport", "setspawn", "global", "globallist", "icon", "desc",
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
            "snapshot",          // -> snapshot list|remove|create [id]
            // Moderation placeholders
            "ban", "unban", "banlist"
    )));

    private static final List<String> SUBS = Arrays.asList(
            // Our features
            "!", "name", "list", "create", "adminlist", "tp", "teleport", "setspawn", "global", "globallist", "icon", "desc",
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
            "transfer",          // -> transfer
            "rentalsignconfirm",
            "evict",
            "collectrent",
            "snapshot",
            "ban", "unban", "banlist"
    );

    // Color code to permission name mapping
    private static final java.util.Map<Character, String> COLOR_PERMISSIONS = new java.util.HashMap<>();
    private static final java.util.Map<Character, String> FORMAT_PERMISSIONS = new java.util.HashMap<>();
    static {
        // Colors: &0-&9, &a-&f
        COLOR_PERMISSIONS.put('0', "black");
        COLOR_PERMISSIONS.put('1', "dark_blue");
        COLOR_PERMISSIONS.put('2', "dark_green");
        COLOR_PERMISSIONS.put('3', "dark_aqua");
        COLOR_PERMISSIONS.put('4', "dark_red");
        COLOR_PERMISSIONS.put('5', "dark_purple");
        COLOR_PERMISSIONS.put('6', "gold");
        COLOR_PERMISSIONS.put('7', "gray");
        COLOR_PERMISSIONS.put('8', "dark_gray");
        COLOR_PERMISSIONS.put('9', "blue");
        COLOR_PERMISSIONS.put('a', "green");
        COLOR_PERMISSIONS.put('b', "aqua");
        COLOR_PERMISSIONS.put('c', "red");
        COLOR_PERMISSIONS.put('d', "light_purple");
        COLOR_PERMISSIONS.put('e', "yellow");
        COLOR_PERMISSIONS.put('f', "white");
        // Formats: &k-&o, &r
        FORMAT_PERMISSIONS.put('k', "obfuscated");
        FORMAT_PERMISSIONS.put('l', "bold");
        FORMAT_PERMISSIONS.put('m', "strikethrough");
        FORMAT_PERMISSIONS.put('n', "underline");
        FORMAT_PERMISSIONS.put('o', "italic");
        FORMAT_PERMISSIONS.put('r', "reset");
    }
    
    /**
     * Filter color and format codes based on player permissions.
     * Permission structure: griefprevention.claim.color.<color> and griefprevention.claim.format.<format>
     * Used for both /claim name and /claim desc commands.
     */
    private String enforceColorPermissions(CommandSender sender, String text) {
        if (text == null || text.isEmpty()) return text;
        
        // Check for wildcard permissions first
        boolean hasAllColors = sender.hasPermission("griefprevention.claim.color.*");
        boolean hasAllFormats = sender.hasPermission("griefprevention.claim.format.*");
        
        if (hasAllColors && hasAllFormats) {
            return text; // Has all permissions, return unchanged
        }
        
        StringBuilder result = new StringBuilder();
        char[] chars = text.toCharArray();
        
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            
            // Check for color/format code prefix (& or §)
            if ((c == '&' || c == '\u00A7') && i + 1 < chars.length) {
                char code = Character.toLowerCase(chars[i + 1]);
                
                // Check if it's a color code (0-9, a-f)
                if (COLOR_PERMISSIONS.containsKey(code)) {
                    if (hasAllColors || sender.hasPermission("griefprevention.claim.color." + COLOR_PERMISSIONS.get(code))) {
                        result.append(c).append(chars[i + 1]);
                    }
                    // Skip the code character regardless
                    i++;
                    continue;
                }
                
                // Check if it's a format code (k-o, r)
                if (FORMAT_PERMISSIONS.containsKey(code)) {
                    if (hasAllFormats || sender.hasPermission("griefprevention.claim.format." + FORMAT_PERMISSIONS.get(code))) {
                        result.append(c).append(chars[i + 1]);
                    }
                    // Skip the code character regardless
                    i++;
                    continue;
                }
                
                // Check for hex color codes: &x&R&R&G&G&B&B or §x§R§R§G§G§B§B
                if (code == 'x' && i + 13 < chars.length) {
                    // Hex colors require the wildcard color permission
                    if (hasAllColors) {
                        // Copy the full hex sequence
                        for (int j = 0; j < 14; j++) {
                            result.append(chars[i + j]);
                        }
                    }
                    i += 13; // Skip the hex sequence
                    continue;
                }
            }
            
            result.append(c);
        }
        
        return result.toString();
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
        // Support standalone /claimtp command
        if (command.getName().equalsIgnoreCase("claimtp") || label.equalsIgnoreCase("claimtp")) {
            return handleTeleport(sender, args);
        }
        // Support standalone /setclaimspawn command
        if (command.getName().equalsIgnoreCase("setclaimspawn") || label.equalsIgnoreCase("setclaimspawn")) {
            return handleSetSpawn(sender, args);
        }
        // Support standalone /globalclaimlist command
        if (command.getName().equalsIgnoreCase("globalclaimlist") || label.equalsIgnoreCase("globalclaimlist")) {
            return handleGlobalList(sender);
        }
        // Support standalone /globalclaim [true|false] [claimId] command (toggle when no args)
        if (command.getName().equalsIgnoreCase("globalclaim") || label.equalsIgnoreCase("globalclaim")) {
            return handleGlobalClaim(sender, args);
        }
        if (args.length == 0) {
            // Check if GUI mode is enabled and sender is a player
            if (sender instanceof Player && plugin.getGUIManager() != null && plugin.getGUIManager().isGUIEnabled()) {
                plugin.getGUIManager().openMainMenu((Player) sender);
                return true;
            }
            sender.sendMessage(plugin.getMessages().get("commands.claim-usage",
                "{label}", label,
                "{subs}", String.join("|", SUBS)));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "!":
                return handleReturnToGUI(sender);
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
            case "snapshot":
                return handleSnapshot(sender, subArgs);
            case "tp":
            case "teleport":
                return handleTeleport(sender, subArgs);
            case "setspawn":
                return handleSetSpawn(sender, subArgs);
            case "globallist":
                return handleGlobalList(sender);
            case "global":
                return handleGlobalClaim(sender, subArgs);
            case "icon":
                return handleIcon(sender, subArgs);
            case "desc":
            case "description":
                return handleDescription(sender, subArgs);
            default:
                // Try to delegate to GP3D's UnifiedClaimCommand if available
                if (tryDelegateToGP3D(sender, command, label, args)) {
                    return true;
                }
                // Otherwise show help message
                sender.sendMessage(plugin.getMessages().get("commands.unknown-subcommand-help"));
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
    
    /**
     * Handle /claim ! - return to the last viewed GUI with preserved state.
     */
    private boolean handleReturnToGUI(CommandSender sender) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        
        // Check permission
        if (!player.hasPermission("griefprevention.claim.gui.return")) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }
        
        // Check if GUI is enabled
        if (plugin.getGUIManager() == null || !plugin.getGUIManager().isGUIEnabled()) {
            sender.sendMessage(plugin.getMessages().get("gui.not-enabled"));
            return true;
        }
        
        // Try to restore last GUI
        if (!dev.towki.gpexpansion.gui.GUIStateTracker.restoreLastGUI(plugin.getGUIManager(), player)) {
            sender.sendMessage(plugin.getMessages().get("gui.no-previous"));
            return true;
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
                        sender.sendMessage(plugin.getMessages().get("claim.teleport-safe-location-fail", "{id}", possibleId));
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
                            sender.sendMessage(plugin.getMessages().get("commands.exec-failed", "{command}", "/" + finalBaseCmd));
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
                    sender.sendMessage(plugin.getMessages().get("commands.exec-error", "{error}", e.getMessage()));
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
                sender.sendMessage(plugin.getMessages().get("commands.exec-failed", "{command}", "/" + base));
            }
        } catch (Exception e) {
            sender.sendMessage(plugin.getMessages().get("commands.exec-error", "{error}", e.getMessage()));
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
        dev.towki.gpexpansion.storage.ClaimDataStore store = plugin.getClaimDataStore();
        gp.getPlayerClaimStats(player).ifPresent(stats -> {
            sender.sendMessage(plugin.getMessages().get("claim.blocks-total",
                "{accrued}", String.valueOf(stats.accrued),
                "{bonus}", String.valueOf(stats.bonus),
                "{total}", String.valueOf(stats.total)));
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

        sender.sendMessage(plugin.getMessages().get("claim.list-header",
            "{count}", String.valueOf(grouped.size())));

        for (Map.Entry<Object, List<Object>> e : grouped.entrySet()) {
            Object parent = e.getKey();
            String id = gp.getClaimId(parent).orElse("?");
            String parentName = store.getCustomName(id).orElse("unnamed");
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
                String subName = store.getCustomName(subId).orElse("");
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
            sender.sendMessage(plugin.getMessages().get("claim.list-trusted-header",
                "{count}", String.valueOf(trusted.size())));
            for (Object c : trusted) {
                String id = gp.getClaimId(c).orElse("?");
                String parentId = gp.getClaimId(toMainClaim(c)).orElse("?");
                String name = store.getCustomName(id).orElse("unnamed");
                String line = formatTrustedClaimLine(c, id, name, parentId, player);
                // Parse color codes in the line
                sender.sendMessage(parseColorCodes(line));

                // Show subclaims of trusted claims if the player is trusted on them
                List<Object> trustedSubs = getSubclaims(c);
                for (Object sub : trustedSubs) {
                    if (gp.getClaimsWhereTrusted(player.getUniqueId()).contains(sub)) {
                        String subId = gp.getClaimId(sub).orElse("");
                        String subName = store.getCustomName(subId).orElse("");
                        String subLine = formatSubclaimLine(sub, id, subName);
                        sender.sendMessage(parseColorCodes("  " + subLine));
                    }
                }
            }
        }

        gp.getPlayerClaimStats(player).ifPresent(stats -> {
            sender.sendMessage(plugin.getMessages().get("claim.blocks-remaining",
                "{remaining}", String.valueOf(stats.remaining)));
        });
        return true;
    }
    
    private boolean handleAdminClaimsList(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return true;
        }
        Player player = (Player)sender;
        if (!player.isOp() && !player.hasPermission("griefprevention.adminclaimslist")) {
            sender.sendMessage(plugin.getMessages().get("permissions.missing",
                "{permission}", "griefprevention.adminclaimslist"));
            return true;
        }
        
        dev.towki.gpexpansion.storage.ClaimDataStore store = plugin.getClaimDataStore();
        List<Object> all = gp.getAllClaims();
        List<Object> admins = new ArrayList<>();
        
        for (Object c : all) {
            if (gp.isAdminClaim(c)) {
                admins.add(c);
            }
        }

        if (admins.isEmpty()) return true;
        
        sender.sendMessage(plugin.getMessages().get("claim.list-admin-header",
            "{count}", String.valueOf(admins.size())));
        
        for (Object c : admins) {
            String id = gp.getClaimId(c).orElse("?");
            String name = store.getCustomName(id).orElse("unnamed");
            String line = formatClaimLine(c, id, name);
            sender.sendMessage(parseColorCodes(line));
            
            for (Object sub : getSubclaims(c)) {
                String subId = gp.getClaimId(sub).orElse("");
                String subName = store.getCustomName(subId).orElse("");
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
            sender.sendMessage(plugin.getMessages().get("claim.transfer-usage"));
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
        if (!sender.hasPermission("griefprevention.claim.name")) {
            sender.sendMessage(plugin.getMessages().get("claim.name-no-permission"));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.name.other");
        boolean allowAnywhere = sender.hasPermission("griefprevention.claim.name.anywhere");

        if (args.length == 0) {
            sender.sendMessage(plugin.getMessages().get("claim.name-usage"));
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
            sender.sendMessage(plugin.getMessages().get("claim.name-usage"));
            return true;
        }

        String enforced = enforceColorPermissions(sender, legacyName);
        String stored = toAmpersand(enforced);

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, allowAnywhere, "rename this claim");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        dev.towki.gpexpansion.storage.ClaimDataStore store = plugin.getClaimDataStore();
        store.setCustomName(ctx.claimId, stored);
        store.save();

        String display = stored.isEmpty() ? "&7unnamed" : stored;
        String feedback = String.format("&aClaim %s renamed to %s", ctx.claimId, display);
        sender.sendMessage(parseColorCodes(feedback));
        return true;
    }
    
    // /claim icon [id] - Set claim icon using held item
    private boolean handleIcon(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        if (!sender.hasPermission("griefprevention.claim.icon")) {
            sender.sendMessage(plugin.getMessages().get("claim.icon-no-permission"));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.icon.other") || sender.hasPermission("griefprevention.admin");

        // Get the item in hand
        org.bukkit.inventory.ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == org.bukkit.Material.AIR) {
            sender.sendMessage(plugin.getMessages().get("claim.icon-hold-item"));
            return true;
        }

        String materialName = item.getType().name();

        // Check for explicit claim ID
        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;
        if (args.length >= 1) {
            String possibleId = args[0];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (looked.isPresent()) {
                explicitClaim = looked;
                explicitId = possibleId;
            } else {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", possibleId));
                return true;
            }
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, false, "set icon for this claim");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        plugin.getClaimDataStore().setIcon(ctx.claimId, item.getType());
        plugin.getClaimDataStore().save();

        sender.sendMessage(plugin.getMessages().get("claim.icon-set",
            "{id}", ctx.claimId,
            "{icon}", materialName));
        return true;
    }
    
    // /claim desc <description...> [id] - Set claim description
    private boolean handleDescription(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        if (!sender.hasPermission("griefprevention.claim.description")) {
            sender.sendMessage(plugin.getMessages().get("claim.description-no-permission"));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.description.other") || sender.hasPermission("griefprevention.admin");
        boolean allowAnywhere = sender.hasPermission("griefprevention.claim.description.anywhere");

        if (args.length == 0) {
            sender.sendMessage(plugin.getMessages().get("claim.description-usage"));
            return true;
        }

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;
        String[] descParts = args;

        // Check if last argument is a claim ID
        if (allowOther && args.length >= 2) {
            String possibleId = args[args.length - 1];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (looked.isPresent()) {
                explicitClaim = looked;
                explicitId = possibleId;
                descParts = Arrays.copyOf(args, args.length - 1);
            }
        }

        String description = String.join(" ", descParts).trim();
        if (description.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("claim.description-usage"));
            return true;
        }

        // Apply color/format permissions (same as name)
        description = enforceColorPermissions(sender, description);
        description = toAmpersand(description);

        // Limit description length
        if (description.length() > 64) {
            description = description.substring(0, 64);
            sender.sendMessage(plugin.getMessages().get("claim.description-truncated", "{max}", "64"));
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, allowAnywhere, "set description for this claim");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        plugin.getClaimDataStore().setDescription(ctx.claimId, description);
        plugin.getClaimDataStore().save();

        sender.sendMessage(plugin.getMessages().get("claim.description-set",
            "{id}", ctx.claimId,
            "{description}", description));
        return true;
    }
    
    private boolean handleBan(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        if (!sender.hasPermission("griefprevention.claim.ban")) {
            sender.sendMessage(plugin.getMessages().get("claim.ban-no-permission"));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.ban.other");
        boolean allowAnywhere = sender.hasPermission("griefprevention.claim.ban.anywhere");

        if (args.length == 0) {
            sender.sendMessage(plugin.getMessages().get("claim.ban-usage"));
            return true;
        }

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;
        String[] workingArgs = args;

        if (allowOther && args.length >= 2) {
            String possibleId = args[args.length - 1];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (!looked.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", possibleId));
                return true;
            }
            explicitClaim = looked;
            explicitId = possibleId;
            workingArgs = Arrays.copyOf(args, args.length - 1);
        }

        if (workingArgs.length == 0) {
            sender.sendMessage(plugin.getMessages().get("claim.ban-usage"));
            return true;
        }

        String targetName = workingArgs[0];
        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, allowAnywhere, "ban players here");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        dev.towki.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();

        if (targetName.equalsIgnoreCase("public")) {
            if (dataStore.isPublicBanned(ctx.mainClaimId)) {
                sender.sendMessage(plugin.getMessages().get("claim.ban-already", "{id}", ctx.mainClaimId));
                return true;
            }
            dataStore.setPublicBanned(ctx.mainClaimId, true);
            dataStore.save();
            sender.sendMessage(plugin.getMessages().get("claim.ban-public", "{id}", ctx.mainClaimId));
            return true;
        }

        UUID targetUuid = resolvePlayerUuid(targetName);

        if (targetUuid == null) {
            sender.sendMessage(plugin.getMessages().get("general.unknown-player", "{player}", targetName));
            return true;
        }

        if (targetUuid.equals(player.getUniqueId())) {
            sender.sendMessage(plugin.getMessages().get("claim.ban-self"));
            return true;
        }

        dataStore.addBannedPlayer(ctx.mainClaimId, targetUuid);
        dataStore.save();

        sender.sendMessage(plugin.getMessages().get("claim.ban-success",
            "{player}", targetName,
            "{id}", ctx.mainClaimId));

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
            sender.sendMessage(plugin.getMessages().get("claim.unban-no-permission"));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.unban.other");
        boolean allowAnywhere = sender.hasPermission("griefprevention.claim.unban.anywhere");

        if (args.length == 0) {
            sender.sendMessage(plugin.getMessages().get("claim.unban-usage"));
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
            sender.sendMessage(plugin.getMessages().get("claim.unban-usage"));
            return true;
        }

        String targetName = workingArgs[0];
        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, allowAnywhere, "unban players here");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        dev.towki.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();

        if (targetName.equalsIgnoreCase("public")) {
            if (!dataStore.isPublicBanned(ctx.mainClaimId)) {
                sender.sendMessage(plugin.getMessages().get("claim.unban-public-missing", "{id}", ctx.mainClaimId));
                return true;
            }
            dataStore.setPublicBanned(ctx.mainClaimId, false);
            dataStore.save();
            sender.sendMessage(plugin.getMessages().get("claim.unban-public", "{id}", ctx.mainClaimId));
            return true;
        }

        UUID targetUuid = resolvePlayerUuid(targetName);

        if (targetUuid == null) {
            String onlineNames = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(", "));
            if (!onlineNames.isEmpty()) {
                sender.sendMessage(plugin.getMessages().get("general.unknown-player-online",
                    "{player}", targetName,
                    "{online}", onlineNames));
            } else {
                sender.sendMessage(plugin.getMessages().get("general.unknown-player", "{player}", targetName));
            }
            return true;
        }

        if (!dataStore.getBannedPlayers(ctx.mainClaimId).contains(targetUuid)) {
            sender.sendMessage(plugin.getMessages().get("claim.unban-not-banned",
                "{player}", targetName,
                "{id}", ctx.mainClaimId));
            return true;
        }

        dataStore.removeBannedPlayer(ctx.mainClaimId, targetUuid);
        dataStore.save();

        sender.sendMessage(plugin.getMessages().get("claim.unban-success",
            "{player}", targetName,
            "{id}", ctx.mainClaimId));
        return true;
    }
    
    private boolean handleBanList(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        if (!sender.hasPermission("griefprevention.claim.ban")) {
            sender.sendMessage(plugin.getMessages().get("claim.ban-no-permission"));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.ban.other");
        boolean allowAnywhere = sender.hasPermission("griefprevention.claim.ban.anywhere");

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;

        if (allowOther && args.length >= 1) {
            String possibleId = args[args.length - 1];
            Optional<Object> looked = gp.findClaimById(possibleId);
            if (!looked.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", possibleId));
                return true;
            }
            explicitClaim = looked;
            explicitId = possibleId;
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, allowAnywhere, "view the ban list for this claim");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        dev.towki.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();
        dev.towki.gpexpansion.storage.ClaimDataStore.BanData entry = dataStore.getBans(ctx.mainClaimId);

        sender.sendMessage(plugin.getMessages().get("claim.banlist-header", "{id}", ctx.mainClaimId));

        if (entry.publicBanned) {
            sender.sendMessage(plugin.getMessages().get("claim.banlist-public"));
        }

        if (entry.bannedPlayers.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("claim.banlist-empty"));
            return true;
        }

        for (UUID uuid : entry.bannedPlayers) {
            String name = entry.playerNames.getOrDefault(uuid, Bukkit.getOfflinePlayer(uuid).getName());
            if (name == null) name = uuid.toString();
            sender.sendMessage(plugin.getMessages().get("claim.banlist-entry", "{player}", name));
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
                                                       String explicitId, boolean allowOther, boolean requireOwnership, boolean allowAnywhere,
                                                       String actionDescription) {
        Object claim = explicitClaim.orElse(null);
        String claimId = explicitId;

        if (claim == null) {
            Optional<Object> claimOpt = gp.getClaimAt(player.getLocation(), player);
            if (!claimOpt.isPresent()) {
                // If player has anywhere permission and provided an explicit ID, they can rename without standing in the claim
                if (allowAnywhere && explicitId != null) {
                    Optional<Object> claimById = gp.findClaimById(explicitId);
                    if (claimById.isPresent()) {
                        claim = claimById.get();
                        claimId = explicitId;
                    } else {
                        sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", explicitId));
                        return Optional.empty();
                    }
                } else {
                    sender.sendMessage(plugin.getMessages().get("claim.not-standing-in-claim"));
                    if (explicitId == null) {
                        sender.sendMessage(plugin.getMessages().get("claim.provide-id"));
                    }
                    return Optional.empty();
                }
            } else {
                claim = claimOpt.get();
            }
        }

        if (claimId == null) {
            claimId = gp.getClaimId(claim).orElse(null);
        }
        if (claimId == null) {
            sender.sendMessage(plugin.getMessages().get("claim.id-missing"));
            return Optional.empty();
        }

        Object mainClaim = gp.getParentClaim(claim).orElse(claim);
        Optional<String> mainIdOpt = gp.getClaimId(mainClaim);
        if (!mainIdOpt.isPresent()) {
            sender.sendMessage(plugin.getMessages().get("claim.main-id-missing"));
            return Optional.empty();
        }
        String mainClaimId = mainIdOpt.get();

        boolean isOwner = gp.isOwner(mainClaim, player.getUniqueId());
        if (requireOwnership && !isOwner && !allowOther) {
            sender.sendMessage(plugin.getMessages().get("claim.must-own-action", "{action}", actionDescription));
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
    
    // Helper methods for claim formatting (use claim.line-format / subline-format from lang.yml)
    private String formatClaimLine(Object claim, String id, String name) {
        String displayName = name.isEmpty() ? "unnamed" : name;
        String worldName = gp.getClaimWorld(claim).orElse("unknown");
        int centerX = gp.getClaimCorners(claim).map(c -> (c.x1 + c.x2) / 2).orElse(0);
        int centerZ = gp.getClaimCorners(claim).map(c -> (c.z1 + c.z2) / 2).orElse(0);
        int area = gp.getClaimArea(claim);
        String key = name.isEmpty() ? "claim.line-format-unnamed" : "claim.line-format";
        return plugin.getMessages().getRaw(key,
            "{id}", id,
            "{name}", displayName,
            "{world}", worldName,
            "{x}", String.valueOf(centerX),
            "{z}", String.valueOf(centerZ),
            "{area}", String.valueOf(area));
    }
    
    private String formatSubclaimLine(Object subclaim, String parentId, String name) {
        String id = gp.getClaimId(subclaim).orElse("");
        String displayName = name.isEmpty() ? "unnamed" : name;
        String worldName = gp.getClaimWorld(subclaim).orElse("unknown");
        int centerX = gp.getClaimCorners(subclaim).map(c -> (c.x1 + c.x2) / 2).orElse(0);
        int centerZ = gp.getClaimCorners(subclaim).map(c -> (c.z1 + c.z2) / 2).orElse(0);
        String key = name.isEmpty() ? "claim.subline-format-unnamed" : "claim.subline-format";
        return plugin.getMessages().getRaw(key,
            "{id}", id,
            "{name}", displayName,
            "{world}", worldName,
            "{x}", String.valueOf(centerX),
            "{z}", String.valueOf(centerZ),
            "{parent}", parentId);
    }
    
    private String formatTrustedClaimLine(Object claim, String id, String name, String parentId, Player player) {
        return formatClaimLine(claim, id, name);
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
            sender.sendMessage(plugin.getMessages().get("claim.evict-no-permission"));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.evict.other");

        if (args.length == 0) {
            String noticeDisplay = plugin.getEvictionNoticePeriodDisplay();
            sender.sendMessage(plugin.getMessages().get("claim.evict-usage"));
            sender.sendMessage(plugin.getMessages().get("claim.evict-help", "{duration}", noticeDisplay));
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

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, false, "evict players from");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();

        // Check if the claim is currently rented
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        ClaimDataStore.RentalData rental = dataStore.getRental(ctx.mainClaimId).orElse(null);
        if (rental == null) {
            sender.sendMessage(plugin.getMessages().get("claim.not-rented"));
            return true;
        }
        
        ClaimDataStore.EvictionData existing = dataStore.getEviction(ctx.mainClaimId).orElse(null);
        long now = System.currentTimeMillis();
        if (existing != null) {
            if (now >= existing.effectiveAt) {
                sender.sendMessage(plugin.getMessages().get("eviction.notice-passed"));
                sender.sendMessage(plugin.getMessages().get("eviction.cancel-hint", "{id}", ctx.mainClaimId));
            } else {
                String remaining = formatDuration(existing.effectiveAt - now);
                sender.sendMessage(plugin.getMessages().get("eviction.notice-in-progress", "{time}", remaining));
            }
            return true;
        }

        long noticeMs = plugin.getEvictionNoticePeriodMs();
        long initiatedAt = now;
        long effectiveAt = initiatedAt + noticeMs;
        String noticeDisplay = plugin.getEvictionNoticePeriodDisplay();

        UUID ownerId = gp.getClaimOwner(ctx.mainClaim);
        if (ownerId == null) {
            sender.sendMessage(plugin.getMessages().get("claim.owner-unknown"));
            return true;
        }
        
        // Start the eviction notice
        dataStore.setEviction(ctx.mainClaimId, ownerId, rental.renter, initiatedAt, effectiveAt);
        rental.paymentFailed = true; // reuse as "being evicted"
        dataStore.save();

        String renterName = Bukkit.getOfflinePlayer(rental.renter).getName();
        if (renterName == null) renterName = rental.renter.toString();

        sender.sendMessage(plugin.getMessages().get("eviction.notice-started", "{renter}", renterName));
        sender.sendMessage(plugin.getMessages().get("eviction.notice-duration", "{duration}", noticeDisplay));
        sender.sendMessage(plugin.getMessages().get("eviction.notice-no-extend"));

        // Notify the renter if they're online
        Player renter = Bukkit.getPlayer(rental.renter);
        if (renter != null) {
            renter.sendMessage(plugin.getMessages().get("eviction.notice-received", "{id}", ctx.mainClaimId));
            renter.sendMessage(plugin.getMessages().get("eviction.notice-days", "{duration}", noticeDisplay));
            renter.sendMessage(plugin.getMessages().get("eviction.notice-no-extend"));
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

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, false, "cancel eviction for");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        ClaimDataStore.EvictionData eviction = dataStore.getEviction(ctx.mainClaimId).orElse(null);
        if (eviction == null) {
            sender.sendMessage(plugin.getMessages().get("eviction.no-pending"));
            return true;
        }

        // Cancel the eviction
        dataStore.clearEviction(ctx.mainClaimId);

        // Update rental store
        ClaimDataStore.RentalData rental = dataStore.getRental(ctx.mainClaimId).orElse(null);
        if (rental != null) {
            rental.paymentFailed = false; // reuse as "being evicted"
            dataStore.save();

            // Notify the renter if online
            Player renter = Bukkit.getPlayer(rental.renter);
            if (renter != null) {
                renter.sendMessage(plugin.getMessages().get("eviction.cancelled-notify", "{id}", ctx.mainClaimId));
            }
        }

        sender.sendMessage(plugin.getMessages().get("eviction.cancelled", "{id}", ctx.mainClaimId));
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

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, false, false, "check eviction status for");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        ClaimDataStore.EvictionData eviction = dataStore.getEviction(ctx.mainClaimId).orElse(null);
        if (eviction == null) {
            sender.sendMessage(plugin.getMessages().get("eviction.no-pending-info"));
            return true;
        }

        String renterName = Bukkit.getOfflinePlayer(eviction.renterId).getName();
        if (renterName == null) renterName = eviction.renterId.toString();

        if (System.currentTimeMillis() >= eviction.effectiveAt) {
            sender.sendMessage(plugin.getMessages().get("eviction.effective", "{renter}", renterName));
            sender.sendMessage(plugin.getMessages().get("eviction.effective-hint"));
        } else {
            String remaining = formatDuration(eviction.effectiveAt - System.currentTimeMillis());
            sender.sendMessage(plugin.getMessages().get("eviction.pending", "{renter}", renterName));
            sender.sendMessage(plugin.getMessages().get("eviction.time-remaining", "{time}", remaining));
        }

        return true;
    }

    private boolean handleRentalSignConfirm(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;

        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().get("claim.rental-sign-confirm-usage"));
            return true;
        }

        String worldName = args[0];
        int x, y, z;

        try {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().get("claim.coords-must-be-int"));
            return true;
        }

        org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(plugin.getMessages().get("claim.world-unknown", "{world}", worldName));
            return true;
        }

        org.bukkit.block.Block b = world.getBlockAt(x, y, z);
        if (!(b.getState() instanceof org.bukkit.block.Sign)) {
            sender.sendMessage(plugin.getMessages().get("claim.sign-not-found"));
            return true;
        }

        org.bukkit.block.Sign sign = (org.bukkit.block.Sign) b.getState();
        org.bukkit.NamespacedKey keyKind = new org.bukkit.NamespacedKey(plugin, "sign.kind");
        org.bukkit.NamespacedKey keyClaim = new org.bukkit.NamespacedKey(plugin, "sign.claimId");
        org.bukkit.NamespacedKey keyRenter = new org.bukkit.NamespacedKey(plugin, "rent.renter");

        if (!sign.getPersistentDataContainer().has(keyKind, org.bukkit.persistence.PersistentDataType.STRING)) {
            sender.sendMessage(plugin.getMessages().get("claim.sign-not-managed"));
            return true;
        }

        String signKind = sign.getPersistentDataContainer().get(keyKind, org.bukkit.persistence.PersistentDataType.STRING);
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
            sender.sendMessage(plugin.getMessages().get("claim.sign-use-denied"));
            return true;
        }

        if ("RENT".equals(signKind)) {
            plugin.resetRentalSign(b);
        } else {
            // Non-rent signs (e.g. mailbox): clear data and remove block
            if (claimId != null) {
                ClaimDataStore dataStore = plugin.getClaimDataStore();
                dataStore.clearRental(claimId);
                dataStore.save();
            }
            if (claimId != null && renterStr != null) {
                try {
                    java.util.Optional<Object> claimOpt = gp.findClaimById(claimId);
                    if (claimOpt.isPresent()) {
                        java.util.UUID renter = java.util.UUID.fromString(renterStr);
                        String renterName = org.bukkit.Bukkit.getOfflinePlayer(renter).getName();
                        if (renterName != null) {
                            gp.untrust(renterName, claimOpt.get());
                            gp.saveClaim(claimOpt.get()); // Persist so untrust survives server restarts
                        }
                    }
                } catch (Exception ignored) {}
            }
            b.setType(org.bukkit.Material.AIR);
        }
        sender.sendMessage(plugin.getMessages().get("eviction.rental-sign-removed"));
        return true;
    }

    private boolean handleCollectRent(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;

        ClaimDataStore dataStore = plugin.getClaimDataStore();

        // Check if player has any pending rents to collect
        boolean hasPending = false;
        double totalMoney = 0;
        int totalExp = 0;
        int totalClaimBlocks = 0;

        for (ClaimDataStore.PendingRentData entry : dataStore.getAllPendingRents().values()) {
            if (entry.ownerId.equals(player.getUniqueId())) {
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
            sender.sendMessage(plugin.getMessages().get("claim.pending-rent-none"));
            return true;
        }

        // Give the player their pending payments
        boolean success = true;

        if (totalMoney > 0 && plugin.isEconomyAvailable()) {
            if (!plugin.depositMoney(player, totalMoney)) {
                success = false;
                sender.sendMessage(plugin.getMessages().get("claim.pending-rent-failed-money",
                    "{amount}", String.valueOf(totalMoney)));
            }
        }

        if (totalExp > 0) {
            player.giveExp(totalExp);
        }

        if (totalClaimBlocks > 0) {
            // Note: Claim blocks would need GP integration here
            sender.sendMessage(plugin.getMessages().get("claim.pending-rent-claimblocks",
                "{amount}", String.valueOf(totalClaimBlocks)));
        }

        if (success) {
            boolean cleared = false;
            for (Map.Entry<String, ClaimDataStore.PendingRentData> entry : new ArrayList<>(dataStore.getAllPendingRents().entrySet())) {
                if (entry.getValue().ownerId.equals(player.getUniqueId())) {
                    dataStore.clearPendingRent(entry.getKey());
                    cleared = true;
                }
            }
            if (cleared) {
                dataStore.save();
            }
            sender.sendMessage(plugin.getMessages().get("claim.pending-rent-collected"));
        }

        return true;
    }

    private boolean handleSnapshot(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        if (!player.hasPermission(dev.towki.gpexpansion.storage.ClaimSnapshotStore.getPermission())) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }

        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "create";
        String[] rest = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];

        // Handle "snapshot remove" before requiring claim context - only needs snapshotId, searches all claims
        if ("remove".equals(action)) {
            if (rest.length < 1 || rest[0] == null || rest[0].trim().isEmpty()) {
                sender.sendMessage(plugin.getMessages().get("snapshot.remove-usage"));
                return true;
            }
            String removeSnapshotId = rest[0].trim();
            boolean ok = plugin.getSnapshotStore().removeSnapshotById(removeSnapshotId);
            if (ok) {
                sender.sendMessage(plugin.getMessages().get("snapshot.removed", "{id}", removeSnapshotId));
            } else {
                sender.sendMessage(plugin.getMessages().get("snapshot.remove-failed"));
            }
            return true;
        }

        // Handle "snapshot list all" before requiring claim context
        if ("list".equals(action) && rest.length >= 1 && "all".equalsIgnoreCase(rest[0])) {
            java.util.List<String> claimIds = plugin.getSnapshotStore().listClaimIdsWithSnapshots();
            if (claimIds.isEmpty()) {
                sender.sendMessage(plugin.getMessages().get("snapshot.list-empty"));
                return true;
            }
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
            for (String cid : claimIds) {
                java.util.List<dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry> list = plugin.getSnapshotStore().listSnapshots(cid);
                if (list.isEmpty()) continue;
                sender.sendMessage(org.bukkit.ChatColor.GRAY + "--- Claim " + cid + " ---");
                sender.sendMessage(plugin.getMessages().get("snapshot.list-header", "{count}", String.valueOf(list.size())));
                for (dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry e : list) {
                    sender.sendMessage(plugin.getMessages().get("snapshot.list-entry", "{id}", e.id, "{date}", sdf.format(new java.util.Date(e.created))));
                }
            }
            return true;
        }

        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;
        if (rest.length >= 1 && !"remove".equals(action) && !"all".equalsIgnoreCase(rest[0])) {
            Optional<Object> looked = gp.findClaimById(rest[0]);
            if (looked.isPresent()) {
                explicitClaim = looked;
                explicitId = rest[0];
            }
        }

        // For "list" with no claim ID: if not standing in a claim, treat as "list all" (no "provide id" message)
        if ("list".equals(action) && rest.length == 0 && !explicitClaim.isPresent()) {
            Optional<Object> atLoc = gp.getClaimAt(player.getLocation(), player);
            if (!atLoc.isPresent()) {
                java.util.List<String> claimIds = plugin.getSnapshotStore().listClaimIdsWithSnapshots();
                if (claimIds.isEmpty()) {
                    sender.sendMessage(plugin.getMessages().get("snapshot.list-empty"));
                    return true;
                }
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
                for (String cid : claimIds) {
                    java.util.List<dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry> list = plugin.getSnapshotStore().listSnapshots(cid);
                    if (list.isEmpty()) continue;
                    sender.sendMessage(org.bukkit.ChatColor.GRAY + "--- Claim " + cid + " ---");
                    sender.sendMessage(plugin.getMessages().get("snapshot.list-header", "{count}", String.valueOf(list.size())));
                    for (dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry e : list) {
                        sender.sendMessage(plugin.getMessages().get("snapshot.list-entry", "{id}", e.id, "{date}", sdf.format(new java.util.Date(e.created))));
                    }
                }
                return true;
            }
            explicitClaim = atLoc;
            explicitId = gp.getClaimId(atLoc.get()).orElse(null);
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, false, true, false, "manage snapshots for");
        if (!ctxOpt.isPresent()) return true;
        ClaimContext ctx = ctxOpt.get();
        String claimId = ctx.mainClaimId;
        Object claim = ctx.mainClaim;

        if ("list".equals(action)) {
            List<dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry> list = plugin.getSnapshotStore().listSnapshots(claimId);
            if (list.isEmpty()) {
                sender.sendMessage(plugin.getMessages().get("snapshot.list-empty"));
                return true;
            }
            sender.sendMessage(plugin.getMessages().get("snapshot.list-header", "{count}", String.valueOf(list.size())));
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
            for (dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry e : list) {
                sender.sendMessage(plugin.getMessages().get("snapshot.list-entry",
                    "{id}", e.id, "{date}", sdf.format(new java.util.Date(e.created))));
            }
            return true;
        }

        if ("create".equals(action) || args.length == 0) {
            ClaimDataStore dataStore = plugin.getClaimDataStore();
            ClaimDataStore.RentalData rental = dataStore.getRental(claimId).orElse(null);
            boolean availableForRent = rental == null || rental.expiry <= System.currentTimeMillis();
            if (!availableForRent) {
                sender.sendMessage(plugin.getMessages().get("snapshot.not-available-for-rent"));
                return true;
            }
            Optional<String> worldOpt = gp.getClaimWorld(claim);
            if (!worldOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.world-unknown", "{world}", "?"));
                return true;
            }
            org.bukkit.World world = Bukkit.getWorld(worldOpt.get());
            if (world == null) {
                sender.sendMessage(plugin.getMessages().get("claim.world-unknown", "{world}", worldOpt.get()));
                return true;
            }
            Optional<GPBridge.ClaimCorners> cornersOpt = gp.getClaimCorners(claim);
            if (!cornersOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("snapshot.create-failed"));
                return true;
            }
            GPBridge.ClaimCorners corners = cornersOpt.get();
            int minX = Math.min(corners.x1, corners.x2);
            int maxX = Math.max(corners.x1, corners.x2);
            int minY = Math.min(corners.y1, corners.y2);
            int maxY = Math.max(corners.y1, corners.y2);
            int minZ = Math.min(corners.z1, corners.z2);
            int maxZ = Math.max(corners.z1, corners.z2);
            if (!gp.is3DClaim(claim)) {
                minY = world.getMinHeight();
                maxY = world.getMaxHeight() - 1;
            }
            Location loc = world.getBlockAt(corners.x1, corners.y1, corners.z1).getLocation();
            final String finalClaimId = claimId;
            final Object finalClaim = claim;
            plugin.runAtLocation(loc, () -> {
                dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry entry =
                    plugin.getSnapshotStore().createSnapshot(finalClaimId, finalClaim, world);
                plugin.runAtEntity(player, () -> {
                    if (entry == null) {
                        sender.sendMessage(plugin.getMessages().get("snapshot.create-failed"));
                    } else {
                        sender.sendMessage(plugin.getMessages().get("snapshot.created", "{id}", entry.id));
                    }
                });
            });
            return true;
        }

        sender.sendMessage(plugin.getMessages().get("snapshot.usage"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        // Tab completion already runs on the correct thread (entity's region thread on Folia)
        // Do NOT use blocking operations like CompletableFuture.join() here
        return completeTab(sender, command, alias, args);
    }
    
    // /claim tp|teleport <claimId> [player]
    private boolean handleTeleport(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(plugin.getMessages().get("claim.teleport-usage"));
            return true;
        }
        
        String claimId = args[0];
        Player targetPlayer;
        
        // Check if teleporting another player
        if (args.length >= 2) {
            // Need .other permission
            if (!sender.hasPermission("griefprevention.claim.teleport.other")) {
                sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                return true;
            }
            targetPlayer = Bukkit.getPlayer(args[1]);
            if (targetPlayer == null) {
                sender.sendMessage(plugin.getMessages().get("claim.player-not-found", "{player}", args[1]));
                return true;
            }
        } else {
            // Teleporting self
            if (!requirePlayer(sender)) return true;
            if (!sender.hasPermission("griefprevention.claim.teleport")) {
                sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                return true;
            }
            targetPlayer = (Player) sender;
        }
        
        // Find the claim
        Optional<Object> claimOpt = gp.findClaimById(claimId);
        if (!claimOpt.isPresent()) {
            sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", claimId));
            return true;
        }
        
        Object claim = claimOpt.get();
        
        // Get teleport location - prefer custom spawn, fallback to claim center
        Optional<Location> spawnOpt = plugin.getClaimDataStore().getSpawn(claimId);
        
        final Player finalTarget = targetPlayer;
        final String finalClaimId = claimId;
        final CommandSender finalSender = sender;
        
        if (spawnOpt.isPresent()) {
            // Custom spawn location - can teleport directly
            Location teleportLoc = spawnOpt.get();
            plugin.teleportEntity(finalTarget, teleportLoc);
            sendTeleportMessages(finalSender, finalTarget, finalClaimId);
        } else {
            // Use claim center as fallback - need to get Y on correct region thread
            Optional<Location> centerXZOpt = gp.getClaimCenterXZ(claim);
            if (!centerXZOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.teleport-no-location", "{id}", claimId));
                return true;
            }
            Location centerXZ = centerXZOpt.get();
            
            // Schedule on target region to get highest Y and teleport
            dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocation(plugin, centerXZ, () -> {
                int y = centerXZ.getWorld().getHighestBlockYAt(centerXZ.getBlockX(), centerXZ.getBlockZ()) + 1;
                Location teleportLoc = new Location(centerXZ.getWorld(), centerXZ.getX(), y, centerXZ.getZ());
                plugin.teleportEntity(finalTarget, teleportLoc);
                sendTeleportMessages(finalSender, finalTarget, finalClaimId);
            });
        }
        
        return true;
    }
    
    private void sendTeleportMessages(CommandSender sender, Player targetPlayer, String claimId) {
        // Get claim name with color support (falls back to claimId if no custom name)
        String claimName = plugin.getClaimDataStore().getCustomName(claimId).orElse(claimId);
        
        if (sender == targetPlayer) {
            sender.sendMessage(plugin.getMessages().get("claim.teleport-success", "{id}", claimId, "{name}", claimName));
        } else {
            sender.sendMessage(plugin.getMessages().get("claim.teleport-other-success", "{player}", targetPlayer.getName(), "{id}", claimId, "{name}", claimName));
            targetPlayer.sendMessage(plugin.getMessages().get("claim.teleport-by-other", "{id}", claimId, "{name}", claimName));
        }
    }
    
    // /claim setspawn [claimId]
    private boolean handleSetSpawn(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        
        Location loc = player.getLocation();
        Object claim;
        String claimId;
        
        // If claimId is provided, use it; otherwise get from player's location
        if (args.length >= 1 && !args[0].isEmpty()) {
            claimId = args[0];
            Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", claimId));
                return true;
            }
            claim = claimOpt.get();
        } else {
            // Get the claim at player's location
            Optional<Object> claimOpt = gp.getClaimAt(loc);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.setspawn-not-in-claim"));
                return true;
            }
            claim = claimOpt.get();
            Optional<String> claimIdOpt = gp.getClaimId(claim);
            if (!claimIdOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.setspawn-error"));
                return true;
            }
            claimId = claimIdOpt.get();
        }
        
        // Check ownership (unless has .other permission)
        boolean hasOtherPerm = player.hasPermission("griefprevention.claim.setspawn.other");
        if (!hasOtherPerm) {
            if (!player.hasPermission("griefprevention.claim.setspawn")) {
                sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                return true;
            }
            
            try {
                Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
                if (ownerId == null || !ownerId.equals(player.getUniqueId())) {
                    sender.sendMessage(plugin.getMessages().get("claim.setspawn-not-owner"));
                    return true;
                }
            } catch (Exception e) {
                sender.sendMessage(plugin.getMessages().get("claim.setspawn-error"));
                return true;
            }
        }
        
        // Save the spawn point
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        dataStore.setSpawn(claimId, loc);
        dataStore.save();
        
        sender.sendMessage(plugin.getMessages().get("claim.setspawn-success", "{id}", claimId));
        return true;
    }
    
    // /claim globallist or /globalclaimlist
    private boolean handleGlobalList(CommandSender sender) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        
        if (!player.hasPermission("griefprevention.claim.gui.globallist")) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }
        
        if (plugin.getGUIManager() == null || !plugin.getGUIManager().isGUIEnabled()) {
            sender.sendMessage(plugin.getMessages().get("gui.not-enabled"));
            return true;
        }
        
        plugin.getGUIManager().openGlobalClaimList(player);
        return true;
    }
    
    /**
     * Handle /globalclaim [true|false] [claimId] - standalone command with toggle support when no args
     */
    private boolean handleGlobalClaim(CommandSender sender, String[] args) {
        if (args.length == 0) {
            // Toggle: get current claim, flip state
            if (!requirePlayer(sender)) return true;
            Player player = (Player) sender;
            java.util.Optional<Object> claimOpt = gp.getClaimAt(player.getLocation());
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.global-not-in-claim"));
                return true;
            }
            Object claim = claimOpt.get();
            String claimId = gp.getClaimId(claim).orElse(null);
            if (claimId == null) {
                sender.sendMessage(plugin.getMessages().get("general.error"));
                return true;
            }
            boolean current = plugin.getClaimDataStore().isPublicListed(claimId);
            return handleToggleGlobal(sender, new String[]{String.valueOf(!current)});
        }
        if (args.length == 1 && isNumeric(args[0])) {
            // Toggle by ID: /globalclaim 123 - requires anywhere perm
            if (!sender.hasPermission("griefprevention.claim.toggleglobal.anywhere")) {
                sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                return true;
            }
            String claimId = args[0];
            java.util.Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", claimId));
                return true;
            }
            boolean current = plugin.getClaimDataStore().isPublicListed(claimId);
            return handleToggleGlobal(sender, new String[]{String.valueOf(!current), claimId});
        }
        // Pass through: /globalclaim true|false [id]
        return handleToggleGlobal(sender, args);
    }
    
    // /claim global true|false [claimId]
    private boolean handleToggleGlobal(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(plugin.getMessages().get("claim.global-usage"));
            return true;
        }
        
        String valueStr = args[0].toLowerCase();
        boolean value;
        if (valueStr.equals("true") || valueStr.equals("on") || valueStr.equals("yes")) {
            value = true;
        } else if (valueStr.equals("false") || valueStr.equals("off") || valueStr.equals("no")) {
            value = false;
        } else {
            sender.sendMessage(plugin.getMessages().get("claim.global-usage"));
            return true;
        }
        
        Object claim;
        String claimId;
        
        if (args.length >= 2) {
            // Claim ID provided - require anywhere perm
            if (!sender.hasPermission("griefprevention.claim.toggleglobal.anywhere")) {
                sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                return true;
            }
            claimId = args[1];
            java.util.Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", claimId));
                return true;
            }
            claim = claimOpt.get();
        } else {
            // Use claim at player's location
            if (!requirePlayer(sender)) return true;
            Player player = (Player) sender;
            java.util.Optional<Object> claimOpt = gp.getClaimAt(player.getLocation());
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.global-not-in-claim"));
                return true;
            }
            claim = claimOpt.get();
            java.util.Optional<String> idOpt = gp.getClaimId(claim);
            if (!idOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("general.error"));
                return true;
            }
            claimId = idOpt.get();
        }
        
        // Check ownership/permission
        boolean hasOtherPerm = sender.hasPermission("griefprevention.claim.toggleglobal.other");
        if (!hasOtherPerm) {
            if (!sender.hasPermission("griefprevention.claim.toggleglobal")) {
                sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                return true;
            }
            
            // Check ownership
            if (sender instanceof Player) {
                try {
                    Object ownerId = claim.getClass().getMethod("getOwnerID").invoke(claim);
                    if (ownerId == null || !ownerId.equals(((Player) sender).getUniqueId())) {
                        sender.sendMessage(plugin.getMessages().get("claim.global-not-owner"));
                        return true;
                    }
                } catch (Exception e) {
                    sender.sendMessage(plugin.getMessages().get("general.error"));
                    return true;
                }
            }
        }
        
        // Check global claim limit if trying to make a claim global
        if (value && sender instanceof Player) {
            Player player = (Player) sender;
            dev.towki.gpexpansion.permission.SignLimitManager limitManager = plugin.getSignLimitManager();
            if (!limitManager.canMakeClaimGlobal(player)) {
                int limit = limitManager.getGlobalClaimLimit(player);
                int current = limitManager.getCurrentGlobalClaims(player);
                sender.sendMessage(plugin.getMessages().get("claim.global-limit-reached", 
                    "{current}", String.valueOf(current), 
                    "{max}", String.valueOf(limit)));
                return true;
            }
        }
        
        // Toggle the global listing
        dev.towki.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();
        dataStore.setPublicListed(claimId, value);
        dataStore.save();
        
        if (value) {
            sender.sendMessage(plugin.getMessages().get("gui.claim-listed", "{id}", claimId));
        } else {
            sender.sendMessage(plugin.getMessages().get("gui.claim-unlisted", "{id}", claimId));
        }
        return true;
    }
    
    /**
     * Try to delegate unknown subcommands to GP3D's claim command via namespaced dispatch
     * Returns true if delegation was attempted
     */
    private boolean tryDelegateToGP3D(CommandSender sender, Command command, String label, String[] args) {
        if (!gp.isGP3D()) {
            return false;
        }
        try {
            String argsStr = args.length > 0 ? " " + String.join(" ", args) : "";
            // Try griefprevention:claim first, fallback to griefprevention3d:claim
            String fullCmd = "griefprevention:claim" + argsStr;
            org.bukkit.Bukkit.dispatchCommand(sender, fullCmd);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get list of player-commands from config that the sender has permission for
     */
    private List<String> getAvailablePlayerCommands(CommandSender sender) {
        List<String> playerCommands = plugin.getConfig().getStringList("player-commands");
        List<String> available = new ArrayList<>();
        
        for (String perm : playerCommands) {
            String fullPerm = "griefprevention." + perm;
            if (sender.hasPermission(fullPerm)) {
                // Map permission to command name
                String commandName = mapPermissionToCommand(perm);
                if (commandName != null && !available.contains(commandName)) {
                    available.add(commandName);
                }
            }
        }
        
        return available;
    }
    
    /**
     * Map permission name to command subcommand name
     * Returns the subcommand name if it's a /claim subcommand, null otherwise
     */
    private String mapPermissionToCommand(String permission) {
        // Map permissions to their command names
        // Some permissions don't directly map to commands (like color/format permissions)
        // Only return commands that are actual subcommands
        
        if (permission.equals("claims")) return null; // Base permission, not a subcommand
        
        // Direct mappings for claim.* permissions
        if (permission.startsWith("claim.")) {
            String sub = permission.substring("claim.".length());
            
            // Direct subcommand mappings
            if (sub.equals("name")) return "name";
            if (sub.equals("description")) return "desc";
            if (sub.equals("icon")) return "icon";
            if (sub.equals("ban")) return "ban";
            if (sub.equals("unban")) return "unban";
            if (sub.equals("banlist")) return "banlist";
            if (sub.equals("teleport")) return "tp";
            if (sub.equals("setspawn")) return "setspawn";
            if (sub.equals("toggleglobal")) return "global";
            
            // GUI commands
            if (sub.equals("gui.globallist")) return "globallist";
            if (sub.equals("gui.setclaimflag.own")) return null; // GUI command, not tab-completed
            
            // Color/format permissions don't map to commands (used for name/desc formatting)
            if (sub.startsWith("color.") || sub.startsWith("format.")) return null;
            
            // .anywhere and .other are permission modifiers, not commands
            if (sub.endsWith(".anywhere") || sub.endsWith(".other")) return null;
        }
        
        // Sign permissions don't map to /claim subcommands (they're separate commands)
        if (permission.startsWith("sign.")) {
            return null;
        }
        
        // claiminfo is a separate command, not a /claim subcommand
        if (permission.equals("claiminfo")) {
            return null;
        }
        
        return null;
    }
    
    private List<String> completeTab(CommandSender sender, Command command, String alias, String[] args) {
        // Standalone /claimlist and /claimslist: no args (they show your claims list); return empty so no misleading /claim subcommands
        if (command.getName().equalsIgnoreCase("claimslist") || alias.equalsIgnoreCase("claimslist") || alias.equalsIgnoreCase("claimlist")) {
            return Collections.emptyList();
        }
        // Standalone /adminclaimlist and /adminclaimslist: no args; return empty
        if (command.getName().equalsIgnoreCase("adminclaimlist") || alias.equalsIgnoreCase("adminclaimlist") || alias.equalsIgnoreCase("adminclaimslist")) {
            return Collections.emptyList();
        }
        // Tab completion for /claimtp <claimId> [player] - must come before /claim logic
        if (command.getName().equalsIgnoreCase("claimtp") || alias.equalsIgnoreCase("claimtp")) {
            if (!sender.hasPermission("griefprevention.claim.teleport")) return Collections.emptyList();
            if (args.length <= 1) {
                String prefix = args.length == 1 ? args[0] : "";
                if (!(sender instanceof Player)) return Collections.emptyList();
                List<String> ids = new ArrayList<>();
                for (Object claim : gp.getClaimsFor((Player) sender)) {
                    gp.getClaimId(claim).ifPresent(id -> ids.add(id));
                }
                return ids.stream()
                    .filter(id -> id.toLowerCase().startsWith(prefix.toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
            }
            if (args.length == 2 && sender.hasPermission("griefprevention.claim.teleport.other")) {
                return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
        // Tab completion for /globalclaim [true|false] [claimId]
        if (command.getName().equalsIgnoreCase("globalclaim") || alias.equalsIgnoreCase("globalclaim")) {
            if (!sender.hasPermission("griefprevention.claim.toggleglobal")) return Collections.emptyList();
            if (args.length == 1) {
                List<String> opts = new ArrayList<>(Arrays.asList("true", "false"));
                if (sender.hasPermission("griefprevention.claim.toggleglobal.anywhere") && sender instanceof Player) {
                    opts.add("[claimId]");
                }
                return opts.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("false"))) {
                if (sender.hasPermission("griefprevention.claim.toggleglobal.anywhere")) {
                    return Collections.singletonList("[claimId]");
                }
            }
            return Collections.emptyList();
        }
        if (args.length == 1) {
            // If they typed a full subcommand that has sub-options, return those (some servers pass only one arg)
            String first = (args[0] != null ? args[0].trim() : "").toLowerCase(Locale.ROOT);
            if ("snapshot".equals(first) && sender.hasPermission(dev.towki.gpexpansion.storage.ClaimSnapshotStore.getPermission())) {
                return Arrays.asList("list", "remove", "create").stream().sorted().collect(Collectors.toList());
            }
            if ("evict".equals(first) && sender.hasPermission("griefprevention.evict")) {
                return Arrays.asList("cancel", "status", "[claimId]", "[player]").stream().sorted().collect(Collectors.toList());
            }
            // Get available player commands from config (filtered by permissions)
            List<String> availableCommands = getAvailablePlayerCommands(sender);
            
            // Also include GP commands from SUBS that the player has permission for
            // These are commands we handle but may not be in player-commands config
            List<String> allCommands = new ArrayList<>(availableCommands);
            
            // Add commands from SUBS that require specific permissions
            if (sender.hasPermission("griefprevention.claims")) {
                // Base commands available to all with claims permission
                if (!allCommands.contains("list")) allCommands.add("list");
                if (!allCommands.contains("create")) allCommands.add("create");
                if (!allCommands.contains("!")) allCommands.add("!");
            }
            
            if (sender.hasPermission("griefprevention.adminclaimslist")) {
                if (!allCommands.contains("adminlist")) allCommands.add("adminlist");
            }
            
            // GP commands that we dispatch (check base GP permissions)
            if (sender.hasPermission("griefprevention.claims")) {
                // Trust commands
                if (!allCommands.contains("trust")) allCommands.add("trust");
                if (!allCommands.contains("untrust")) allCommands.add("untrust");
                if (!allCommands.contains("trustlist")) allCommands.add("trustlist");
                if (!allCommands.contains("accesstrust")) allCommands.add("accesstrust");
                if (!allCommands.contains("containertrust")) allCommands.add("containertrust");
                if (!allCommands.contains("permissiontrust")) allCommands.add("permissiontrust");
                
                // Claim management
                if (!allCommands.contains("abandon")) allCommands.add("abandon");
                if (!allCommands.contains("abandonall")) allCommands.add("abandonall");
                if (!allCommands.contains("transfer")) allCommands.add("transfer");
                
                // Subdivision commands
                if (!allCommands.contains("subdivideclaim")) allCommands.add("subdivideclaim");
                if (!allCommands.contains("3dsubdivideclaim")) allCommands.add("3dsubdivideclaim");
                if (!allCommands.contains("restrictsubclaim")) allCommands.add("restrictsubclaim");
                if (!allCommands.contains("basic")) allCommands.add("basic");
                if (!allCommands.contains("explosions")) allCommands.add("explosions");
            }
            
            // Rental/eviction commands
            if (sender.hasPermission("griefprevention.evict")) {
                if (!allCommands.contains("evict")) allCommands.add("evict");
            }
            if (sender.hasPermission(dev.towki.gpexpansion.storage.ClaimSnapshotStore.getPermission())) {
                if (!allCommands.contains("snapshot")) allCommands.add("snapshot");
            }
            
            // Filter by input and return
            return allCommands.stream()
                .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                .sorted()
                .collect(Collectors.toList());
        }
        if (args.length > 1) {
            String sub = (args[0] != null ? args[0].trim() : "").toLowerCase(Locale.ROOT);
            switch (sub) {
                case "name":
                    return Collections.singletonList("<name...>");
                case "create":
                    return Collections.singletonList("<radius>");
                case "adminclaimslist":
                case "adminlist":
                case "globallist":
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
                    if (args.length == 2) {
                        String prefix = args[1].toLowerCase(Locale.ROOT);
                        return Arrays.asList("cancel", "status", "[claimId]", "[player]").stream()
                            .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .collect(Collectors.toList());
                    }
                    return new ArrayList<>();
                case "tp":
                case "teleport":
                    if (args.length == 2) return Collections.singletonList("<claimId>");
                    if (args.length == 3) return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                    return new ArrayList<>();
                case "setspawn":
                    return new ArrayList<>();
                case "global":
                    if (args.length == 2) return Arrays.asList("true", "false");
                    if (args.length == 3) return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
                case "trustlist":
                case "restrictsubclaim":
                case "explosions":
                    if (args.length == 2) return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
                case "abandon":
                    if (args.length == 2) return Arrays.asList("all", "[claimId]");
                    return new ArrayList<>();
                case "icon":
                    if (args.length == 2) return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
                case "desc":
                case "description":
                    if (args.length == 2) return Collections.singletonList("<description...>");
                    return new ArrayList<>();
                case "snapshot":
                    if (!sender.hasPermission(dev.towki.gpexpansion.storage.ClaimSnapshotStore.getPermission()))
                        return new ArrayList<>();
                    {
                        String firstSub = args.length >= 2 ? (args[1] != null ? args[1].trim() : "").toLowerCase(Locale.ROOT) : "";
                        boolean hasRemove = "remove".equals(firstSub);
                        boolean hasList = "list".equals(firstSub);
                        boolean hasCreate = "create".equals(firstSub);
                        // Completing first sub-arg after "snapshot": only when args[1] is not yet a full list/remove/create
                        boolean completingFirstSubArg = args.length == 2 && !hasRemove && !hasList && !hasCreate;
                        if (completingFirstSubArg) {
                            String prefix = (args[1] != null ? args[1].trim() : "").toLowerCase(Locale.ROOT);
                            return Arrays.asList("list", "remove", "create").stream()
                                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                                .sorted()
                                .collect(Collectors.toList());
                        }
                        if (args.length >= 2 && hasList) {
                            String p = args.length >= 3 ? (args[2] != null ? args[2].trim() : "").toLowerCase(Locale.ROOT) : "";
                            return Arrays.asList("all", "[claimId]").stream()
                                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p))
                                .sorted()
                                .collect(Collectors.toList());
                        }
                        if (args.length == 2 && hasCreate)
                            return Collections.singletonList("[claimId]");
                        // After "snapshot remove" or "snapshot remove <partial>": complete with all existing snapshot IDs
                        if (args.length >= 2 && hasRemove) {
                            String prefix = args.length >= 3 ? (args[2] != null ? args[2].trim() : "").toLowerCase(Locale.ROOT) : "";
                            List<String> allIds = new ArrayList<>();
                            for (String cid : plugin.getSnapshotStore().listClaimIdsWithSnapshots()) {
                                for (dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry e : plugin.getSnapshotStore().listSnapshots(cid)) {
                                    if (e.id != null && !e.id.isEmpty() && e.id.toLowerCase(Locale.ROOT).startsWith(prefix))
                                        allIds.add(e.id);
                                }
                            }
                            allIds = allIds.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).limit(100).collect(Collectors.toList());
                            if (!allIds.isEmpty()) return allIds;
                            return Collections.singletonList("<snapshotId>");
                        }
                    }
                    return new ArrayList<>();
                default:
                    return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }
}
