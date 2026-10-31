package codes.castled.gpexpansion.listener;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.command.ClaimCommand;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.storage.ClaimDataStore;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class CommandInterceptListener implements Listener {

    private final GPExpansionPlugin plugin;
    private final GPBridge gp = new GPBridge();

    public CommandInterceptListener(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreprocess(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (msg == null) return;
        if (!msg.startsWith("/")) return;
        String noSlash = msg.substring(1);
        // Ignore if explicitly namespaced already
        if (noSlash.startsWith("gpexpansion:claim")) return;

        String lower = noSlash.toLowerCase();
        if (lower.equals("claim") || lower.startsWith("claim ")) {
            if (plugin.isGp3dClaimMode()) {
                // GP3D present: only intercept our subcommands, let GP3D handle the rest (e.g. /claim help)
                String sub = noSlash.length() > 5 ? noSlash.substring(5).trim().split("\\s+")[0].toLowerCase() : "";
                boolean weHandle = sub.isEmpty() || ClaimCommand.HANDLED_SUBCOMMANDS.contains(sub);
                if (weHandle) {
                    ClaimCommand claimCmd = plugin.getClaimCommand();
                    if (claimCmd != null) {
                        String[] args = noSlash.length() > 5 ? noSlash.substring(5).trim().split("\\s+", -1) : new String[0];
                        event.setCancelled(true);
                        claimCmd.run(event.getPlayer(), "claim", "claim", args);
                    }
                }
            } else {
                // GPExpansion owns /claim: reroute to our namespaced handler
                String rest = noSlash.length() > 5 ? noSlash.substring(5).trim() : "";
                String reroute = "gpexpansion:claim" + (rest.isEmpty() ? "" : " " + rest);
                event.setCancelled(true);
                Bukkit.dispatchCommand(event.getPlayer(), reroute);
            }
            return;
        }

        if (isStandaloneTrustCommand(lower)) {
            if ((lower.equals("untrust") || lower.startsWith("untrust ")) && interceptUntrustCommand(event)) {
                return;
            }
            ClaimCommand claimCmd = plugin.getClaimCommand();
            if (claimCmd != null) {
                event.setCancelled(true);
                String[] args = noSlash.trim().split("\\s+", -1);
                String[] claimArgs = new String[args.length];
                claimArgs[0] = args[0].toLowerCase(Locale.ROOT);
                if (args.length > 1) {
                    System.arraycopy(args, 1, claimArgs, 1, args.length - 1);
                }
                claimCmd.run(event.getPlayer(), args[0].toLowerCase(Locale.ROOT), args[0].toLowerCase(Locale.ROOT), claimArgs);
            }
            return;
        }

        // Support /abandonclaim <id> and /abandontoplevelclaim <id> by routing to our
        // anywhere-aware handler (mirrors the trust standalone routing). Commands without
        // a numeric ID are left to native GriefPrevention so the rental protection in
        // ClaimAbandonListener continues to apply to bare abandons.
        if (isStandaloneAbandonCommand(lower)) {
            ClaimCommand claimCmd = plugin.getClaimCommand();
            String[] abandonArgs = noSlash.trim().split("\\s+", -1);
            if (claimCmd != null && abandonArgs.length >= 2 && abandonArgs[abandonArgs.length - 1].matches("\\d+")) {
                event.setCancelled(true);
                String[] claimArgs = new String[abandonArgs.length];
                claimArgs[0] = abandonArgs[0].toLowerCase(Locale.ROOT);
                System.arraycopy(abandonArgs, 1, claimArgs, 1, abandonArgs.length - 1);
                claimCmd.run(event.getPlayer(), abandonArgs[0].toLowerCase(Locale.ROOT), abandonArgs[0].toLowerCase(Locale.ROOT), claimArgs);
            }
            return;
        }

        if (lower.equals("expandclaim") || lower.equals("extendclaim")) {
            ClaimCommand claimCmd = plugin.getClaimCommand();
            if (claimCmd != null) {
                event.setCancelled(true);
                claimCmd.run(event.getPlayer(), "resizeclaim", "resizeclaim", new String[0]);
                return;
            }
        }

        // Support overriding /claimlist and /claimslist by rerouting to our /claim list
        if (lower.equals("claimlist") || lower.startsWith("claimlist ") || lower.equals("claimslist") || lower.startsWith("claimslist ")) {
            int prefixLen = lower.startsWith("claimslist") ? 10 : 9;
            ClaimCommand claimCmd = plugin.getClaimCommand();
            if (claimCmd != null) {
                event.setCancelled(true);
                String rest = noSlash.length() > prefixLen ? noSlash.substring(prefixLen).trim() : "";
                String[] args = rest.isEmpty() ? new String[]{"list"} : ("list " + rest).split("\\s+", -1);
                claimCmd.run(event.getPlayer(), "claimlist", "claimlist", args);
            } else if (!plugin.isGp3dClaimMode()) {
                // GPExpansion owns /claim: reroute to namespaced handler
                event.setCancelled(true);
                String rest = noSlash.length() > prefixLen ? noSlash.substring(prefixLen).trim() : "";
                Bukkit.dispatchCommand(event.getPlayer(), "gpexpansion:claim list" + (rest.isEmpty() ? "" : " " + rest));
            }
            return;
        }

        // Support overriding /adminclaimlist and /adminclaimslist with our enhanced display (ID + name)
        if (lower.equals("adminclaimlist") || lower.startsWith("adminclaimlist ") || lower.equals("adminclaimslist") || lower.startsWith("adminclaimslist ")) {
            int prefixLen = lower.startsWith("adminclaimslist") ? 15 : 14;
            ClaimCommand claimCmd = plugin.getClaimCommand();
            if (claimCmd != null) {
                event.setCancelled(true);
                String rest = noSlash.length() > prefixLen ? noSlash.substring(prefixLen).trim() : "";
                String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+", -1);
                claimCmd.run(event.getPlayer(), "adminclaimlist", "adminclaimlist", args);
            }
            return;
        }

        // Intercept untrust commands to prevent untrusting renters
        if (lower.equals("untrust") || lower.startsWith("untrust ")) {
            if (interceptUntrustCommand(event)) {
                return;
            }
        }
    }

    private boolean isStandaloneTrustCommand(String lower) {
        return lower.equals("trust")
            || lower.startsWith("trust ")
            || lower.equals("untrust")
            || lower.startsWith("untrust ")
            || lower.equals("accesstrust")
            || lower.startsWith("accesstrust ")
            || lower.equals("containertrust")
            || lower.startsWith("containertrust ")
            || lower.equals("permissiontrust")
            || lower.startsWith("permissiontrust ");
    }

    private boolean isStandaloneAbandonCommand(String lower) {
        return lower.equals("abandonclaim")
            || lower.startsWith("abandonclaim ")
            || lower.equals("abandontoplevelclaim")
            || lower.startsWith("abandontoplevelclaim ");
    }

    /**
     * When GP3D owns /claim, inject sub-argument completions for GPExpansion-owned commands.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onTabComplete(TabCompleteEvent event) {
        if (!plugin.isGp3dClaimMode()) return;
        String buffer = event.getBuffer();
        if (buffer == null) return;
        String commandLine = buffer.stripLeading();
        if (!commandLine.startsWith("/claim") && !commandLine.startsWith("claim")) return;
        // Parse args: first token is /claim or claim, rest are args
        String afterCommand = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        int claimLen = "claim".length();
        if (afterCommand.length() > claimLen && !Character.isWhitespace(afterCommand.charAt(claimLen))) return;
        String argsStr = afterCommand.length() > claimLen ? afterCommand.substring(claimLen).stripLeading() : "";
        boolean hasTrailingSpace = !argsStr.isEmpty() && Character.isWhitespace(argsStr.charAt(argsStr.length() - 1));
        String[] args = argsStr.isBlank() ? new String[0] : argsStr.trim().split("\\s+");
        if (hasTrailingSpace) {
            args = Arrays.copyOf(args, args.length + 1);
            args[args.length - 1] = "";
        }
        if (args.length == 0) return;
        String first = (args[0] != null ? args[0].trim() : "").toLowerCase(Locale.ROOT);
        if (!"snapshot".equals(first) && !"evict".equals(first)
                && !"info".equals(first) && !"movesign".equals(first)
                && !"market".equals(first) && !"shop".equals(first)
                && !"listed".equals(first) && !"removelisting".equals(first)
                && !"bar".equals(first)) return;
        ClaimCommand claimCmd = plugin.getClaimCommand();
        if (claimCmd == null) return;
        List<String> ours = claimCmd.completeTab(event.getSender(), "claim", "claim", args);
        if (ours != null && !ours.isEmpty()) {
            List<String> current = event.getCompletions();
            for (String s : ours) {
                if (!current.contains(s)) current.add(s);
            }
        }
    }

    private boolean interceptUntrustCommand(PlayerCommandPreprocessEvent event) {
        if (plugin.getMarketManager() == null) return false;
        Player player = event.getPlayer();
        String command = event.getMessage();
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 2) return false;

        String targetName = parts[1];
        String explicitClaimId = (parts.length >= 3 && parts[parts.length - 1].matches("\\d+"))
            ? parts[parts.length - 1]
            : null;
        if (plugin.getMarketManager().checkAndBlockUntrust(player, targetName, explicitClaimId)) {
            event.setCancelled(true);
            return true;
        }
        return false;
    }
}
