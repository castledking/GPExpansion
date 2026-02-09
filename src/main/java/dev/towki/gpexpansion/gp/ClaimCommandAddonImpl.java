package dev.towki.gpexpansion.gp;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * GP3D ClaimCommandAddon implementation. Registers with GP3D's addon registry when present,
 * adding our tab completions (e.g. [claimId] for abandon, list, transfer) to /claim subcommands.
 */
public class ClaimCommandAddonImpl {

    private static Object addonInstance;
    private static boolean available;
    private static GPExpansionPlugin pluginRef;

    public static void register(GPExpansionPlugin plugin) {
        if (available) return;
        pluginRef = plugin;
        try {
            Class<?> addonClass = Class.forName("com.griefprevention.api.ClaimCommandAddon");
            Class<?> registryClass = Class.forName("com.griefprevention.api.ClaimCommandAddonRegistry");
            Method registerMethod = registryClass.getMethod("register", addonClass);

            addonInstance = java.lang.reflect.Proxy.newProxyInstance(
                    addonClass.getClassLoader(),
                    new Class<?>[]{addonClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("getTabCompletions".equals(name)) {
                            return getTabCompletions((CommandSender) args[0], (String) args[1], (String) args[2], (String[]) args[3]);
                        }
                        if ("getSubcommandCompletions".equals(name)) {
                            return getSubcommandCompletions((CommandSender) args[0], (String) args[1]);
                        }
                        // GP3D may call a method to execute a subcommand (e.g. executeSubcommand(sender, rootCommand, subcommand, args))
                        if (args != null && args.length >= 4 && (name.toLowerCase(Locale.ROOT).contains("execute") || name.toLowerCase(Locale.ROOT).contains("handle"))) {
                            Object result = tryExecuteSnapshotSubcommand((CommandSender) args[0], (String) args[1], (String) args[2], (String[]) args[3]);
                            if (result != null) return result;
                        }
                        // GP3D may ask whether we handle a subcommand (e.g. handlesSubcommand(rootCommand, subcommand))
                        if (args != null && args.length >= 2 && name.toLowerCase(Locale.ROOT).contains("handles")) {
                            String root = args[0] instanceof String ? (String) args[0] : null;
                            String sub = args[1] instanceof String ? (String) args[1] : null;
                            if ("claim".equalsIgnoreCase(root) && "snapshot".equalsIgnoreCase(sub))
                                return Boolean.TRUE;
                        }
                        return null;
                    });

            registerMethod.invoke(null, addonInstance);
            available = true;
            plugin.getLogger().info("Registered GP3D ClaimCommandAddon for enhanced tab completion and snapshot execution");
        } catch (Throwable t) {
            // GP3D addon API not present (vanilla GP or older GP3D)
        }
    }

    public static void unregister() {
        if (!available) return;
        try {
            Class<?> registryClass = Class.forName("com.griefprevention.api.ClaimCommandAddonRegistry");
            Method unregister = registryClass.getMethod("unregister", Class.forName("com.griefprevention.api.ClaimCommandAddon"));
            unregister.invoke(null, addonInstance);
        } catch (Throwable ignored) {}
        available = false;
        addonInstance = null;
        pluginRef = null;
    }

    /**
     * If GP3D delegated execution for "snapshot" subcommand, run our handler.
     * Expects args = (CommandSender, rootCommand, subcommand, subcommandArgs).
     * Returns Boolean true if we ran snapshot handling; null if we didn't handle it.
     */
    private static Object tryExecuteSnapshotSubcommand(CommandSender sender, String rootCommand, String subcommand, String[] subcommandArgs) {
        if (pluginRef == null || !"claim".equalsIgnoreCase(rootCommand) || !"snapshot".equalsIgnoreCase(subcommand))
            return null;
        dev.towki.gpexpansion.command.ClaimCommand cmd = pluginRef.getClaimCommand();
        if (cmd == null) return null;
        String[] fullArgs = new String[1 + (subcommandArgs != null ? subcommandArgs.length : 0)];
        fullArgs[0] = "snapshot";
        if (subcommandArgs != null && subcommandArgs.length > 0)
            System.arraycopy(subcommandArgs, 0, fullArgs, 1, subcommandArgs.length);
        org.bukkit.command.Command stub = new org.bukkit.command.Command("claim") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender s, String label, String[] a) { return false; }
        };
        cmd.onCommand(sender, stub, "claim", fullArgs);
        return Boolean.TRUE;
    }

    /**
     * Tab completions for positions after the subcommand. GP3D must call this with
     * {@code args} = arguments after the subcommand only (e.g. for "/claim snapshot list "
     * use subcommand="snapshot", args=["list", ""]) so that sub-args for subcommands are addon-able.
     * If GP3D sometimes passes the subcommand as args[0], we normalize so logic uses only args after the subcommand.
     */
    private static List<String> getTabCompletions(CommandSender sender, String rootCommand, String subcommand, String[] args) {
        if (!"claim".equalsIgnoreCase(rootCommand)) return Collections.emptyList();
        if (!(sender instanceof Player)) return Collections.emptyList();

        String sub = subcommand.toLowerCase(Locale.ROOT);
        // Normalize: if args starts with the subcommand (some GP3D pass full tail), use only what's after it
        if (args != null && args.length >= 1 && sub.equalsIgnoreCase(args[0])) {
            args = args.length == 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        }
        List<String> out = new ArrayList<>();

        // Add [claimId] for subcommands that support claim ID as argument
        switch (sub) {
            case "abandon":
                if (args != null && args.length == 1) out.add("[claimId]");
                break;
            case "trust":
            case "untrust":
            case "containertrust":
            case "accesstrust":
            case "permissiontrust":
                if (args != null && args.length == 2) out.add("[claimId]");
                break;
            case "evict":
                // First argument after "evict": cancel | status | [claimId] | [player] (args = after subcommand only)
                if (args != null && args.length <= 1) {
                    String prefix = (args.length == 1) ? args[0].toLowerCase(Locale.ROOT) : "";
                    for (String s : Arrays.asList("cancel", "status", "[claimId]", "[player]")) {
                        if (s.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(s);
                    }
                }
                break;
            case "tp":
            case "teleport":
                if (args != null && args.length == 1) {
                    // Could add claim IDs from player's claims
                    out.add("<claimId>");
                }
                break;
            case "global":
                if (args != null && args.length == 2) out.add("[claimId]");
                break;
            case "trustlist":
            case "restrictsubclaim":
            case "explosions":
            case "icon":
                if (args != null && args.length == 1) out.add("[claimId]");
                break;
            case "name":
            case "desc":
            case "description":
                if (args != null && args.length == 1) out.add("<value>");
                if (args != null && args.length == 2) out.add("[claimId]");
                break;
            case "ban":
            case "unban":
                if (args != null && args.length == 2) out.add("<player>");
                if (args != null && args.length == 3) out.add("[claimId]");
                break;
            case "transfer":
                if (args != null && args.length == 1) out.add("<player>");
                if (args != null && args.length == 2) out.add("[claimId]");
                break;
            case "snapshot":
                if (!sender.hasPermission(dev.towki.gpexpansion.storage.ClaimSnapshotStore.getPermission()))
                    break;
                // args = arguments after "snapshot". Don't show list/remove/create again when user already typed one.
                String firstSubArg = (args != null && args.length >= 1 && args[0] != null) ? args[0].trim().toLowerCase(Locale.ROOT) : "";
                boolean alreadyHasAction = "list".equals(firstSubArg) || "remove".equals(firstSubArg) || "create".equals(firstSubArg);
                boolean completingFirstSubArg = args == null || args.length == 0 || (args.length >= 1 && !alreadyHasAction);
                if (completingFirstSubArg) {
                    String prefix = (args != null && args.length >= 1 && args[0] != null) ? args[0].toLowerCase(Locale.ROOT) : "";
                    for (String action : Arrays.asList("list", "remove", "create")) {
                        if (action.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(action);
                    }
                }
                if (args != null && args.length >= 1 && "list".equals(firstSubArg)) {
                    String prefix2 = (args.length >= 2 && args[1] != null ? args[1] : "").toLowerCase(Locale.ROOT);
                    if ("all".startsWith(prefix2)) out.add("all");
                    if ("[claimid]".startsWith(prefix2)) out.add("[claimId]");
                }
                if (args != null && args.length >= 1 && "remove".equals(firstSubArg)) {
                    String prefix2 = (args.length >= 2 && args[1] != null ? args[1] : "").toLowerCase(Locale.ROOT);
                    if (pluginRef != null) {
                        java.util.List<String> allIds = new java.util.ArrayList<>();
                        for (String cid : pluginRef.getSnapshotStore().listClaimIdsWithSnapshots()) {
                            for (dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry e : pluginRef.getSnapshotStore().listSnapshots(cid)) {
                                if (e.id != null && !e.id.isEmpty() && e.id.toLowerCase(Locale.ROOT).startsWith(prefix2))
                                    allIds.add(e.id);
                            }
                        }
                        for (String id : allIds.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).limit(100).collect(java.util.stream.Collectors.toList()))
                            out.add(id);
                    }
                    if (out.isEmpty()) out.add("<snapshotId>");
                }
                if (args != null && args.length >= 1 && "create".equals(firstSubArg) && args.length == 1)
                    out.add("[claimId]");
                break;
            default:
                break;
        }
        return out;
    }

    private static List<String> getSubcommandCompletions(CommandSender sender, String rootCommand) {
        if (!"claim".equalsIgnoreCase(rootCommand)) return Collections.emptyList();

        List<String> out = new ArrayList<>();
        if (sender.hasPermission("griefprevention.claims")) {
            out.add("name");
            out.add("desc");
            out.add("icon");
        }
        if (sender.hasPermission("griefprevention.claim.gui.return")) {
            out.add("!");
        }
        if (sender.hasPermission("griefprevention.adminclaimslist")) {
            out.add("adminlist");
        }
        if (sender.hasPermission("griefprevention.transferclaim")) {
            out.add("transfer");
        }
        if (sender.hasPermission("griefprevention.claim.ban")) {
            out.add("ban");
            out.add("unban");
        }
        if (sender.hasPermission("griefprevention.evict")) {
            out.add("evict");
        }
        if (sender.hasPermission("griefprevention.claim.toggleglobal")) {
            out.add("global");
        }
        if (sender.hasPermission("griefprevention.claim.teleport")) {
            out.add("tp");
            out.add("teleport");
        }
        if (sender.hasPermission(dev.towki.gpexpansion.storage.ClaimSnapshotStore.getPermission())) {
            out.add("snapshot");
        }
        return out;
    }
}
