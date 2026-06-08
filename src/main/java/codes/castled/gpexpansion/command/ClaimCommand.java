package codes.castled.gpexpansion.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.economy.TaxManager;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.scheduler.TaskHandle;
import codes.castled.gpexpansion.storage.ClaimDataStore;
import codes.castled.gpexpansion.util.ClaimCustomizationUtil;
import codes.castled.gpexpansion.util.SafeTeleportUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ClaimCommand implements CommandExecutor, TabCompleter {

    private final GPBridge gp;
    private final GPExpansionPlugin plugin;
    private final Map<UUID, Long> claimTeleportCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, PendingClaimTeleport> pendingClaimTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, PendingUnsafeClaimTeleport> pendingUnsafeClaimTeleports = new ConcurrentHashMap<>();

    private static final long UNSAFE_TELEPORT_CONFIRM_WINDOW_MS = 10_000L;

    private record PendingUnsafeClaimTeleport(String claimId, long expiresAt) {
        boolean matches(String claimId) {
            return this.claimId.equals(claimId) && System.currentTimeMillis() <= expiresAt;
        }
    }

    public static final class PendingClaimTeleport {
        private final TaskHandle task;
        private final String claimId;
        private final long initX;
        private final long initY;
        private final long initZ;
        private final org.bukkit.World world;

        private PendingClaimTeleport(TaskHandle task, String claimId, org.bukkit.Location location) {
            this.task = task;
            this.claimId = claimId;
            // Store rounded coordinates like Essentials does to avoid precision issues
            this.initX = Math.round(location.getX() * 10);
            this.initY = Math.round(location.getY() * 10);
            this.initZ = Math.round(location.getZ() * 10);
            this.world = location.getWorld();
        }

        public boolean hasMoved(org.bukkit.Location current) {
            if (current.getWorld() != world) return true;
            long curX = Math.round(current.getX() * 10);
            long curY = Math.round(current.getY() * 10);
            long curZ = Math.round(current.getZ() * 10);
            return curX != initX || curY != initY || curZ != initZ;
        }
    }
    
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

    private boolean isPositiveInteger(String str) {
        if (!isNumeric(str)) return false;
        return parsePositiveInteger(str, 0) > 0;
    }

    private int parsePositiveInteger(String str, int fallback) {
        try {
            int value = Integer.parseInt(str);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    /**
     * Get the map of pending claim teleports for movement detection.
     * @return Map of player UUIDs to their pending teleports
     */
    public Map<UUID, PendingClaimTeleport> getPendingClaimTeleports() {
        return pendingClaimTeleports;
    }

    /**
     * Cancel a pending claim teleport and notify the player.
     * @param playerId The player UUID whose teleport should be cancelled
     */
    public void cancelPendingClaimTeleportWithMessage(UUID playerId) {
        PendingClaimTeleport pending = pendingClaimTeleports.remove(playerId);
        if (pending != null) {
            pending.task.cancel();
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(plugin.getMessages().get("claim.teleport-cancelled-move"));
            }
        }
    }

    /** Subcommands we handle - used when sharing /claim with GP3D (only intercept these) */
    public static final java.util.Set<String> HANDLED_SUBCOMMANDS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(Arrays.asList(
            "!", "gui", "menu", "name", "list", "create", "adminlist", "tp", "teleport", "setspawn", "global", "globallist", "icon", "desc", "flags", "options", "resize", "map", "fly",
            // Mapped GP commands (exact set requested)
            "abandon",           // -> abandonclaim
            "abandonall",        // -> abandonallclaims
            "explosions",        // -> claimexplosions
            "trust",             // -> trust
            "untrust",           // -> untrust (supports 'all')
            "accesstrust",       // -> accesstrust
            "containertrust",    // -> containertrust
            "subdivideclaim",    // -> subdivideclaims
            "3dsubdivideclaim",  // -> 3dsubdivideclaims
            "restrictsubclaim",  // -> restrictsubclaim
            "basic",             // -> basicclaims
            "permissiontrust",   // -> permissiontrust
            "expand",            // legacy alias -> resize
            "abandonall",        // -> abandonallclaims
            "transfer",          // -> transfer (wraps GP's transferclaim and adds ID support)
            "rentalsignconfirm",
            "evict",             // -> evict player from rental
            "cancelrent",        // -> renter forfeits their rental
            "collectrent",       // -> collect pending rental payments
            "snapshot",          // -> snapshot list|remove|create [id]
            // Moderation placeholders
            "ban", "unban", "banlist"
    )));

    private static final List<String> SUBS = Arrays.asList(
            // Our features
            "!", "gui", "menu", "name", "list", "create", "adminlist", "tp", "teleport", "setspawn", "global", "globallist", "icon", "desc", "flags", "options", "resize", "map", "fly",
            // Mapped GP commands (exact set requested)
            "abandon",           // -> abandonclaim
            "abandonall",        // -> abandonallclaims
            "explosions",        // -> claimexplosions
            "trust",             // -> trust
            "untrust",           // -> untrust (supports 'all')
            "accesstrust",       // -> accesstrust
            "containertrust",    // -> containertrust
            "subdivideclaim",    // -> subdivideclaims
            "3dsubdivideclaim",  // -> 3dsubdivideclaims
            "restrictsubclaim",  // -> restrictsubclaim
            "basic",             // -> basicclaims
            "permissiontrust",   // -> permissiontrust
            "transfer",          // -> transfer
            "rentalsignconfirm",
            "evict",
            "cancelrent",
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
        // Support standalone /resizeclaim and GP-native aliases /expandclaim, /extendclaim
        if (command.getName().equalsIgnoreCase("resizeclaim")
            || label.equalsIgnoreCase("resizeclaim")
            || command.getName().equalsIgnoreCase("expandclaim")
            || label.equalsIgnoreCase("expandclaim")
            || command.getName().equalsIgnoreCase("extendclaim")
            || label.equalsIgnoreCase("extendclaim")) {
            return handleResizeCommand(sender, args);
        }
        // Support standalone /claimmap
        if (command.getName().equalsIgnoreCase("claimmap")
            || label.equalsIgnoreCase("claimmap")) {
            return handleMapCommand(sender, args);
        }
        // Support standalone /setclaimspawn command
        if (command.getName().equalsIgnoreCase("setclaimspawn") || label.equalsIgnoreCase("setclaimspawn")) {
            return handleSetSpawn(sender, args);
        }
        // Support standalone /globalclaimlist and /globalclaimslist commands
        if (command.getName().equalsIgnoreCase("globalclaimlist")
                || command.getName().equalsIgnoreCase("globalclaimslist")
                || label.equalsIgnoreCase("globalclaimlist")
                || label.equalsIgnoreCase("globalclaimslist")) {
            return handleGlobalList(sender);
        }
        // Support standalone /globalclaim [true|false] [claimId] command (toggle when no args)
        if (command.getName().equalsIgnoreCase("globalclaim") || label.equalsIgnoreCase("globalclaim")) {
            return handleGlobalClaim(sender, args);
        }
        if (command.getName().equalsIgnoreCase("approveclaim")
                || command.getName().equalsIgnoreCase("aclaim")
                || label.equalsIgnoreCase("approveclaim")
                || label.equalsIgnoreCase("aclaim")) {
            return handleApproveClaim(sender, args);
        }
        if (command.getName().equalsIgnoreCase("cancelrent") || label.equalsIgnoreCase("cancelrent")) {
            return handleCancelRent(sender, args);
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
            case "gui":
            case "menu":
                return handleOpenMainMenu(sender);
            case "trust":
                return handleTrustDispatch(sender, subArgs);
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
            case "name":
                return handleName(sender, subArgs);
            case "create":
                // Alias to GP's /createclaim [radius] to avoid recursion
                return handleDispatch(sender, "createclaim", subArgs);
            case "resize":
            case "expand":
                return handleResizeCommand(sender, subArgs);
            case "map":
                return handleMapCommand(sender, subArgs);
            case "fly":
                return new ClaimFlyCommand(plugin).onCommand(sender, command, label, subArgs);
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
            case "cancelrent":
                return handleCancelRent(sender, subArgs);
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
            case "flags":
                return handleClaimFlags(sender, subArgs);
            case "options":
                return handleClaimOptions(sender, subArgs);
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

    private boolean canListOtherClaims(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission("griefprevention.claimslistother");
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
        if (!codes.castled.gpexpansion.gui.GUIStateTracker.restoreLastGUI(plugin.getGUIManager(), player)) {
            sender.sendMessage(plugin.getMessages().get("gui.no-previous"));
            return true;
        }
        
        return true;
    }

    private boolean handleOpenMainMenu(CommandSender sender) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        if (plugin.getGUIManager() == null || !plugin.getGUIManager().isGUIEnabled()) {
            sender.sendMessage(plugin.getMessages().get("gui.not-enabled"));
            return true;
        }
        plugin.getGUIManager().openMainMenu(player);
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
                           base.equals("restrictsubclaim");
        
        // Check if we have an ID at the end
        boolean hasIdArg = false;
        if (supportsIdWithArgs && args.length >= 2) {
            hasIdArg = isNumeric(args[args.length - 1]);
        } else if (supportsIdOnly && args.length >= 1) {
            hasIdArg = isNumeric(args[args.length - 1]);
        }

        if (hasIdArg && isTrustCommand(base) && !sender.hasPermission("griefprevention.trust.anywhere")) {
            sender.sendMessage(plugin.getMessages().get("claim.trust-anywhere-required"));
            return true;
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
plugin.getSchedulerFacade().teleportEntity(player, centerOpt.get());

                        // Execute the command with the player as the sender
                        String cmd = finalBaseCmd;
                        if (passArgs.length > 0) {
                            cmd += " " + String.join(" ", passArgs);
                        }
                        
                        boolean ok = Bukkit.dispatchCommand(sender, cmd);
                        if (!ok) {
                            sender.sendMessage(plugin.getMessages().get("commands.exec-failed", "{command}", "/" + finalBaseCmd));
                        } else {
                            maybeTrackTrustedPlayerChange(player, finalBaseCmd, passArgs, possibleId);
                        }
                        
                        // Teleport back
                        plugin.getSchedulerFacade().teleportEntity(player, original);
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
            } else {
                maybeTrackTrustedPlayerChange(player, base, args, null);
            }
        } catch (Exception e) {
            sender.sendMessage(plugin.getMessages().get("commands.exec-error", "{error}", e.getMessage()));
            e.printStackTrace();
        }
        return true;
    }

    private boolean handleTrustDispatch(CommandSender sender, String[] args) {
        TrustCommandDispatch dispatch = normalizeTrustCommand(args);
        if (dispatch == null) {
            sender.sendMessage(plugin.getMessages().get("claim.trust-usage"));
            return true;
        }
        return handleDispatch(sender, dispatch.base, dispatch.args);
    }

    private boolean handleResizeCommand(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;

        if (args.length == 0) {
            if (plugin.getGUIManager() == null || !plugin.getGUIManager().isGUIEnabled()) {
                return handleDispatch(sender, "expandclaim", args);
            }

            Optional<Object> claimOpt = gp.getClaimAt(player.getLocation(), player);
            if (claimOpt.isEmpty()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-standing-in-claim"));
                return true;
            }

            Object claim = claimOpt.get();
            String claimId = gp.getClaimId(claim).orElse(null);
            if (claimId == null) {
                sender.sendMessage(plugin.getMessages().get("claim.id-missing"));
                return true;
            }

            plugin.getGUIManager().openClaimResize(player, claim, claimId);
            return true;
        }

        return handleDispatch(sender, "expandclaim", args);
    }

    private boolean handleMapCommand(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;

        if (args.length > 0) {
            sender.sendMessage(plugin.getMessages().get("commands.claim-usage",
                    "{label}", "claim",
                    "{subs}", String.join("|", SUBS)));
            return true;
        }

        if (plugin.getGUIManager() == null || !plugin.getGUIManager().isGUIEnabled()) {
            sender.sendMessage(plugin.getMessages().get("gui.not-enabled"));
            return true;
        }

        Optional<Object> claimOpt = gp.getClaimAt(player.getLocation(), player);
        if (claimOpt.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("claim.not-standing-in-claim"));
            return true;
        }

        Object claim = claimOpt.get();
        String claimId = gp.getClaimId(claim).orElse(null);
        if (claimId == null) {
            sender.sendMessage(plugin.getMessages().get("claim.id-missing"));
            return true;
        }

        plugin.getGUIManager().openClaimMapEditor(player, claim, claimId);
        return true;
    }

    private TrustCommandDispatch normalizeTrustCommand(String[] args) {
        if (args.length < 1 || args.length > 3) {
            return null;
        }

        if (args.length == 1) {
            return new TrustCommandDispatch("trust", args);
        }

        String playerArg = args[0];
        String second = args[1];

        if (args.length == 2) {
            if (isNumeric(second)) {
                return new TrustCommandDispatch("trust", args);
            }

            String mappedBase = mapTrustTypeToBase(second);
            if (mappedBase == null) {
                return null;
            }
            return new TrustCommandDispatch(mappedBase, new String[]{playerArg});
        }

        String mappedBase = mapTrustTypeToBase(second);
        if (mappedBase == null || !isNumeric(args[2])) {
            return null;
        }
        return new TrustCommandDispatch(mappedBase, new String[]{playerArg, args[2]});
    }

    private String mapTrustTypeToBase(String trustType) {
        if (trustType == null || trustType.isBlank()) {
            return null;
        }

        return switch (trustType.toLowerCase(Locale.ROOT)) {
            case "build", "builder", "builders" -> "trust";
            case "access", "accessor", "accessors" -> "accesstrust";
            case "container", "containers", "inventory", "inventories" -> "containertrust";
            case "manage", "manager", "managers", "permission", "permissions" -> "permissiontrust";
            default -> null;
        };
    }

    private boolean isTrustCommand(String base) {
        return base.equals("trust")
            || base.equals("untrust")
            || base.equals("containertrust")
            || base.equals("accesstrust")
            || base.equals("permissiontrust");
    }

    private void maybeTrackTrustedPlayerChange(Player player, String base, String[] args, String explicitClaimId) {
        if (!isTrustCommand(base) || args.length == 0) {
            return;
        }

        String targetName = args[0];
        if (targetName == null || targetName.isBlank() || targetName.equalsIgnoreCase("all") || targetName.equalsIgnoreCase("public")) {
            return;
        }

        UUID targetUuid = resolvePlayerUuid(targetName);
        if (targetUuid == null) {
            return;
        }

        String claimId = explicitClaimId;
        if (claimId == null) {
            claimId = gp.getClaimAt(player.getLocation(), player)
                .flatMap(gp::getClaimId)
                .orElse(null);
        }
        if (claimId == null) {
            return;
        }

        plugin.getClaimDataStore().addTrustedPlayer(claimId, targetUuid, targetName);
        plugin.getClaimDataStore().save();
    }

    private static final class TrustCommandDispatch {
        private final String base;
        private final String[] args;

        private TrustCommandDispatch(String base, String[] args) {
            this.base = base;
            this.args = args;
        }
    }

    private record ClaimListOptions(
            boolean showTrustedClaims,
            boolean showSubclaims,
            boolean showOwnerListToConsole,
            boolean showWorld,
            boolean showCoordinates,
            boolean showArea,
            boolean showName,
            boolean showClaimBlockSummary,
            int pageSize
    ) {
        static ClaimListOptions from(org.bukkit.configuration.file.FileConfiguration config) {
            return new ClaimListOptions(
                    config.getBoolean("claim-list.show-trusted-claims", true),
                    config.getBoolean("claim-list.show-subclaims", true),
                    config.getBoolean("claim-list.show-owner-list-to-console", true),
                    config.getBoolean("claim-list.show-world", true),
                    config.getBoolean("claim-list.show-coordinates", true),
                    config.getBoolean("claim-list.show-area", true),
                    config.getBoolean("claim-list.show-name", true),
                    config.getBoolean("claim-list.show-claim-block-summary", true),
                    Math.max(1, config.getInt("claim-list.page-size", 10))
            );
        }
    }

    // Helper methods for command handling
    private boolean handleList(CommandSender sender, String[] args) {
        ClaimListOptions listOptions = ClaimListOptions.from(plugin.getConfig());
        String[] listArgs = args;
        if (listArgs.length > 0 && listArgs[0].equalsIgnoreCase("list")) {
            listArgs = Arrays.copyOfRange(listArgs, 1, listArgs.length);
        }

        Player senderPlayer = sender instanceof Player ? (Player) sender : null;
        int requestedPage = 1;
        if (listArgs.length > 0 && isPositiveInteger(listArgs[listArgs.length - 1])) {
            requestedPage = parsePositiveInteger(listArgs[listArgs.length - 1], 1);
            listArgs = Arrays.copyOf(listArgs, listArgs.length - 1);
        }
        UUID targetId;
        String targetName;

        if (listArgs.length > 0 && !listArgs[0].isEmpty()) {
            if (senderPlayer == null && !listOptions.showOwnerListToConsole()) {
                sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                return true;
            }
            String requestedName = listArgs[0];
            Player onlineTarget = Bukkit.getPlayerExact(requestedName);
            if (onlineTarget != null) {
                targetId = onlineTarget.getUniqueId();
                targetName = onlineTarget.getName();
            } else {
                targetId = resolvePlayerUuid(requestedName);
                targetName = requestedName;
            }

            if (targetId == null) {
                sender.sendMessage(plugin.getMessages().get("claim.list-player-not-found",
                    "{player}", requestedName));
                return true;
            }

            if (senderPlayer != null
                    && !senderPlayer.getUniqueId().equals(targetId)
                    && !sender.hasPermission("griefprevention.claimslistother")) {
                sender.sendMessage(plugin.getMessages().get("permissions.missing",
                    "{permission}", "griefprevention.claimslistother"));
                return true;
            }
        } else {
            if (senderPlayer == null) {
                sender.sendMessage(plugin.getMessages().get("claim.list-usage-other"));
                return true;
            }
            targetId = senderPlayer.getUniqueId();
            targetName = senderPlayer.getName();
        }

        boolean viewingOther = senderPlayer == null || !senderPlayer.getUniqueId().equals(targetId);
        List<Object> claims = gp.getClaimsFor(targetId);
        codes.castled.gpexpansion.storage.ClaimDataStore store = plugin.getClaimDataStore();
        if (viewingOther) {
            sender.sendMessage(plugin.getMessages().get("claim.list-player-header",
                "{player}", targetName));
        }
        if (listOptions.showClaimBlockSummary()) {
            gp.getPlayerClaimStats(targetId).ifPresent(stats -> {
                sender.sendMessage(plugin.getMessages().get("claim.blocks-total",
                    "{accrued}", String.valueOf(stats.accrued),
                    "{bonus}", String.valueOf(stats.bonus),
                    "{total}", String.valueOf(stats.total)));
            });
        }

        // Group claims by their parent claim, but only include subclaims that the player owns
        LinkedHashMap<Object, List<Object>> grouped = new LinkedHashMap<>();
        for (Object c : claims) {
            Object parent = toMainClaim(c);
            if (c != parent) { // This is a subclaim
                // Only add subclaims that the player owns
                if (gp.isOwner(c, targetId)) {
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

        List<Map.Entry<Object, List<Object>>> groupedEntries = new ArrayList<>(grouped.entrySet());
        int totalPages = Math.max(1, (int) Math.ceil(groupedEntries.size() / (double) listOptions.pageSize()));
        int page = Math.min(Math.max(1, requestedPage), totalPages);
        if (totalPages > 1) {
            sender.sendMessage(plugin.getMessages().get("claim.list-page",
                "{page}", String.valueOf(page),
                "{pages}", String.valueOf(totalPages)));
        }

        int fromIndex = Math.min(groupedEntries.size(), (page - 1) * listOptions.pageSize());
        int toIndex = Math.min(groupedEntries.size(), fromIndex + listOptions.pageSize());
        for (Map.Entry<Object, List<Object>> e : groupedEntries.subList(fromIndex, toIndex)) {
            Object parent = e.getKey();
            String id = gp.getClaimId(parent).orElse("?");
            String parentName = store.getCustomName(id).orElse("unnamed");
            String name = formatClaimLine(parent, id, parentName, listOptions);
            sender.sendMessage(parseColorCodes(name));

            if (listOptions.showSubclaims()) {
                // Get subclaims for this parent that the player owns
                List<Object> subs = e.getValue();
                if (subs.isEmpty()) {
                    // If no subclaims in the grouped list, check if there are any subclaims the player owns
                    List<Object> allSubs = getSubclaims(parent);
                    for (Object sub : allSubs) {
                        if (gp.isOwner(sub, targetId)) {
                            subs.add(sub);
                        }
                    }
                }

                for (Object sub : subs) {
                    String subId = gp.getClaimId(sub).orElse("");
                    String subName = store.getCustomName(subId).orElse("");
                    String subLine = formatSubclaimLine(sub, id, subName, listOptions);
                    // Parse color codes in the line
                    sender.sendMessage(parseColorCodes("    " + subLine));
                }
            }
        }

        if (listOptions.showTrustedClaims()) {
            // Get trusted claims and filter out any that the player owns
            List<Object> trusted = getTrustedClaimsFor(targetId).stream()
                .filter(c -> !gp.isOwner(c, targetId))
                .collect(Collectors.toList());

            if (!trusted.isEmpty()) {
                sender.sendMessage(plugin.getMessages().get("claim.list-trusted-header",
                    "{count}", String.valueOf(trusted.size())));
                for (Object c : trusted) {
                    String id = gp.getClaimId(c).orElse("?");
                    String parentId = gp.getClaimId(toMainClaim(c)).orElse("?");
                    String name = store.getCustomName(id).orElse("unnamed");
                    String line = formatTrustedClaimLine(c, id, name, parentId, targetId, listOptions);
                    // Parse color codes in the line
                    sender.sendMessage(parseColorCodes(line));

                    if (listOptions.showSubclaims()) {
                        // Show subclaims of trusted claims if the player is trusted on them
                        List<Object> trustedSubs = getSubclaims(c);
                        for (Object sub : trustedSubs) {
                            if (gp.getClaimsWhereTrusted(targetId).contains(sub)) {
                                String subId = gp.getClaimId(sub).orElse("");
                                String subName = store.getCustomName(subId).orElse("");
                                String subLine = formatSubclaimLine(sub, id, subName, listOptions);
                                sender.sendMessage(parseColorCodes("  " + subLine));
                            }
                        }
                    }
                }
            }
        }

        if (listOptions.showClaimBlockSummary()) {
            gp.getPlayerClaimStats(targetId).ifPresent(stats -> {
                sender.sendMessage(plugin.getMessages().get("claim.blocks-remaining",
                    "{remaining}", String.valueOf(stats.remaining)));
            });
        }
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
        
        ClaimListOptions listOptions = ClaimListOptions.from(plugin.getConfig());
        codes.castled.gpexpansion.storage.ClaimDataStore store = plugin.getClaimDataStore();
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
            String line = formatClaimLine(c, id, name, listOptions);
            sender.sendMessage(parseColorCodes(line));
            
            if (listOptions.showSubclaims()) {
                for (Object sub : getSubclaims(c)) {
                    String subId = gp.getClaimId(sub).orElse("");
                    String subName = store.getCustomName(subId).orElse("");
                    String subLine = formatSubclaimLine(sub, id, subName, listOptions);
                    // Parse color codes in the line
                    sender.sendMessage(parseColorCodes("    " + subLine));
                }
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

        ClaimCustomizationUtil.TextResult normalized = ClaimCustomizationUtil.normalizeName(plugin, sender, legacyName);
        String stored = normalized.value();
        if (stored.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("claim.name-usage"));
            return true;
        }
        if (normalized.truncated()) {
            sender.sendMessage(plugin.getMessages().get("claim.name-truncated",
                "{max}", String.valueOf(plugin.getConfigManager().getClaimNameMaxLength())));
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(sender, player, explicitClaim, explicitId, allowOther, true, allowAnywhere, "rename this claim");
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        codes.castled.gpexpansion.storage.ClaimDataStore store = plugin.getClaimDataStore();
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
        if (!ClaimCustomizationUtil.isIconAllowed(plugin, item)) {
            sender.sendMessage(plugin.getMessages().get("claim.icon-denied", "{icon}", item.getType().name()));
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

        ClaimCustomizationUtil.TextResult normalized = ClaimCustomizationUtil.normalizeDescription(plugin, sender, description);
        if (normalized.rejected()) {
            sender.sendMessage(plugin.getMessages().get("claim.description-links-denied"));
            return true;
        }
        description = normalized.value();
        if (description.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("claim.description-usage"));
            return true;
        }
        if (normalized.truncated()) {
            sender.sendMessage(plugin.getMessages().get("claim.description-truncated",
                "{max}", String.valueOf(plugin.getConfigManager().getClaimDescriptionMaxLength())));
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

    /**
     * Handle /claim flags [claimId] - open claim flags GUI. Optional claim ID for targeting a specific claim.
     * Permissions: griefprevention.claim.gui.flags (base), .flags.anywhere (own by ID), .flags.other (other players).
     */
    private boolean handleClaimFlags(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        if (plugin.getGUIManager() == null || !plugin.getGUIManager().isGUIEnabled()) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }
        if (!sender.hasPermission("griefprevention.claim.gui.flags")) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }
        Object claim;
        String claimId;
        if (args.length >= 1 && !args[0].isEmpty()) {
            String id = args[0];
            Optional<Object> claimOpt = gp.findClaimById(id);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", id));
                return true;
            }
            claim = claimOpt.get();
            claimId = gp.getClaimId(claim).orElse(id);
            boolean isOwner = gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin");
            if (isOwner) {
                if (!sender.hasPermission("griefprevention.claim.gui.flags.anywhere")) {
                    sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                    return true;
                }
            } else {
                if (!sender.hasPermission("griefprevention.claim.gui.flags.other")) {
                    sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                    return true;
                }
            }
        } else {
            Optional<Object> claimOpt = gp.getClaimAt(player.getLocation(), player);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-standing-in-claim"));
                return true;
            }
            claim = claimOpt.get();
            claimId = gp.getClaimId(claim).orElse(null);
            if (claimId == null) {
                sender.sendMessage(plugin.getMessages().get("general.error"));
                return true;
            }
        }
        if (!codes.castled.gpexpansion.gui.ClaimFlagsGUI.canAccess(player, claim, gp)) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }
        plugin.getGUIManager().openClaimFlags(player, claim, claimId);
        return true;
    }

    /**
     * Handle /claim options [claimId] - open claim options GUI. Optional claim ID for targeting a specific claim.
     * Permissions: griefprevention.claim.gui.options (base), .options.anywhere (own by ID), .options.other (other players).
     */
    private boolean handleClaimOptions(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        if (plugin.getGUIManager() == null || !plugin.getGUIManager().isGUIEnabled()) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }
        if (!sender.hasPermission("griefprevention.claim.gui.options")) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }
        Object claim;
        String claimId;
        if (args.length >= 1 && !args[0].isEmpty()) {
            String id = args[0];
            Optional<Object> claimOpt = gp.findClaimById(id);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", id));
                return true;
            }
            claim = claimOpt.get();
            claimId = gp.getClaimId(claim).orElse(id);
            boolean isOwner = gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin");
            if (isOwner) {
                if (!sender.hasPermission("griefprevention.claim.gui.options.anywhere")) {
                    sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                    return true;
                }
            } else {
                if (!sender.hasPermission("griefprevention.claim.gui.options.other")) {
                    sender.sendMessage(plugin.getMessages().get("general.no-permission"));
                    return true;
                }
            }
        } else {
            Optional<Object> claimOpt = gp.getClaimAt(player.getLocation(), player);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-standing-in-claim"));
                return true;
            }
            claim = claimOpt.get();
            claimId = gp.getClaimId(claim).orElse(null);
            if (claimId == null) {
                sender.sendMessage(plugin.getMessages().get("general.error"));
                return true;
            }
        }
        plugin.getGUIManager().openClaimOptions(player, claim, claimId);
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
        codes.castled.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();

        if (targetName.equalsIgnoreCase("public")) {
            String publicPermission = plugin.getConfigManager().getClaimBanPublicPermission();
            if (!publicPermission.isEmpty() && !sender.hasPermission(publicPermission)) {
                sender.sendMessage(plugin.getMessages().get("claim.public-ban-no-permission",
                    "{permission}", publicPermission));
                return true;
            }
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
                eject.ifPresent(location -> plugin.getSchedulerFacade().teleportEntity(target, location));
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
        codes.castled.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();

        if (targetName.equalsIgnoreCase("public")) {
            String publicPermission = plugin.getConfigManager().getClaimBanPublicPermission();
            if (!publicPermission.isEmpty() && !sender.hasPermission(publicPermission)) {
                sender.sendMessage(plugin.getMessages().get("claim.public-ban-no-permission",
                    "{permission}", publicPermission));
                return true;
            }
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
        if (!sender.hasPermission("griefprevention.claim.banlist")) {
            sender.sendMessage(plugin.getMessages().get("claim.banlist-no-permission"));
            return true;
        }

        Player player = (Player) sender;
        boolean allowOther = sender.hasPermission("griefprevention.claim.banlist.other");
        boolean allowAnywhere = sender.hasPermission("griefprevention.claim.banlist.anywhere");

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
        codes.castled.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();
        codes.castled.gpexpansion.storage.ClaimDataStore.BanData entry = dataStore.getBans(ctx.mainClaimId);

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
    
    @SuppressWarnings("all")
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

    private boolean validateRentEvictionStanding(CommandSender sender, Player player, ClaimContext ctx) {
        if (!plugin.getConfigManager().isRentEvictionStandingRequired()) {
            return true;
        }
        Optional<Object> standingClaimOpt = gp.getClaimAt(player.getLocation(), player);
        if (!standingClaimOpt.isPresent()) {
            sender.sendMessage(plugin.getMessages().get("claim.not-standing-in-claim"));
            return false;
        }
        Object standingMainClaim = gp.getParentClaim(standingClaimOpt.get()).orElse(standingClaimOpt.get());
        String standingMainId = gp.getClaimId(standingMainClaim).orElse(null);
        if (standingMainId == null || !standingMainId.equals(ctx.mainClaimId)) {
            sender.sendMessage(plugin.getMessages().get("claim.not-standing-in-claim"));
            return false;
        }
        return true;
    }

    private void removeRenterTrust(UUID renterId, Object claim) {
        if (renterId == null || claim == null) return;
        String renterName = Bukkit.getOfflinePlayer(renterId).getName();
        if (renterName == null) renterName = renterId.toString();
        if (gp.untrust(renterName, claim)) {
            gp.saveClaim(claim);
        }
    }

    private void grantRenterTrust(UUID renterId, Object claim, Player executor) {
        if (renterId == null || claim == null || executor == null) return;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(renterId);
        String renterName = offline.getName();
        if (renterName == null) renterName = renterId.toString();

        boolean changed = gp.trust(executor, renterName, claim);
        if (plugin.getConfigManager().isRentContainerTrustGranted()) {
            changed = gp.grantInventoryTrust(executor, renterName, claim) || changed;
        }
        if (plugin.getConfigManager().isRentAccessTrustGranted()) {
            changed = gp.grantAccessTrust(executor, renterName, claim) || changed;
        }
        if (changed) {
            gp.saveClaim(claim);
        }
    }

    private UUID resolvePlayerUuid(String name) {
        if (name == null || name.isEmpty()) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (offline != null && offline.getUniqueId() != null && (offline.isOnline() || offline.hasPlayedBefore())) {
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
    
    private String formatClaimLine(Object claim, String id, String name, ClaimListOptions options) {
        StringBuilder line = new StringBuilder("&eID ").append(id);
        appendClaimListFields(line, claim, name, options, false, null);
        return line.toString();
    }
    
    private String formatSubclaimLine(Object subclaim, String parentId, String name, ClaimListOptions options) {
        String id = gp.getClaimId(subclaim).orElse("");
        StringBuilder line = new StringBuilder("&7- ID &f").append(id);
        appendClaimListFields(line, subclaim, name, options, true, parentId);
        return line.toString();
    }
    
    private String formatTrustedClaimLine(Object claim, String id, String name, String parentId, UUID playerId, ClaimListOptions options) {
        return formatClaimLine(claim, id, name, options);
    }

    private void appendClaimListFields(
            StringBuilder line,
            Object claim,
            String name,
            ClaimListOptions options,
            boolean subclaim,
            String parentId
    ) {
        if (options.showName()) {
            String displayName = name == null || name.isEmpty() ? "unnamed" : name;
            line.append(subclaim ? " &7(&f" : " &e(")
                .append(displayName)
                .append(subclaim ? "&7)" : "&e)");
        }

        List<String> locationParts = new ArrayList<>();
        if (options.showWorld()) {
            locationParts.add(gp.getClaimWorld(claim).orElse("unknown"));
        }
        if (options.showCoordinates()) {
            int centerX = gp.getClaimCorners(claim).map(c -> (c.x1 + c.x2) / 2).orElse(0);
            int centerZ = gp.getClaimCorners(claim).map(c -> (c.z1 + c.z2) / 2).orElse(0);
            locationParts.add("x" + centerX + ", z" + centerZ);
        }
        if (!locationParts.isEmpty()) {
            line.append(subclaim ? " &7" : " &e")
                .append(String.join(": ", locationParts));
        }

        if (options.showArea()) {
            line.append(subclaim ? " &8(&6" : " &e(-")
                .append(gp.getClaimArea(claim))
                .append(subclaim ? " blocks&8)" : " blocks)");
        }

        if (subclaim && parentId != null && !parentId.isEmpty()) {
            line.append(" &8(&6Child of ").append(parentId).append("&8)");
        }
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

    private List<Object> getTrustedClaimsFor(UUID playerId) {
        return gp.getClaimsWhereTrusted(playerId);
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
            String noticeDisplay = plugin.getRentalSignManager().getEvictionNoticePeriodDisplay();
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
        if (!validateRentEvictionStanding(sender, player, ctx)) return true;

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

        long noticeMs = plugin.getRentalSignManager().getEvictionNoticePeriodMs();
        long initiatedAt = now;
        long effectiveAt = initiatedAt + noticeMs;
        String noticeDisplay = plugin.getRentalSignManager().getEvictionNoticePeriodDisplay();

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

        if (plugin.getConfigManager().isRenterTrustRemovedOnEvictionStart()) {
            removeRenterTrust(rental.renter, ctx.mainClaim);
        }

        sender.sendMessage(plugin.getMessages().get("eviction.notice-started", "{renter}", renterName));
        sender.sendMessage(plugin.getMessages().get("eviction.notice-duration", "{duration}", noticeDisplay));
        sender.sendMessage(plugin.getMessages().get("eviction.notice-no-extend"));

        // Notify the renter if they're online
        Player renter = Bukkit.getPlayer(rental.renter);
        if (renter != null && plugin.getConfigManager().isRenterNotifiedOnEvictionStart()) {
            renter.sendMessage(plugin.getMessages().get("eviction.notice-received", "{id}", ctx.mainClaimId));
            renter.sendMessage(plugin.getMessages().get("eviction.notice-days", "{duration}", noticeDisplay));
            renter.sendMessage(plugin.getMessages().get("eviction.notice-no-extend"));
        }

        return true;
    }

    private boolean handleEvictCancel(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;
        boolean hasOtherPermission = sender.hasPermission("griefprevention.evict.other");
        boolean allowOther = hasOtherPermission && plugin.getConfigManager().isRentEvictionAdminCancelAllowed();

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
        if (!validateRentEvictionStanding(sender, player, ctx)) return true;
        boolean owner = gp.isOwner(ctx.mainClaim, player.getUniqueId());
        boolean canCancelAsOwner = owner && plugin.getConfigManager().isRentEvictionOwnerCancelAllowed();
        boolean canCancelAsAdmin = hasOtherPermission && plugin.getConfigManager().isRentEvictionAdminCancelAllowed();
        if (!canCancelAsOwner && !canCancelAsAdmin) {
            sender.sendMessage(plugin.getMessages().get("eviction.cancel-disabled"));
            return true;
        }

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

            if (plugin.getConfigManager().isRenterTrustRemovedOnEvictionStart()) {
                grantRenterTrust(rental.renter, ctx.mainClaim, player);
            }

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
        if (!validateRentEvictionStanding(sender, player, ctx)) return true;
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

    private boolean handleCancelRent(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return true;
        Player player = (Player) sender;

        if (!player.hasPermission("griefprevention.claim.cancelrent")) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }

        boolean allowAnywhere = player.hasPermission("griefprevention.claim.cancelrent.anywhere");
        boolean allowOther = player.hasPermission("griefprevention.claim.cancelrent.other");
        Optional<Object> explicitClaim = Optional.empty();
        String explicitId = null;

        if (args.length > 0 && args[0] != null && !args[0].isBlank()) {
            explicitId = args[0];
            if (!allowAnywhere && !allowOther) {
                sender.sendMessage(plugin.getMessages().get("permissions.missing",
                        "{permission}", "griefprevention.claim.cancelrent.anywhere"));
                return true;
            }
            explicitClaim = gp.findClaimById(explicitId);
            if (!explicitClaim.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", explicitId));
                return true;
            }
        }

        Optional<ClaimContext> ctxOpt = resolveClaimContext(
                sender,
                player,
                explicitClaim,
                explicitId,
                allowOther,
                false,
                allowAnywhere || allowOther,
                "cancel this rental"
        );
        if (!ctxOpt.isPresent()) return true;

        ClaimContext ctx = ctxOpt.get();
        ClaimDataStore dataStore = plugin.getClaimDataStore();
        ClaimDataStore.RentalData rental = dataStore.getRental(ctx.mainClaimId).orElse(null);
        if (rental == null || rental.expiry <= System.currentTimeMillis()) {
            sender.sendMessage(plugin.getMessages().get("claim.cancelrent-not-rented", "{id}", ctx.mainClaimId));
            return true;
        }

        if (!allowOther && !player.getUniqueId().equals(rental.renter)) {
            sender.sendMessage(plugin.getMessages().get("claim.cancelrent-not-renter", "{id}", ctx.mainClaimId));
            return true;
        }

        Optional<Object> claimOpt = gp.findClaimById(ctx.mainClaimId);
        if (claimOpt.isPresent()) {
            String renterName = Bukkit.getOfflinePlayer(rental.renter).getName();
            if (renterName != null) {
                gp.untrust(renterName, claimOpt.get());
                gp.saveClaim(claimOpt.get());
            }
        }

        Location signLocation = rental.signLocation;
        dataStore.clearRental(ctx.mainClaimId);
        dataStore.clearEviction(ctx.mainClaimId);
        dataStore.save();

        boolean resetSign = false;
        if (signLocation != null && signLocation.getWorld() != null) {
            org.bukkit.block.Block block = signLocation.getBlock();
            if (block.getState() instanceof org.bukkit.block.Sign) {
                plugin.getRentalSignManager().resetRentalSign(block);
                resetSign = true;
            }
        }

        sender.sendMessage(plugin.getMessages().get("claim.cancelrent-success", "{id}", ctx.mainClaimId));
        if (!resetSign) {
            sender.sendMessage(plugin.getMessages().get("claim.cancelrent-sign-not-found", "{id}", ctx.mainClaimId));
        }

        Player renter = Bukkit.getPlayer(rental.renter);
        if (renter != null && !renter.getUniqueId().equals(player.getUniqueId())) {
            renter.sendMessage(plugin.getMessages().get("claim.cancelrent-cancelled-by-admin", "{id}", ctx.mainClaimId));
        }

        return true;
    }

    @SuppressWarnings("all")
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
            ClaimDataStore.EvictionData eviction = claimId != null
                    ? plugin.getClaimDataStore().getEviction(claimId).orElse(null)
                    : null;
            codes.castled.gpexpansion.sign.RentalSignManager.ResetCause resetCause = eviction != null
                    && System.currentTimeMillis() >= eviction.effectiveAt
                    ? codes.castled.gpexpansion.sign.RentalSignManager.ResetCause.EVICT
                    : codes.castled.gpexpansion.sign.RentalSignManager.ResetCause.GENERAL;
            plugin.getRentalSignManager().resetRentalSign(b, resetCause);
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
        double totalTax = 0;
        int totalExp = 0;
        int totalClaimBlocks = 0;

        for (ClaimDataStore.PendingRentData entry : dataStore.getAllPendingRents().values()) {
            if (entry.ownerId.equals(player.getUniqueId())) {
                hasPending = true;
                try {
                    double amount = Double.parseDouble(entry.amount);
                    switch (entry.kind) {
                        case "MONEY":
                            TaxManager.Context taxContext = entry.isPurchase
                                ? TaxManager.Context.SELL
                                : TaxManager.Context.RENT;
                            TaxManager.TaxResult tax = plugin.getTaxManager().calculateTax(amount, taxContext, player);
                            totalMoney += tax.net;
                            totalTax += tax.tax;
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

        if (totalMoney > 0 && plugin.getEconomyManager().isEconomyAvailable()) {
            if (!plugin.getEconomyManager().depositMoney(player, totalMoney)) {
                success = false;
                sender.sendMessage(plugin.getMessages().get("claim.pending-rent-failed-money",
                    "{amount}", String.valueOf(totalMoney)));
            } else {
                if (totalTax > 0) {
                    plugin.getTaxManager().depositTax(totalTax);
                }
                if (totalTax > 0 && plugin.getConfigManager().shouldNotifyTaxPayee()) {
                    plugin.getMessages().send(player, "tax.payee-notify",
                        "{tax}", plugin.getEconomyManager().formatMoney(totalTax),
                        "{net}", plugin.getEconomyManager().formatMoney(totalMoney));
                }
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
        if (!player.hasPermission(codes.castled.gpexpansion.storage.ClaimSnapshotStore.getPermission())) {
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
                java.util.List<codes.castled.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry> list = plugin.getSnapshotStore().listSnapshots(cid);
                if (list.isEmpty()) continue;
                sender.sendMessage(net.kyori.adventure.text.Component.text("--- Claim " + cid + " ---", net.kyori.adventure.text.format.NamedTextColor.GRAY));
                sender.sendMessage(plugin.getMessages().get("snapshot.list-header", "{count}", String.valueOf(list.size())));
                for (codes.castled.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry e : list) {
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
                    java.util.List<codes.castled.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry> list = plugin.getSnapshotStore().listSnapshots(cid);
                    if (list.isEmpty()) continue;
                    sender.sendMessage(net.kyori.adventure.text.Component.text("--- Claim " + cid + " ---", net.kyori.adventure.text.format.NamedTextColor.GRAY));
                    sender.sendMessage(plugin.getMessages().get("snapshot.list-header", "{count}", String.valueOf(list.size())));
                    for (codes.castled.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry e : list) {
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
            List<codes.castled.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry> list = plugin.getSnapshotStore().listSnapshots(claimId);
            if (list.isEmpty()) {
                sender.sendMessage(plugin.getMessages().get("snapshot.list-empty"));
                return true;
            }
            sender.sendMessage(plugin.getMessages().get("snapshot.list-header", "{count}", String.valueOf(list.size())));
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
            for (codes.castled.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry e : list) {
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
            Location loc = world.getBlockAt(corners.x1, corners.y1, corners.z1).getLocation();
            final String finalClaimId = claimId;
            final Object finalClaim = claim;
            plugin.getSchedulerFacade().runAtLocation(loc, () -> {
                codes.castled.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry entry =
                    plugin.getSnapshotStore().createSnapshot(finalClaimId, finalClaim, world);
                plugin.getSchedulerFacade().runAtEntity(player, () -> {
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

    private long getClaimTeleportCooldownRemaining(Player player) {
        UUID uuid = player.getUniqueId();
        Long until = claimTeleportCooldowns.get(uuid);
        if (until == null) return 0L;

        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0L) {
            claimTeleportCooldowns.remove(uuid, until);
            return 0L;
        }
        return remaining;
    }

    private void applyClaimTeleportCooldown(Player player) {
        if (hasClaimTeleportCooldownBypass(player)) return;
        int cooldownSeconds = plugin.getConfigManager().getClaimTeleportCooldownSeconds();
        if (cooldownSeconds <= 0) return;
        claimTeleportCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + (cooldownSeconds * 1000L));
    }

    private boolean hasClaimTeleportCooldownBypass(Player player) {
        return hasConfiguredPermission(player, plugin.getConfigManager().getClaimTeleportCooldownBypassPermission());
    }

    private boolean hasClaimTeleportDelayBypass(Player player) {
        return hasConfiguredPermission(player, plugin.getConfigManager().getClaimTeleportDelayBypassPermission());
    }

    private boolean hasConfiguredPermission(Player player, String permission) {
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    private void cancelPendingClaimTeleport(UUID playerId) {
        PendingClaimTeleport pending = pendingClaimTeleports.remove(playerId);
        if (pending != null) {
            pending.task.cancel();
        }
    }

    private void completeClaimTeleport(CommandSender sender, Player targetPlayer, String claimId, Location destination) {
        spawnClaimTeleportParticles(targetPlayer.getLocation());
        plugin.getSchedulerFacade().teleportEntity(targetPlayer, destination);
        playConfiguredSound(targetPlayer, plugin.getConfigManager().getClaimTeleportCompleteSound());
        spawnClaimTeleportParticles(destination);
        if (sender == targetPlayer) {
            applyClaimTeleportCooldown(targetPlayer);
        }
        sendTeleportMessages(sender, targetPlayer, claimId);
    }

    private void queueClaimTeleport(CommandSender sender, Player targetPlayer, String claimId, Location destination) {
        if (sender != targetPlayer) {
            completeClaimTeleport(sender, targetPlayer, claimId, destination);
            return;
        }

        boolean bypassCooldown = hasClaimTeleportCooldownBypass(targetPlayer);
        long cooldownRemaining = bypassCooldown ? 0L : getClaimTeleportCooldownRemaining(targetPlayer);
        if (cooldownRemaining > 0L) {
            sender.sendMessage(plugin.getMessages().get("claim.teleport-cooldown",
                "{time}", formatDuration(cooldownRemaining)));
            return;
        }

        int delaySeconds = hasClaimTeleportDelayBypass(targetPlayer)
            ? 0
            : plugin.getConfigManager().getClaimTeleportDelaySeconds();
        UUID playerId = targetPlayer.getUniqueId();
        cancelPendingClaimTeleport(playerId);

        if (delaySeconds <= 0) {
            completeClaimTeleport(sender, targetPlayer, claimId, destination);
            return;
        }

        sender.sendMessage(plugin.getMessages().get("claim.teleport-delay-start",
            "{id}", claimId,
            "{time}", formatDuration(delaySeconds * 1000L)));
        playConfiguredSound(targetPlayer, plugin.getConfigManager().getClaimTeleportStartSound());
        spawnClaimTeleportParticles(targetPlayer.getLocation());

        final PendingClaimTeleport[] pendingRef = new PendingClaimTeleport[1];
        TaskHandle task = codes.castled.gpexpansion.scheduler.SchedulerAdapter.runLaterEntity(plugin, targetPlayer, () -> {
            PendingClaimTeleport pending = pendingRef[0];
            if (pending == null || pendingClaimTeleports.get(playerId) != pending) {
                return;
            }

            pendingClaimTeleports.remove(playerId, pending);
            if (!targetPlayer.isOnline() || !targetPlayer.isValid()) {
                return;
            }

            long remaining = hasClaimTeleportCooldownBypass(targetPlayer)
                ? 0L
                : getClaimTeleportCooldownRemaining(targetPlayer);
            if (remaining > 0L) {
                targetPlayer.sendMessage(plugin.getMessages().get("claim.teleport-cooldown",
                    "{time}", formatDuration(remaining)));
                return;
            }

            completeClaimTeleport(sender, targetPlayer, claimId, destination);
        }, Math.max(1L, delaySeconds * 20L));

        PendingClaimTeleport pending = new PendingClaimTeleport(task, claimId, targetPlayer.getLocation());
        pendingRef[0] = pending;
        pendingClaimTeleports.put(playerId, pending);
    }

    private void playConfiguredSound(Player player, String configuredSound) {
        Sound sound = parseSound(configuredSound);
        if (sound == null) return;
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    private Sound parseSound(String configuredSound) {
        String normalized = normalizeRegistryName(configuredSound);
        if (normalized == null) return null;
        try {
            return Sound.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void spawnClaimTeleportParticles(Location location) {
        Particle particle = parseParticle(plugin.getConfigManager().getClaimTeleportParticle());
        if (particle == null || location == null || location.getWorld() == null) return;
        location.getWorld().spawnParticle(particle, location.clone().add(0, 1.0, 0), 24, 0.35, 0.7, 0.35, 0.02);
    }

    private Particle parseParticle(String configuredParticle) {
        String normalized = normalizeRegistryName(configuredParticle);
        if (normalized == null) return null;
        try {
            return Particle.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeRegistryName(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("none") || trimmed.equalsIgnoreCase("false")) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_').replace(':', '_');
    }

    private int[] buildTeleportSearchBounds(Location origin, Object claim, int claimSearchRadius) {
        int configuredRadius = plugin.getConfigManager().getClaimTeleportSafeLocationSearchRadius();
        int radius = Math.max(claimSearchRadius, configuredRadius);

        GPBridge.ClaimCorners corners = gp.getClaimCorners(claim).orElse(null);
        if (corners != null && !plugin.getConfigManager().isClaimTeleportNearbyFallbackAllowed()) {
            return new int[] { origin.getBlockX(), origin.getBlockX(), origin.getBlockZ(), origin.getBlockZ() };
        }

        return new int[] {
            origin.getBlockX() - radius,
            origin.getBlockX() + radius,
            origin.getBlockZ() - radius,
            origin.getBlockZ() + radius
        };
    }

    private boolean hasConfirmedUnsafeTeleport(CommandSender sender, String claimId) {
        if (!(sender instanceof Player)) return true;
        UUID playerId = ((Player) sender).getUniqueId();
        PendingUnsafeClaimTeleport pending = pendingUnsafeClaimTeleports.get(playerId);
        if (pending == null || !pending.matches(claimId)) {
            pendingUnsafeClaimTeleports.remove(playerId);
            return false;
        }
        pendingUnsafeClaimTeleports.remove(playerId);
        return true;
    }

    private boolean needsUnsafeTeleportConfirmation(CommandSender sender, Player targetPlayer, String claimId, Location requested, Location resolved) {
        if (sender != targetPlayer) return false;
        if (plugin.getConfigManager().isClaimTeleportStaffIgnoreUnsafeLocation()) {
            GameMode mode = targetPlayer.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return false;
        }
        if (!plugin.getConfigManager().isClaimTeleportUnsafeConfirmationEnabled()) return false;
        if (!plugin.getConfigManager().isClaimTeleportNearbyFallbackAllowed()) return false;
        if (SafeTeleportUtil.isLocationSafeForSpawn(requested)) return false;
        if (sameBlockLocation(requested, resolved)) return false;
        return !hasConfirmedUnsafeTeleport(sender, claimId);
    }

    private boolean sameBlockLocation(Location left, Location right) {
        if (left == null || right == null || left.getWorld() == null || right.getWorld() == null) return false;
        return left.getWorld().equals(right.getWorld())
            && left.getBlockX() == right.getBlockX()
            && left.getBlockY() == right.getBlockY()
            && left.getBlockZ() == right.getBlockZ();
    }

    private boolean queueResolvedClaimTeleport(CommandSender sender, Player targetPlayer, String claimId, Object claim, Location requested, int claimSearchRadius) {
        if (plugin.getConfigManager().isClaimTeleportStaffIgnoreUnsafeLocation()) {
            GameMode mode = targetPlayer.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
                queueClaimTeleport(sender, targetPlayer, claimId, requested);
                return true;
            }
        }
        Location destination = requested;
        if (plugin.getConfigManager().isClaimTeleportSafeLocationEnabled()) {
            if (!plugin.getConfigManager().isClaimTeleportNearbyFallbackAllowed()) {
                if (!SafeTeleportUtil.isLocationSafeForSpawn(requested)) {
                    sender.sendMessage(plugin.getMessages().get("claim.teleport-unsafe", "{id}", claimId));
                    return false;
                }
                destination = new Location(requested.getWorld(), requested.getBlockX() + 0.5, requested.getBlockY(), requested.getBlockZ() + 0.5, requested.getYaw(), requested.getPitch());
            } else {
                int[] bounds = buildTeleportSearchBounds(requested, claim, claimSearchRadius);
                destination = SafeTeleportUtil.getSafeDestination(requested, bounds);
                if (destination == null) {
                    sender.sendMessage(plugin.getMessages().get("claim.teleport-unsafe", "{id}", claimId));
                    return false;
                }
                if (needsUnsafeTeleportConfirmation(sender, targetPlayer, claimId, requested, destination)) {
                    pendingUnsafeClaimTeleports.put(((Player) sender).getUniqueId(),
                        new PendingUnsafeClaimTeleport(claimId, System.currentTimeMillis() + UNSAFE_TELEPORT_CONFIRM_WINDOW_MS));
                    sender.sendMessage(plugin.getMessages().get("claim.teleport-unsafe-confirm", "{id}", claimId));
                    return false;
                }
            }
        }

        queueClaimTeleport(sender, targetPlayer, claimId, destination);
        return true;
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
        
        // Get claim bounds for safe teleport search
        int searchRadius = gp.getClaimSearchRadius(claim);
        
        // Get teleport location - prefer custom spawn, fallback to claim center
        Optional<Location> spawnOpt = plugin.getClaimDataStore().getSpawn(claimId);
        
        final Player finalTarget = targetPlayer;
        final String finalClaimId = claimId;
        final CommandSender finalSender = sender;
        
        if (spawnOpt.isPresent()) {
            Location teleportLoc = spawnOpt.get();
            queueResolvedClaimTeleport(finalSender, finalTarget, finalClaimId, claim, teleportLoc, searchRadius);
        } else {
            // Use claim center as fallback - need to get Y on correct region thread
            Optional<Location> centerXZOpt = gp.getClaimCenterXZ(claim);
            if (!centerXZOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.teleport-no-location", "{id}", claimId));
                return true;
            }
            Location centerXZ = centerXZOpt.get();
            
            final int finalSearchRadius = searchRadius;
            final Object finalClaim = claim;
            
            // Schedule on target region to get highest Y and teleport
            codes.castled.gpexpansion.scheduler.SchedulerAdapter.runAtLocation(plugin, centerXZ, () -> {
                int y = centerXZ.getWorld().getHighestBlockYAt(centerXZ.getBlockX(), centerXZ.getBlockZ()) + 1;
                Location teleportLoc = new Location(centerXZ.getWorld(), centerXZ.getX(), y, centerXZ.getZ());
                queueResolvedClaimTeleport(finalSender, finalTarget, finalClaimId, finalClaim, teleportLoc, finalSearchRadius);
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
        
        // Check if location is safe (solid block below feet)
        if (!SafeTeleportUtil.isLocationSafeForSpawn(loc)) {
            sender.sendMessage(plugin.getMessages().get("claim.setspawn-unsafe"));
            return true;
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

        if (!plugin.getConfigManager().isGlobalClaimsEnabled()) {
            sender.sendMessage(plugin.getMessages().get("claim.global-disabled"));
            return true;
        }
        
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
        if (!plugin.getConfigManager().isGlobalClaimsEnabled()) {
            sender.sendMessage(plugin.getMessages().get("claim.global-disabled"));
            return true;
        }

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
        if (!plugin.getConfigManager().isGlobalClaimsEnabled()) {
            sender.sendMessage(plugin.getMessages().get("claim.global-disabled"));
            return true;
        }

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
            codes.castled.gpexpansion.permission.SignLimitManager limitManager = plugin.getSignLimitManager();
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
        codes.castled.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();
        if (value && plugin.getConfigManager().isGlobalClaimsApprovalRequired()
                && !sender.hasPermission("griefprevention.approveclaim")) {
            dataStore.setGlobalApprovalPending(claimId, true);
            dataStore.save();
            sender.sendMessage(plugin.getMessages().get("claim.global-approval-required", "{id}", claimId));
            return true;
        }

        if (!value) {
            dataStore.setGlobalApprovalPending(claimId, false);
        }
        dataStore.setPublicListed(claimId, value);
        dataStore.save();
        
        if (value) {
            sender.sendMessage(plugin.getMessages().get("gui.claim-listed", "{id}", claimId));
        } else {
            sender.sendMessage(plugin.getMessages().get("gui.claim-unlisted", "{id}", claimId));
        }
        return true;
    }

    private boolean handleApproveClaim(CommandSender sender, String[] args) {
        if (!sender.hasPermission("griefprevention.approveclaim")) {
            sender.sendMessage(plugin.getMessages().get("general.no-permission"));
            return true;
        }
        if (!plugin.getConfigManager().isGlobalClaimsEnabled()) {
            sender.sendMessage(plugin.getMessages().get("claim.global-disabled"));
            return true;
        }
        String claimId = null;
        if (args.length > 0 && !args[0].equalsIgnoreCase("approve")) {
            claimId = args[0];
        } else if (args.length >= 2) {
            claimId = args[1];
        }

        if (claimId != null) {
            java.util.Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-found", "{id}", claimId));
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(plugin.getMessages().get("claim.approve-usage"));
                return true;
            }
            Player player = (Player) sender;
            java.util.Optional<Object> claimOpt = gp.getClaimAt(player.getLocation(), player);
            if (!claimOpt.isPresent()) {
                sender.sendMessage(plugin.getMessages().get("claim.not-standing-in-claim"));
                sender.sendMessage(plugin.getMessages().get("claim.provide-id"));
                return true;
            }
            Object claim = claimOpt.get();
            claimId = gp.getClaimId(claim).orElse(null);
            if (claimId == null) {
                sender.sendMessage(plugin.getMessages().get("general.error"));
                return true;
            }
        }

        codes.castled.gpexpansion.storage.ClaimDataStore dataStore = plugin.getClaimDataStore();
        if (dataStore.isPublicListed(claimId)) {
            sender.sendMessage(plugin.getMessages().get("claim.approve-already-listed", "{id}", claimId));
            return true;
        }
        if (!dataStore.isGlobalApprovalPending(claimId)) {
            sender.sendMessage(plugin.getMessages().get("claim.approve-not-pending", "{id}", claimId));
            return true;
        }

        dataStore.setPublicListed(claimId, true);
        dataStore.save();
        sender.sendMessage(plugin.getMessages().get("claim.approve-success", "{id}", claimId));
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
            if (sub.equals("cancelrent")) return "cancelrent";
            
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
    
    @SuppressWarnings("all")
    private List<String> completeTab(CommandSender sender, Command command, String alias, String[] args) {
        // Standalone /claimlist and /claimslist: optional player target for console or claimslistother permission
        if (command.getName().equalsIgnoreCase("claimslist") || alias.equalsIgnoreCase("claimslist") || alias.equalsIgnoreCase("claimlist")) {
            if (args.length == 1 && canListOtherClaims(sender)) {
                return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .collect(Collectors.toList());
            }
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
        // Tab completion for /resizeclaim
        if (command.getName().equalsIgnoreCase("resizeclaim")
            || alias.equalsIgnoreCase("resizeclaim")) {
            if (args.length == 1) {
                return Arrays.asList("1", "5", "10").stream()
                    .filter(s -> s.startsWith(args[0]))
                    .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
        // Tab completion for /claimmap
        if (command.getName().equalsIgnoreCase("claimmap")
            || alias.equalsIgnoreCase("claimmap")) {
            return Collections.emptyList();
        }
        // Tab completion for /globalclaim [true|false] [claimId]
        if (command.getName().equalsIgnoreCase("globalclaim") || alias.equalsIgnoreCase("globalclaim")) {
            if (!plugin.getConfigManager().isGlobalClaimsEnabled()) return Collections.emptyList();
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
        if (command.getName().equalsIgnoreCase("approveclaim")
                || command.getName().equalsIgnoreCase("aclaim")
                || alias.equalsIgnoreCase("approveclaim")
                || alias.equalsIgnoreCase("aclaim")) {
            if (!sender.hasPermission("griefprevention.approveclaim")) return Collections.emptyList();
            if (!plugin.getConfigManager().isGlobalClaimsEnabled()) return Collections.emptyList();
            if (command.getName().equalsIgnoreCase("aclaim") || alias.equalsIgnoreCase("aclaim")) {
                if (args.length == 1) {
                    List<String> options = new ArrayList<>();
                    options.add("approve");
                    options.addAll(plugin.getClaimDataStore().getPendingGlobalApprovalClaims());
                    return options.stream()
                        .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                        .sorted()
                        .collect(Collectors.toList());
                }
                if (args.length == 2 && args[0].equalsIgnoreCase("approve")) {
                    return plugin.getClaimDataStore().getPendingGlobalApprovalClaims().stream()
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .sorted()
                        .collect(Collectors.toList());
                }
                return Collections.emptyList();
            }
            if (args.length == 1) {
                return plugin.getClaimDataStore().getPendingGlobalApprovalClaims().stream()
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
        if (command.getName().equalsIgnoreCase("cancelrent") || alias.equalsIgnoreCase("cancelrent")) {
            if (!sender.hasPermission("griefprevention.claim.cancelrent")) return Collections.emptyList();
            if (args.length == 1
                    && (sender.hasPermission("griefprevention.claim.cancelrent.anywhere")
                    || sender.hasPermission("griefprevention.claim.cancelrent.other"))) {
                return Collections.singletonList("[claimId]");
            }
            return Collections.emptyList();
        }
        if (command.getName().equalsIgnoreCase("globalclaimlist")
                || command.getName().equalsIgnoreCase("globalclaimslist")
                || alias.equalsIgnoreCase("globalclaimlist")
                || alias.equalsIgnoreCase("globalclaimslist")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            // If they typed a full subcommand that has sub-options, return those (some servers pass only one arg)
            String first = (args[0] != null ? args[0].trim() : "").toLowerCase(Locale.ROOT);
            if ("snapshot".equals(first) && sender.hasPermission(codes.castled.gpexpansion.storage.ClaimSnapshotStore.getPermission())) {
                return Arrays.asList("list", "remove", "create").stream().sorted().collect(Collectors.toList());
            }
            if ("evict".equals(first) && sender.hasPermission("griefprevention.evict")) {
                return Arrays.asList("cancel", "status", "[claimId]", "[player]").stream().sorted().collect(Collectors.toList());
            }
            if ("cancelrent".equals(first) && sender.hasPermission("griefprevention.claim.cancelrent")) {
                return sender.hasPermission("griefprevention.claim.cancelrent.anywhere")
                        || sender.hasPermission("griefprevention.claim.cancelrent.other")
                        ? Collections.singletonList("[claimId]")
                        : Collections.emptyList();
            }
            // Get available player commands from config (filtered by permissions)
            List<String> availableCommands = getAvailablePlayerCommands(sender);
            
            // Also include GP commands from SUBS that the player has permission for
            // These are commands we handle but may not be in player-commands config
            List<String> allCommands = new ArrayList<>(availableCommands);
            
            // Add commands from SUBS that require specific permissions
            if (sender.hasPermission("griefprevention.claims")) {
                // Base commands available to all with claims permission
                if (!allCommands.contains("gui")) allCommands.add("gui");
                if (!allCommands.contains("menu")) allCommands.add("menu");
                if (!allCommands.contains("list")) allCommands.add("list");
                if (!allCommands.contains("create")) allCommands.add("create");
                if (!allCommands.contains("!")) allCommands.add("!");
                if (!allCommands.contains("resize")) allCommands.add("resize");
                if (!allCommands.contains("map")) allCommands.add("map");
            }
            
            if (sender.hasPermission("griefprevention.adminclaimslist")) {
                if (!allCommands.contains("adminlist")) allCommands.add("adminlist");
            }
            
            // GP commands that we dispatch (check base GP permissions)
            if (sender.hasPermission("griefprevention.claims")) {
                // Trust commands
                if (!allCommands.contains("trust")) allCommands.add("trust");
                if (!allCommands.contains("untrust")) allCommands.add("untrust");
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
            if (sender.hasPermission("griefprevention.claim.cancelrent")) {
                if (!allCommands.contains("cancelrent")) allCommands.add("cancelrent");
            }
            if (sender.hasPermission(codes.castled.gpexpansion.storage.ClaimSnapshotStore.getPermission())) {
                if (!allCommands.contains("snapshot")) allCommands.add("snapshot");
            }
            if (sender.hasPermission("griefprevention.claim.gui.flags")) {
                if (!allCommands.contains("flags")) allCommands.add("flags");
            }
            if (sender.hasPermission("griefprevention.claim.gui.options")) {
                if (!allCommands.contains("options")) allCommands.add("options");
            }
            if (!plugin.getConfigManager().isGlobalClaimsEnabled()) {
                allCommands.removeIf(commandName ->
                    commandName.equalsIgnoreCase("global") || commandName.equalsIgnoreCase("globallist"));
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
                case "resize":
                case "expand":
                    if (args.length == 2) return Collections.singletonList("<blocks>");
                    return new ArrayList<>();
                case "adminclaimslist":
                case "adminlist":
                case "globallist":
                case "gui":
                case "menu":
                    return new ArrayList<>();
                case "cancelrent":
                    if (args.length == 2
                            && (sender.hasPermission("griefprevention.claim.cancelrent.anywhere")
                            || sender.hasPermission("griefprevention.claim.cancelrent.other"))) {
                        return Collections.singletonList("[claimId]");
                    }
                    return new ArrayList<>();
                case "list":
                    if (args.length == 2 && canListOtherClaims(sender)) {
                        return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                            .sorted()
                            .collect(Collectors.toList());
                    }
                    return new ArrayList<>();
                case "ban":
                case "unban":
                    if (args.length == 2) {
                        List<String> targets = new ArrayList<>();
                        targets.add("<player>");
                        String publicPermission = plugin.getConfigManager().getClaimBanPublicPermission();
                        if (publicPermission.isEmpty() || sender.hasPermission(publicPermission)) {
                            targets.add("public");
                        }
                        return targets.stream()
                            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))
                                || value.startsWith("<"))
                            .collect(Collectors.toList());
                    }
                    if (args.length == 3) return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
                case "trust":
                    if (args.length == 2) return Collections.singletonList("<player>");
                    if (args.length == 3) {
                        return Arrays.asList("build", "access", "containers", "manage", "[claimId]");
                    }
                    if (args.length == 4) return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
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
                    if (!plugin.getConfigManager().isGlobalClaimsEnabled()) return new ArrayList<>();
                    if (args.length == 2) return Arrays.asList("true", "false");
                    if (args.length == 3) return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
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
                    if (!sender.hasPermission(codes.castled.gpexpansion.storage.ClaimSnapshotStore.getPermission()))
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
                                for (codes.castled.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry e : plugin.getSnapshotStore().listSnapshots(cid)) {
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
                case "flags":
                    if (args.length == 2 && sender.hasPermission("griefprevention.claim.gui.flags"))
                        return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
                case "options":
                    if (args.length == 2 && sender.hasPermission("griefprevention.claim.gui.options"))
                        return Collections.singletonList("[claimId]");
                    return new ArrayList<>();
                default:
                    return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }
}
