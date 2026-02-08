package dev.towki.gpexpansion.gp;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
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

    public static void register(GPExpansionPlugin plugin) {
        if (available) return;
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
                        return null;
                    });

            registerMethod.invoke(null, addonInstance);
            available = true;
            plugin.getLogger().info("Registered GP3D ClaimCommandAddon for enhanced tab completion");
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
    }

    private static List<String> getTabCompletions(CommandSender sender, String rootCommand, String subcommand, String[] args) {
        if (!"claim".equalsIgnoreCase(rootCommand)) return Collections.emptyList();
        if (!(sender instanceof Player)) return Collections.emptyList();

        String sub = subcommand.toLowerCase(Locale.ROOT);
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
                if (args != null && args.length == 2) out.add("[claimId]");
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
        return out;
    }
}
