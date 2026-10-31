package codes.castled.gpexpansion.util;
import codes.castled.gpexpansion.util.Mini;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import codes.castled.gpexpansion.GPExpansionPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages language messages for GPExpansion.
 * Loads messages from lang.yml and provides methods to retrieve and format them.
 * Falls back to hardcoded defaults if lang.yml is missing or incomplete.
 */
public class Messages {
    
    private final GPExpansionPlugin plugin;
    private FileConfiguration langConfig;
    private File langFile;
    private String currentVersion = "0.1.2a"; // Default fallback version
    
    // Cache for frequently accessed messages
    private final Map<String, String> messageCache = new HashMap<>();
    
    // Hardcoded fallback defaults
    private static final Map<String, String> DEFAULTS = new HashMap<>();
    
    static {
        // General
        DEFAULTS.put("general.prefix", "&8[&6GPX&8]&r ");
        DEFAULTS.put("general.no-permission", "&cYou don't have permission to do that.");
        DEFAULTS.put("general.reload-success", "&aConfiguration and language files reloaded successfully.");
        DEFAULTS.put("general.player-only", "&cThis command can only be used by players.");

        // Configuration migration
        DEFAULTS.put("migration.accruals-section-added", "&8[&6GPX&8] &7Added missing accruals.groups section to config.yml.");
        DEFAULTS.put("migration.accruals-section-failed", "&8[&6GPX&8] &cFailed to add accruals.groups section: {error}");
        
        // Permissions
        DEFAULTS.put("permissions.economy-type-denied", "&c✖ You don't have permission to use &6{economy}&c economy for {signtype} signs.");
        DEFAULTS.put("permissions.economy-type-denied-detail", "&7  Missing: &e{permission}");
        DEFAULTS.put("permissions.create-sign-denied", "&cYou don't have permission to create {signtype} signs.");
        DEFAULTS.put("permissions.create-sign-denied-detail", "&7  Missing: &e{permission}");
        DEFAULTS.put("permissions.sign-anywhere-denied", "&cYou can only create {signtype} signs in your own claims.");
        DEFAULTS.put("permissions.command-anywhere-denied", "&cYou can only run this command in your own claims.");
        DEFAULTS.put("permissions.anywhere-denied-detail", "&7  Missing: &e{permission}");
        
        // Sign creation
        DEFAULTS.put("sign-creation.rent-limit-reached", "&cYou have reached your rent sign limit ({current}/{max}).");
        DEFAULTS.put("sign-creation.sell-limit-reached", "&cYou have reached your sell sign limit ({current}/{max}).");
        DEFAULTS.put("sign-creation.mailbox-limit-reached", "&cYou have reached your mailbox sign limit ({current}/{max}).");
        DEFAULTS.put("sign-creation.invalid-economy-type", "&cInvalid economy type: {type}");
        DEFAULTS.put("sign-creation.invalid-claim-id", "&cClaim not found with ID: {id}");
        DEFAULTS.put("sign-creation.vault-required", "&cMoney economy requires a Vault economy provider.");
        DEFAULTS.put("sign-creation.item-required", "&cHold the payment item in your offhand when creating an Item sign.");
        
        // Sign interaction
        DEFAULTS.put("sign-interaction.rent-success", "&aYou have rented claim {id} for {duration}. Cost: {cost}");
        DEFAULTS.put("sign-interaction.rent-too-close-to-max", "&cYou cannot renew yet - Next available renewal: &7{time}");
        DEFAULTS.put("sign-interaction.confirmation-header-line", "&8&m●                                                   &8●");
        DEFAULTS.put("sign-interaction.confirmation-title-rent", "&6&l>> Rent Confirmation <<");
        DEFAULTS.put("sign-interaction.confirmation-title-purchase", "&6&l>> Purchase Confirmation <<");
        DEFAULTS.put("sign-interaction.confirmation-item", "&7Item: &eclaim {id}");
        DEFAULTS.put("sign-interaction.confirmation-duration", "&7Duration: &e{duration}");
        DEFAULTS.put("sign-interaction.confirmation-cost", "&7Cost: &e{cost}");
        DEFAULTS.put("sign-interaction.confirmation-button-confirm", "&a&lCONFIRM");
        DEFAULTS.put("sign-interaction.confirmation-button-cancel", "&c&lCANCEL");
        DEFAULTS.put("sign-interaction.confirmation-hover-confirm-rent", "&aClick to confirm rent");
        DEFAULTS.put("sign-interaction.confirmation-hover-confirm-purchase", "&aClick to confirm purchase");
        DEFAULTS.put("sign-interaction.confirmation-hover-cancel", "&cClick to cancel");
        DEFAULTS.put("sign-interaction.confirmation-cancelled", "&eCancelled.");
        DEFAULTS.put("sign-interaction.confirmation-command-hint", "&7Type &e/gpxconfirm accept&7 to confirm.");
        DEFAULTS.put("sign-interaction.sign-display-rent-full", "&a&l[Rent Claim]");
        DEFAULTS.put("sign-interaction.sign-display-rent-hanging", "&a&l[Rent]");
        DEFAULTS.put("sign-interaction.sign-display-buy-full", "&a&l[Buy Claim]");
        DEFAULTS.put("sign-interaction.sign-display-buy-hanging", "&a&l[Sell]");
        
        // Claim teleport
        DEFAULTS.put("claim.teleport-usage", "&cUsage: /claim tp <claimId> [player]");
        DEFAULTS.put("claim.teleport-success", "&aTeleported to claim &6{id}&a.");
        DEFAULTS.put("claim.teleport-other-success", "&aTeleported &6{player}&a to claim &6{id}&a.");
        DEFAULTS.put("claim.teleport-by-other", "&aYou were teleported to claim &6{id}&a.");
        DEFAULTS.put("claim.teleport-no-location", "&cCould not find a teleport location for claim &6{id}&c.");
        DEFAULTS.put("claim.teleport-unsafe", "&cThe spawn location for claim &6{id}&c is unsafe and cannot be teleported to.");
        DEFAULTS.put("claim.teleport-unsafe-confirm", "&eWarning: The spawn location for claim &6{id}&c is unsafe. Running this command again will confirm teleport to a nearby safe location.");
        DEFAULTS.put("claim.teleport-delay-start", "&eTeleporting to claim &6{id}&e in &6{time}&e.");
        DEFAULTS.put("claim.teleport-cooldown", "&cYou must wait &6{time}&c before teleporting to another claim.");
        DEFAULTS.put("claim.player-not-found", "&cPlayer not found: &6{player}");
        DEFAULTS.put("claim.setspawn-not-in-claim", "&cYou must be standing inside a claim to set its spawn point.");
        DEFAULTS.put("claim.setspawn-not-owner", "&cYou must be the owner of this claim to set its spawn point.");
        DEFAULTS.put("claim.setspawn-success", "&aSpawn point set for claim &6{id}&a.");
        DEFAULTS.put("claim.setspawn-error", "&cAn error occurred while setting the spawn point.");
        DEFAULTS.put("claim.setspawn-unsafe", "&cCannot set spawn point in an unsafe location.");
        
        // GUI messages
        DEFAULTS.put("gui.rename-prompt", "&eType the new name for claim &6{id}&e in chat, or type &ccancel&e to cancel.");
        DEFAULTS.put("gui.description-prompt", "&eType the new description for claim &6{id}&e in chat, or type &ccancel&e to cancel.");
        DEFAULTS.put("gui.description-cancelled", "&eDescription update cancelled for claim &6{id}&e.");
        DEFAULTS.put("gui.rental-renew-hint", "&eFind the rental sign for claim &6{id}&e and right-click to renew.");
        DEFAULTS.put("gui.ban-manage-hint", "&eManaging bans for claim &6{id}&e:");
        DEFAULTS.put("gui.not-enabled", "&cGUI mode is not enabled.");
        DEFAULTS.put("gui.claim-listed", "&aClaim &6{id}&a is now publicly listed!");
        DEFAULTS.put("gui.claim-unlisted", "&eClaim &6{id}&e is no longer publicly listed.");
        DEFAULTS.put("gui.icon-set", "&aIcon set for claim &6{id}&a.");
        DEFAULTS.put("gui.description-set", "&aDescription set for claim &6{id}&a.");
        DEFAULTS.put("gui.claim-renamed", "&aClaim &6{id}&a renamed to &f{name}&a.");
        DEFAULTS.put("gui.search-no-results", "&cNo claims found matching: &e{query}");
        DEFAULTS.put("gui.search-found", "&aFound claim: &f{name} &7(ID: {id})");
        DEFAULTS.put("gui.player-unbanned", "&aUnbanned &f{player}&a from claim &6{id}&a.");
        DEFAULTS.put("gui.player-banned", "&aBanned &f{player}&a from claim &6{id}&a.");
        DEFAULTS.put("gui.color-not-allowed", "&cYou don't have permission to use the color code &e{code}&c.");
        DEFAULTS.put("gui.format-not-allowed", "&cYou don't have permission to use the format code &e{code}&c.");
        DEFAULTS.put("claim.global-usage", "&cUsage: /claim global <true|false> [claimId]");
        DEFAULTS.put("claim.global-disabled", "&cGlobal claims are disabled on this server.");
        DEFAULTS.put("claim.global-teleport-disabled", "&eGlobal claim teleport is disabled. Travel to &6{location}&e for claim &6{id}&e.");
        DEFAULTS.put("claim.global-approval-required", "&eClaim &6{id}&e was submitted for global listing approval. A staff member must approve it first.");
        DEFAULTS.put("claim.global-not-in-claim", "&cYou must be standing in a claim or provide a claim ID.");
        DEFAULTS.put("claim.global-not-owner", "&cYou must own this claim to toggle its global listing.");
        DEFAULTS.put("claim.global-limit-reached", "&cYou have reached your global claim limit! &7({current}/{max})");
        DEFAULTS.put("claim.approve-usage", "&eUsage: /approveclaim [claimId] or /aclaim approve [claimId]");
        DEFAULTS.put("claim.approve-not-pending", "&eClaim &6{id}&e is not waiting for approval.");
        DEFAULTS.put("claim.approve-already-listed", "&eClaim &6{id}&e is already publicly listed.");
        DEFAULTS.put("claim.approve-success", "&aApproved global listing for claim &6{id}&a.");
        
        // Wizard - General
        DEFAULTS.put("wizard.cancelled", "&cSetup wizard cancelled.");
        DEFAULTS.put("wizard.previous-cancelled", "&7(Previous setup wizard cancelled)");
        DEFAULTS.put("wizard.autopaste-place-in-claim", "&eAuto-paste: &7Place the sign &einside a claim you own or rent&7 to auto-fill the format.");
        DEFAULTS.put("wizard.autopaste-anywhere-required", "&eAuto-paste: &7To paste outside your claim you need &e{permission}&7.");
        DEFAULTS.put("wizard.autopaste-edit-then-done", "&e&lSign Auto-Paste: &7Edit if needed, then click Done.");
        DEFAULTS.put("wizard.autopaste-placed-success", "&a✓ Sign placed successfully!");
        DEFAULTS.put("wizard.autopaste-format-filled", "&a✓ Sign format auto-filled!");
        DEFAULTS.put("wizard.autopaste-failed-place", "&cFailed to place sign. Please try again.");
        DEFAULTS.put("wizard.autopaste-sign-removed", "&cSign was removed. Please try again.");
        DEFAULTS.put("wizard.claim-not-found", "&cClaim ID not found: {id}");
        DEFAULTS.put("wizard.not-claim-owner", "&cYou don't own that claim!");
        DEFAULTS.put("wizard.invalid-claim-id", "&cInvalid claim ID. Please enter a number.\n&7(Type 'cancel' to exit the wizard)");
        DEFAULTS.put("wizard.invalid-duration", "&cInvalid duration format. Use: &e<number><s/m/h/d/w>\n&7Examples: &e30s&7, &e1h&7, &e7d&7, &e1w");
        DEFAULTS.put("wizard.invalid-economy-type", "&cInvalid economy type.\n&7Options: &e{options}");
        DEFAULTS.put("wizard.invalid-price", "&cInvalid price. Please enter a number.");
        DEFAULTS.put("wizard.vault-required", "&cMoney payments require Vault and an economy plugin.\n&7Please choose a different payment type.");
        DEFAULTS.put("wizard.yes-or-no", "&7Type &ayes&7 or &cno&7.");
        DEFAULTS.put("wizard.confirm-prompt", "&7Type &ayes&7 to confirm or &cno&7 to cancel.");
        DEFAULTS.put("wizard.gp3d-required", "&cMailbox setup requires GP3D (GriefPrevention 3D) to be installed.");
        DEFAULTS.put("wizard.mailbox-must-be-subdivision", "&cMailbox must reference a 3D subdivision.");
        DEFAULTS.put("wizard.mailbox-wrong-size", "&cMailbox must reference a 1x1x1 subdivision (current: {width}x{height}x{depth}).");
        
        // Wizard - Rent
        DEFAULTS.put("wizard.rent-start", "&aRenting claim &6{id}&a...");
        DEFAULTS.put("wizard.rent-start-no-claim", "&a&l=== Rent Claim Sign Setup ===");
        DEFAULTS.put("wizard.rent-enter-claim-id", "&eEnter the claim ID:");
        DEFAULTS.put("wizard.rent-enter-claim-id-hint", "&7(Quick tip: do &6/claimlist&7 to view your claim IDs)");
        DEFAULTS.put("wizard.rent-enter-economy", "&eWhat payment type?");
        DEFAULTS.put("wizard.rent-enter-economy-hint", "&7Options: &e{options}");
        
        // Wizard - Sell
        DEFAULTS.put("wizard.sell-start", "&aSelling claim &6{id}&a...");
        DEFAULTS.put("wizard.sell-start-no-claim", "&a&l=== Sell Claim Sign Setup ===");
        DEFAULTS.put("wizard.sell-enter-claim-id", "&eEnter the claim ID:");
        DEFAULTS.put("wizard.sell-enter-claim-id-hint", "&7(Quick tip: do &6/claimlist&7 to view your claim IDs)");
        DEFAULTS.put("wizard.sell-enter-economy", "&eWhat payment type?");
        DEFAULTS.put("wizard.sell-enter-economy-hint", "&7Options: &e{options}");
        
        // Wizard - Mailbox
        DEFAULTS.put("wizard.mailbox-start", "&aSetting up mailbox for claim &6{id}&a...");
        DEFAULTS.put("wizard.mailbox-start-no-claim", "&a&l=== Mailbox Sign Setup ===");
        DEFAULTS.put("wizard.mailbox-choose-type", "&eSelf mailbox or buyable? Type &aself&e or &abuyable&e.");
        DEFAULTS.put("wizard.mailbox-self-or-buyable-hint", "&7Self = instant mailbox for you (place [Mailbox] on container). Buyable = others pay to get the mailbox.");
        DEFAULTS.put("wizard.mailbox-enter-claim-id", "&eEnter the claim ID:");
        DEFAULTS.put("wizard.mailbox-enter-claim-id-hint", "&7(Quick tip: do &6/claimlist&7 to view your claim IDs)");
        DEFAULTS.put("wizard.mailbox-enter-economy", "&eWhat payment type?");
        DEFAULTS.put("wizard.mailbox-enter-economy-hint", "&7Options: &e{options}");
        
        // Wizard - Auto-paste
        DEFAULTS.put("wizard.auto-paste-ready", "&a✓ Sign format loaded! Edit if needed, then click Done.");
        DEFAULTS.put("wizard.auto-paste-item-reminder", "&e⚠ Hold the payment item in your offhand when placing the sign!");
        DEFAULTS.put("wizard.auto-paste-cancelled", "&cAuto-paste mode cancelled.");
        
        // Wizard - Step prompts
        DEFAULTS.put("wizard.step-prompt", "&aStep {step}: {prompt}");
        DEFAULTS.put("wizard.cancel-hint", "&8(Type 'cancel' at any time to exit)");
        
        // Claim
        DEFAULTS.put("claim.list-header", "&eClaims ({count}):");
        DEFAULTS.put("claim.list-page", "&7Page {page}/{pages}");
        DEFAULTS.put("claim.list-player-header", "&eClaims for &6{player}&e:");
        DEFAULTS.put("claim.list-player-not-found", "&cPlayer not found: &6{player}");
        DEFAULTS.put("claim.list-usage-other", "&cUsage: /claimlist <player>");
        DEFAULTS.put("claim.list-trusted-header", "&eTrusted Claims ({count}):");
        DEFAULTS.put("claim.list-admin-header", "&eAdmin Claims ({count}):");
        DEFAULTS.put("claim.line-format", "&eID {id} &e({name}&e) {world}: x{x}, z{z} (-{area} blocks)");
        DEFAULTS.put("claim.line-format-unnamed", "&eID {id} &e(unnamed) {world}: x{x}, z{z} (-{area} blocks)");
        DEFAULTS.put("claim.subline-format", "&7- ID &f{id} &7({name}&7) &f{world}&7: &fx{x}&7, z&f{z} &8(&6Child of {parent}&8)");
        DEFAULTS.put("claim.subline-format-unnamed", "&7- ID &f{id} &7(unnamed) &f{world}&7: &fx{x}&7, z&f{z} &8(&6Child of {parent}&8)");
        DEFAULTS.put("claim.list-empty", "&7You don't own any claims.");
        DEFAULTS.put("claim.not-found", "&cClaim ID not found: {id}");
        DEFAULTS.put("claim.not-owner", "&cYou must own claim {id} to do that.");
        DEFAULTS.put("claim.owner-unknown", "&cUnable to determine the claim owner.");
        DEFAULTS.put("claim.name-set", "&aClaim name set to: {name}");
        DEFAULTS.put("claim.name-truncated", "&eName truncated to {max} characters.");
        DEFAULTS.put("claim.name-no-permission", "&cYou lack permission: &egriefprevention.claim.name");
        DEFAULTS.put("claim.transfer-success", "&aClaim {id} transferred to {player}.");
        DEFAULTS.put("claim.transfer-received", "&aYou are now the owner of claim {id}.");
        DEFAULTS.put("claim.ban-success", "&aBanned {player} from claim {id}.");
        DEFAULTS.put("claim.unban-success", "&aUnbanned {player} from claim {id}.");
        DEFAULTS.put("claim.blocks-total", "&e{accrued} blocks from play + {bonus} bonus = {total} total.");
        DEFAULTS.put("claim.blocks-remaining", "&e= {remaining} blocks left to spend");
        DEFAULTS.put("claim.id-missing", "&cCould not determine claim ID for this action.");
        DEFAULTS.put("claim.main-id-missing", "&cCould not determine main claim ID.");
        DEFAULTS.put("claim.must-own-action", "&cYou must own this claim to {action}.");
        DEFAULTS.put("claim.abandon-subdivision-warning", "&7This will delete &6{count} subdivision{plural}.");
        DEFAULTS.put("claim.bar-current", "&7Your locator bar mode is &e{mode}&7. The bar shows: &e{what}&7.");
        DEFAULTS.put("claim.bar-set", "&aLocator bar mode set to &e{mode}&a. The bar now shows: &e{what}&a.");
        DEFAULTS.put("claim.bar-current-mode-off", "&7Your locator bar mode is &eoff&7. Nothing appears on the bar.");
        DEFAULTS.put("claim.bar-set-mode-off", "&aLocator bar mode set to &eoff&a. Nothing appears on the bar.");
        DEFAULTS.put("claim.bar-mode-what-own", "only your own claims");
        DEFAULTS.put("claim.bar-mode-what-trusted", "your claims and claims you can access");
        DEFAULTS.put("claim.bar-mode-what-both", "your claims, claims you can access, and published claims");
        DEFAULTS.put("claim.bar-invalid", "&cUnknown mode &6{label}&c. Valid modes: &e{modes}&c.");
        DEFAULTS.put("startup.tag-author", "&7by castledking");
        DEFAULTS.put("startup.tag-plugin", "&6The GriefPrevention expansion plugin \n&b- signs, economy, rentals, claim fly & waypoints");
        DEFAULTS.put("startup.details", "&fStartup details:");
        DEFAULTS.put("startup.boot-finished", "&7Boot finished in: &a{boot}");
        DEFAULTS.put("claim.transfer-usage", "&eUsage: /claim transfer <claimId> <player>");
        DEFAULTS.put("claim.transfer-failed-contact", "&cFailed to transfer claim ownership. Please contact an admin.");
        DEFAULTS.put("claim.name-usage", "&eUsage: /claim name <newName> [claimId]");
        DEFAULTS.put("claim.icon-no-permission", "&cYou lack permission: &egriefprevention.claim.icon");
        DEFAULTS.put("claim.icon-hold-item", "&cYou must hold an item to set as the claim icon.");
        DEFAULTS.put("claim.icon-denied", "&cThe icon &e{icon}&c is not allowed for claim icons.");
        DEFAULTS.put("claim.icon-set", "&aClaim {id} icon set to {icon}.");
        DEFAULTS.put("claim.description-no-permission", "&cYou lack permission: &egriefprevention.claim.description");
        DEFAULTS.put("claim.description-usage", "&eUsage: /claim desc <description...> [claimId]");
        DEFAULTS.put("claim.description-links-denied", "&cLinks are not allowed in claim descriptions.");
        DEFAULTS.put("claim.description-truncated", "&eDescription truncated to {max} characters.");
        DEFAULTS.put("claim.description-set", "&aClaim {id} description set to: {description}");
        DEFAULTS.put("claim.ban-usage", "&eUsage: /claim ban <player|public> [claimId]");
        DEFAULTS.put("claim.ban-no-permission", "&cYou lack permission: &egriefprevention.claim.ban");
        DEFAULTS.put("claim.public-ban-no-permission", "&cYou lack permission: &e{permission}");
        DEFAULTS.put("claim.ban-self", "&cYou cannot ban yourself.");
        DEFAULTS.put("claim.unban-usage", "&eUsage: /claim unban <player|public> [claimId]");
        DEFAULTS.put("claim.unban-no-permission", "&cYou lack permission: &egriefprevention.claim.unban");
        DEFAULTS.put("claim.unban-public-missing", "&eClaim {id} is not public-banned.");
        DEFAULTS.put("claim.unban-not-banned", "&e{player} is not banned from claim {id}.");
        DEFAULTS.put("claim.ban-blocked-enter", "&cYou are not allowed to enter this claim!");
        DEFAULTS.put("claim.ban-blocked-inside", "&cYou are not allowed to be in this claim!");
        DEFAULTS.put("claim.ban-blocked-teleport", "&cYou cannot teleport into this claim - you are banned!");
        DEFAULTS.put("claim.ban-blocked-mount", "&cYou cannot ride entities in a claim you are banned from!");
        DEFAULTS.put("claim.ban-blocked-interact", "&cYou cannot touch anything in a claim you are banned from!");
        DEFAULTS.put("claim.ban-ignoreclaims-hint", "&7To override use &e/ignoreclaims&7.");
        DEFAULTS.put("claim.banlist-no-permission", "&cYou lack permission: &egriefprevention.claim.banlist");
        DEFAULTS.put("claim.banlist-header", "&eBan list for claim {id}:");
        DEFAULTS.put("claim.banlist-public", "&6 - Public banned");
        DEFAULTS.put("claim.banlist-empty", "&7 - No players banned");
        DEFAULTS.put("claim.banlist-entry", "&c - {player}");
        DEFAULTS.put("claim.evict-usage", "&eUsage: /claim evict [claimId]");
        DEFAULTS.put("claim.evict-help", "&7Starts a {duration} eviction notice for the renter.");
        DEFAULTS.put("claim.evict-no-permission", "&cYou lack permission: &egriefprevention.evict");
        DEFAULTS.put("claim.not-rented", "&cThis claim is not currently rented.");
        DEFAULTS.put("claim.cancelrent-not-rented", "&cClaim {id} is not actively rented.");
        DEFAULTS.put("claim.cancelrent-not-renter", "&cYou are not the active renter for claim {id}.");
        DEFAULTS.put("claim.cancelrent-success", "&aCancelled rental for claim &6{id}&a.");
        DEFAULTS.put("claim.cancelrent-sign-not-found", "&eRental data was cleared, but the rental sign location is unknown. The sign may need manual cleanup.");
        DEFAULTS.put("claim.cancelrent-cancelled-by-admin", "&eYour rental for claim &6{id}&e was cancelled by an admin.");
        DEFAULTS.put("claim.rental-sign-confirm-usage", "&eUsage: /claim rentalsignconfirm <world> <x> <y> <z>");
        DEFAULTS.put("claim.coords-must-be-int", "&cCoordinates must be integers.");
        DEFAULTS.put("claim.world-unknown", "&cUnknown world: {world}");
        DEFAULTS.put("claim.sign-not-found", "&cNo managed sign found at that location.");
        DEFAULTS.put("claim.sign-not-managed", "&cThat sign is not managed by GPExpansion.");
        DEFAULTS.put("claim.sign-use-denied", "&cYou don't have permission to use this sign.");
        DEFAULTS.put("claim.pending-rent-none", "&eYou have no pending rental payments to collect.");
        DEFAULTS.put("claim.pending-rent-failed-money", "&cFailed to give you  from rentals.");
        DEFAULTS.put("claim.pending-rent-claimblocks", "&aYou received {amount} claim blocks from rentals!");
        DEFAULTS.put("claim.pending-rent-collected", "&aSuccessfully collected all pending rental payments!");
        DEFAULTS.put("claim.teleport-safe-location-fail", "&cCould not compute a safe location in claim {id}.");
        DEFAULTS.put("claim.teleport-cancelled-move", "&cTeleport cancelled because you moved.");
        DEFAULTS.put("claim.untrust-renter", "&cYou cannot untrust {renter} while they are renting your claim.");
        DEFAULTS.put("claim.untrust-renter-hint", "&eUse {command} instead.");

        // Tax
        DEFAULTS.put("tax.payee-notify", "&7Tax withheld: &e{tax}&7. Net received: &a{net}&7.");
        DEFAULTS.put("tax.payer-notify", "&7Tax on this payment: &e{tax}&7.");
        
        // Admin
        DEFAULTS.put("admin.gpx-help-header", "&6=== GPExpansion Admin Commands ===");
        DEFAULTS.put("admin.gpx-reload", "&e/gpx reload &7- Reload config and language files");
        DEFAULTS.put("admin.gpx-debug", "&e/gpx debug &7- Toggle debug mode");
        DEFAULTS.put("admin.gpx-max", "&e/gpx max &7- Modify player creation limits.");
        DEFAULTS.put("admin.gpx-accruals", "&e/gpx accruals &7- Check and modify claim block accrual profiles.");
        DEFAULTS.put("admin.debug-enabled", "&aDebug mode enabled.");
        DEFAULTS.put("admin.debug-disabled", "&cDebug mode disabled.");
        DEFAULTS.put("admin.no-permission", "&cYou don't have permission to use this command.");
        
        // Command messages
        DEFAULTS.put("commands.claim-usage", "&e/{label} <{subs}>");
        DEFAULTS.put("commands.unknown-subcommand", "&cUnknown subcommand. Try: {subs}");
        DEFAULTS.put("commands.unknown-subcommand-help", "&cUnknown subcommand. Do &e/claimhelp &cfor help.");
        DEFAULTS.put("commands.exec-failed", "&cCommand failed to execute: {command}");
        DEFAULTS.put("commands.exec-error", "&cAn error occurred while executing the command: {error}");
        DEFAULTS.put("commands.player-not-online", "&cPlayer '{player}' is not online.");
        DEFAULTS.put("commands.gpx-max-usage", "&cUsage: /gpx max <sell|rent|mailbox|self-mailboxes|globals> <add|take|set> <player> <amount>");
        DEFAULTS.put("commands.gpx-max-invalid-type", "&cInvalid type. Use 'sell', 'rent', 'mailbox', 'self-mailboxes', or 'globals'.");
        DEFAULTS.put("commands.gpx-max-invalid-action", "&cInvalid action. Use 'add', 'take', or 'set'.");
        DEFAULTS.put("commands.gpx-max-amount-required", "&cPlease specify an amount.");
        DEFAULTS.put("commands.gpx-max-amount-positive", "&cAmount must be positive.");
        DEFAULTS.put("commands.gpx-max-amount-invalid", "&cInvalid amount.");
        DEFAULTS.put("commands.gpx-max-added", "&aAdded {amount} to {player}'s {type} limit.");
        DEFAULTS.put("commands.gpx-max-added-player", "&eYour {type} limit has been increased by {amount}.");
        DEFAULTS.put("commands.gpx-max-removed", "&aRemoved {amount} from {player}'s {type} limit.");
        DEFAULTS.put("commands.gpx-max-removed-player", "&eYour {type} limit has been decreased by {amount}.");
        DEFAULTS.put("commands.gpx-max-set", "&aSet {player}'s {type} limit to {amount}.");
        DEFAULTS.put("commands.gpx-max-set-player", "&eYour {type} limit has been set to {amount}.");
        DEFAULTS.put("commands.gpx-max-current-header", "&b{player}'s current limits:");
        DEFAULTS.put("commands.gpx-max-current-sell", "&b  Sell signs: {count}");
        DEFAULTS.put("commands.gpx-max-current-rent", "&b  Rent signs: {count}");
        DEFAULTS.put("commands.gpx-max-current-mailbox", "&b  Mailbox signs: {count}");
        DEFAULTS.put("commands.gpx-max-current-self-mailbox", "&b  Self mailboxes per claim: {count}");
        DEFAULTS.put("commands.gpx-max-current-globals", "&b  Global claims: {count}");
        DEFAULTS.put("commands.gpx-max-desync-warning", "&eWarning: Permission desync detected for {player}!");
        DEFAULTS.put("commands.gpx-max-desync-cleanup", "&aPermissions will be automatically cleaned up using {plugin}.");
        DEFAULTS.put("commands.gpx-max-desync-unsupported", "&cNo supported permission plugin found for automatic cleanup.");
        DEFAULTS.put("commands.gpx-max-desync-manual", "&cPlease manually remove duplicate permissions and set the highest one.");
        DEFAULTS.put("commands.accruals-manager-unavailable", "&cAccrual permission manager is not available.");
        DEFAULTS.put("commands.accruals-usage", "&cUsage: /gpx accruals <check|group|player|creategroup|deletegroup> ...");
        DEFAULTS.put("commands.accruals-usage-check", "&7/gpx accruals check [player]");
        DEFAULTS.put("commands.accruals-usage-group", "&7/gpx accruals group <group> set|add|remove <per-hour|max-blocks|max-claims|all> <amount>");
        DEFAULTS.put("commands.accruals-usage-player", "&7/gpx accruals player <name> set|add|remove|reset <per-hour|max-blocks|max-claims|all> [amount]");
        DEFAULTS.put("commands.accruals-usage-creategroup", "&7/gpx accruals creategroup <name> [blocks-per-hour] [max-blocks] [max-claims] [permission]");
        DEFAULTS.put("commands.accruals-usage-deletegroup", "&7/gpx accruals deletegroup <name>");
        DEFAULTS.put("commands.accruals-unknown-action", "&cUnknown accruals action. Use check, group, player, creategroup, or deletegroup.");
        DEFAULTS.put("commands.accruals-check-online-required", "&cPlayer must be online to resolve effective accrual group: {player}");
        DEFAULTS.put("commands.accruals-check-console-usage", "&cUsage from console: /gpx accruals check <player>");
        DEFAULTS.put("commands.accruals-check-header", "&b{player} accruals:");
        DEFAULTS.put("commands.accruals-check-group", "&7  group: &f{group} &8({source})");
        DEFAULTS.put("commands.accruals-check-blocks", "&7  blocks/hour: &f{amount}");
        DEFAULTS.put("commands.accruals-check-max-blocks", "&7  max blocks: &f{amount}");
        DEFAULTS.put("commands.accruals-check-max-claims", "&7  max claims: &f{amount}");
        DEFAULTS.put("commands.accruals-check-override", "&7  player override: &f{override}");
        DEFAULTS.put("commands.accruals-disabled", "disabled");
        DEFAULTS.put("commands.accruals-unlimited", "unlimited");
        DEFAULTS.put("commands.accruals-none", "none");
        DEFAULTS.put("commands.accruals-source-fallback", "fallback");
        DEFAULTS.put("commands.accruals-source-player-override", "player override");
        DEFAULTS.put("commands.accruals-source-group", "accrual group");
        DEFAULTS.put("commands.accruals-group-usage", "&cUsage: /gpx accruals group <group> set|add|remove <per-hour|max-blocks|max-claims|all> <amount>");
        DEFAULTS.put("commands.accruals-unknown-group", "&cUnknown accrual group: {group}");
        DEFAULTS.put("commands.accruals-invalid-group-action", "&cInvalid action. Use set, add, or remove.");
        DEFAULTS.put("commands.accruals-invalid-field", "&cInvalid field. Use per-hour, max-blocks, max-claims, or all.");
        DEFAULTS.put("commands.accruals-amount-number", "&cAmount must be a number.");
        DEFAULTS.put("commands.accruals-amount-nonnegative", "&cAmount cannot be negative.");
        DEFAULTS.put("commands.accruals-group-updated", "&aUpdated accrual group {group}: {fields} {action} {amount}.");
        DEFAULTS.put("commands.accruals-player-usage", "&cUsage: /gpx accruals player <name> set|add|remove|reset <per-hour|max-blocks|max-claims|all> [amount]");
        DEFAULTS.put("commands.accruals-unknown-player", "&cUnknown player: {player}");
        DEFAULTS.put("commands.accruals-player-reset", "&aReset {fields} accrual override for {player}.");
        DEFAULTS.put("commands.accruals-player-reset-none", "&eNo matching accrual override was set for {player}.");
        DEFAULTS.put("commands.accruals-invalid-player-action", "&cInvalid action. Use set, add, remove, or reset.");
        DEFAULTS.put("commands.accruals-player-action-usage", "&cUsage: /gpx accruals player <name> {action} <per-hour|max-blocks|max-claims|all> <amount>");
        DEFAULTS.put("commands.accruals-player-updated", "&aUpdated player {player} accrual override: {fields} {action} {amount}.");
        DEFAULTS.put("commands.accruals-effective-now", "&7Effective now: {blocks}/hour, max {maxBlocks} blocks, {maxClaims} claims.");
        DEFAULTS.put("commands.accruals-creategroup-usage", "&cUsage: /gpx accruals creategroup <name> [blocks-per-hour] [max-blocks] [max-claims] [permission]");
        DEFAULTS.put("commands.accruals-group-exists", "&cAccrual group already exists: {group}");
        DEFAULTS.put("commands.accruals-group-create-failed", "&cFailed to create accrual group {group}.");
        DEFAULTS.put("commands.accruals-group-created", "&aCreated accrual group {group} with {blocks}/hour, max {maxBlocks} blocks, {maxClaims} claims.");
        DEFAULTS.put("commands.accruals-luckperms-weight", "&7LuckPerms weight {weight} detected; accruals.groups was sorted with lower weights higher.");
        DEFAULTS.put("commands.accruals-luckperms-no-weight", "&7LuckPerms group detected without a weight; existing list order was preserved for this group.");
        DEFAULTS.put("commands.accruals-deletegroup-usage", "&cUsage: /gpx accruals deletegroup <name>");
        DEFAULTS.put("commands.accruals-group-deleted", "&aDeleted accrual group {group}.");
        DEFAULTS.put("commands.accruals-claim-limit", "&cYou have reached your claim limit ({max}) for accrual profile {profile}.");
        DEFAULTS.put("commands.accruals-cap-reached", "&eYour accrued claim blocks were capped at {max} for accrual profile {profile}.");
        DEFAULTS.put("commands.accruals-received", "&aYou received {amount} bonus claim blocks from your {profile} profile.");
        DEFAULTS.put("commands.accruals-redundant-group-warning", "No 'permission:' section found for accrual group '{group}', and no matching Vault or LuckPerms group was found. This accrual config is redundant.");
        DEFAULTS.put("commands.accruals-invalid-group-entry-warning", "Skipping accruals.groups entry {index} because it has no name.");
        
        // Claim flags
        DEFAULTS.put("flags.no-permission", "&cYou don't have permission to set claim flags.");
        DEFAULTS.put("flags.no-permission-flag", "&cYou don't have permission to use the {flag} flag.");
        DEFAULTS.put("flags.not-owner", "&cYou can only modify flags on your own claims.");
        DEFAULTS.put("flags.toggled", "&aFlag {flag} {state}.");
        DEFAULTS.put("flags.toggle-failed", "&cFailed to toggle flag {flag}.");
        
        // Mailbox
        DEFAULTS.put("mailbox.invalid-sign", "&cInvalid mailbox sign.");
        DEFAULTS.put("mailbox.claim-not-found", "&cClaim not found.");
        DEFAULTS.put("mailbox.no-container", "&cNo container found in mailbox claim.");
        DEFAULTS.put("mailbox.invalid-container", "&cInvalid container.");
        DEFAULTS.put("mailbox.deposit-only", "&cYou can only deposit items, not take them!");
        DEFAULTS.put("mailbox.full-warning", "&cWARNING: This mailbox is completely full!");
        DEFAULTS.put("mailbox.almost-full-warning", "&eWARNING: This mailbox is almost full ({slots} slots left)!");
        DEFAULTS.put("mailbox.storage-full-warning", "&cWARNING: Your mailbox at {x},{y},{z} is completely full!");
        DEFAULTS.put("mailbox.storage-almost-full-warning", "&eWARNING: Your mailbox at {x},{y},{z} is almost full ({slots} slots left)!");
        DEFAULTS.put("mailbox.already-purchased", "&cThis mailbox has already been purchased.");
        DEFAULTS.put("mailbox.sign-not-found", "&cMailbox sign not found.");
        DEFAULTS.put("mailbox.payment-failed", "&cPayment failed.");
        DEFAULTS.put("mailbox.purchase-success", "&aYou have successfully purchased this mailbox!");
        DEFAULTS.put("mailbox.economy-not-available", "&cEconomy not available. Please ensure Vault and an economy provider are installed.");
        DEFAULTS.put("mailbox.not-enough-money", "&cYou don't have enough money.");
        DEFAULTS.put("mailbox.not-enough-experience", "&cYou don't have enough experience.");
        DEFAULTS.put("mailbox.items-returned", "&eSome items were returned - the mailbox was updated since you opened it.");
        DEFAULTS.put("mailbox.must-own-or-rent", "&cYou must own or rent this claim to create an instant mailbox.");
        DEFAULTS.put("mailbox.self-disabled", "&cSelf mailboxes are disabled on this server.");
        DEFAULTS.put("mailbox.stacked-disabled", "&cA mailbox already uses that container.");
        DEFAULTS.put("mailbox.self-limit-reached", "&cYou have reached the self mailbox limit ({max}) for this claim.");
        DEFAULTS.put("mailbox.instant-creation-failed", "&cCould not create subdivision. GP3D with AllowNestedSubclaims may be required.");
        DEFAULTS.put("mailbox.self-created", "&aSelf mailbox created!");
        DEFAULTS.put("mailbox.buyable-created", "&aBuyable mailbox sign created! Others can click to purchase.");
        DEFAULTS.put("mailbox.self-deleted", "&aSelf mailbox and subdivision removed.");
        DEFAULTS.put("mailbox.self-deleted-virtual", "&aSelf mailbox removed.");
        DEFAULTS.put("mailbox.in-use-by-other", "&cSomeone else is currently using this mailbox. Try again later.");
        DEFAULTS.put("mailbox.auto-kicked", "&eYou were removed from the mailbox for staying too long. Your items were saved. You cannot use mailboxes for 30 minutes.");
        DEFAULTS.put("mailbox.cooldown", "&cYou cannot use mailboxes for a while (removed for staying too long). Try again later.");
        
        // Sign creation / interaction extras
        DEFAULTS.put("sign-creation.sign-created", "&aSign created for claim &6{id}&a.");
        DEFAULTS.put("sign-creation.type-disabled", "&c{signtype} signs are disabled on this server.");
        DEFAULTS.put("sign-creation.sell-subclaim-not-allowed", "&cSell signs can only be created for top-level claims.");
        DEFAULTS.put("sign-creation.economy-disabled", "&c{economy} payments are disabled for {signtype} signs.");
        DEFAULTS.put("sign-creation.item-setup-reminder", "&eHold the payment item in your offhand and right-click the sign to set it.");
        DEFAULTS.put("sign-interaction.rent-own-claim", "&cYou cannot rent your own claim.");
        DEFAULTS.put("sign-interaction.sell-subclaim-not-allowed", "&cThis sell sign points to a subclaim. Only top-level claims can be sold.");
        DEFAULTS.put("sign-interaction.economy-disabled", "&c{economy} payments are disabled for {signtype} signs.");
        DEFAULTS.put("sign-interaction.renewals-disabled", "&cRental renewals are disabled on this server.");
        DEFAULTS.put("sign-interaction.renewal-denied-eviction", "&cYou cannot renew while eviction is in progress.");
        DEFAULTS.put("sign-interaction.rent-already-rented", "&cThis claim is already rented.");
        DEFAULTS.put("sign-interaction.sign-missing-data", "&cThis sign is missing required data. Please break and recreate it.");
        DEFAULTS.put("sign-interaction.sign-missing-economy", "&cThis sign is missing economy data. Please break and recreate it.");
        DEFAULTS.put("sign-interaction.invalid-claim-id", "&cInvalid claim ID on sign. Please break and recreate the sign.");
        DEFAULTS.put("sign-interaction.invalid-economy-type", "&cInvalid economy type on sign.");
        DEFAULTS.put("sign-interaction.economy-required", "&cThis rental requires an economy provider.");
        DEFAULTS.put("sign-interaction.error", "&cAn error occurred while processing this sign.");
        DEFAULTS.put("sign-interaction.item-updated", "&aSign updated with payment item: &e{item}&a.");
        DEFAULTS.put("sign-interaction.item-set-hint", "&eHold the payment item in your offhand and right-click to set it.");
        DEFAULTS.put("sign-interaction.item-not-configured", "&cThis sign is not yet configured. The claim owner needs to set the payment item.");
        DEFAULTS.put("sign-interaction.buy-success-claim", "&aYou have purchased claim &6{id}&a. The claim is now yours!");
        DEFAULTS.put("sign-interaction.owner-payment", "&a{player} has {action} your rental: [&6{id}&a]");
        DEFAULTS.put("sign-interaction.owner-claimblocks", "&aYou received {amount} claim blocks from rental!");
        DEFAULTS.put("sign-interaction.owner-items", "&aYou received items from rental!");
        
        // General additions
        DEFAULTS.put("general.unknown-player-online", "&cUnknown player: {player}. &eOnline players: {online}");
        DEFAULTS.put("permissions.missing", "&cYou lack permission: &e{permission}");
        DEFAULTS.put("gui.no-previous", "&eNo previous menu to return to.");
        DEFAULTS.put("wizard.cancel-none", "&7You don't have an active setup or auto-paste mode to cancel.");
        DEFAULTS.put("claiminfo.no-permission-other", "&cYou don't have permission to view info for claims you don't own.");
        
        // Eviction additions
        DEFAULTS.put("eviction.notice-passed", "&aThe eviction notice period has passed. You can now remove the renter.");
        DEFAULTS.put("eviction.cancel-hint", "&7Use /claim evict cancel {id} to cancel or break the rental sign.");
        DEFAULTS.put("eviction.notice-in-progress", "&eAn eviction is already in progress. {time} remaining before you can remove the renter.");
        DEFAULTS.put("eviction.notice-started", "&aEviction notice started for {renter}.");
        DEFAULTS.put("eviction.notice-duration", "&eThey have {duration} before you can remove them from the claim or break the sign.");
        DEFAULTS.put("eviction.notice-no-extend", "&7During this time, the renter cannot extend their rental.");
        DEFAULTS.put("eviction.notice-received", "&cYou have received an eviction notice for claim {id}.");
        DEFAULTS.put("eviction.notice-days", "&eYou have {duration} before you will be removed from this claim.");
        DEFAULTS.put("eviction.no-pending", "&cThere is no pending eviction for this claim.");
        DEFAULTS.put("eviction.no-pending-info", "&7There is no pending eviction for this claim.");
        DEFAULTS.put("eviction.cancelled", "&aEviction cancelled for claim {id}.");
        DEFAULTS.put("eviction.cancelled-notify", "&aThe eviction notice for claim {id} has been cancelled.");
        DEFAULTS.put("eviction.cancel-disabled", "&cCancelling eviction notices is disabled for your role.");
        DEFAULTS.put("eviction.effective", "&aEviction for {renter} is now effective.");
        DEFAULTS.put("eviction.effective-hint", "&7You can now remove them from the claim or break the rental sign.");
        DEFAULTS.put("eviction.completed-owner-notify", "&aEviction completed for claim {id}.");
        DEFAULTS.put("eviction.pending", "&eEviction notice for {renter} is pending.");
        
        // Eviction messages
        DEFAULTS.put("eviction.cannot-manage-sign", "&cYou cannot manage this sign.");
        DEFAULTS.put("eviction.active-renter", "&cThis claim has an active renter.");
        DEFAULTS.put("eviction.start-eviction-notice", "&eUse {command} to start a {duration} eviction notice. &7(New: {coords} placeholder available)");
        DEFAULTS.put("eviction.notice-pending", "&cEviction notice is still pending.");
        DEFAULTS.put("eviction.time-remaining", "&e{time} remaining before you can remove the renter.");
        DEFAULTS.put("eviction.cannot-break-sign", "&cYou cannot break this sign.");
        DEFAULTS.put("eviction.rental-sign-removed", "&aRental sign removed and rental cleared.");
        DEFAULTS.put("eviction.check-status-hint", "&7Use {command} to check status.");
        DEFAULTS.put("eviction.cannot-remove-renter", "&7You cannot break this sign or remove the renter until the eviction period has passed.");
        DEFAULTS.put("eviction.rental-expired", "&6Your rented claim has expired. &7(Location: {coords})");
        DEFAULTS.put("eviction.cannot-abandon-during-eviction", "&cYou cannot abandon this claim while an eviction is in progress.");
        DEFAULTS.put("eviction.abandon-eviction-command", "/claim evict {id}");
        DEFAULTS.put("eviction.eviction-in-progress", "&cYou cannot extend this rental - an eviction notice is in progress.");
        DEFAULTS.put("eviction.confirm-deletion", "Confirm deletion");
        DEFAULTS.put("eviction.confirm-deletion-click", "Click here to delete the rental or break the sign again while sneaking.");
        DEFAULTS.put("eviction.confirm-deletion-alt", "You can also confirm by sneak + right clicking the sign.");
        
        // Claim info messages
        DEFAULTS.put("claim.not-standing-in-claim", "You are not standing in a claim.");
        DEFAULTS.put("claim.provide-id", "Provide a claim ID.");
        DEFAULTS.put("claim.info-header", "&6=== Claim Info ===");
        DEFAULTS.put("claim.info-id", "&7ID: &e{id}");
        DEFAULTS.put("claim.info-name", "&7Name: &e{name}");
        DEFAULTS.put("claim.info-owner", "&7Owner: &e{owner}");
        DEFAULTS.put("claim.info-world", "&7World: &e{world}");
        DEFAULTS.put("claim.info-location", "&7Location: &ex{x}, z{z}");
        DEFAULTS.put("claim.info-size", "&7Size: &e{width} x {depth} ({area} blocks)");
        DEFAULTS.put("claim.info-type", "&7Type: &e{type}");
        
        // Empty line for formatting
        DEFAULTS.put("general.empty-line", "");
        
        // Synced lang.yml defaults
        DEFAULTS.put("admin.gpx-info", "&e/gpx info &7- Show plugin information");
        DEFAULTS.put("claim.ban-already", "&eClaim {id} already has a public ban.");
        DEFAULTS.put("claim.ban-public", "&aClaim {id} is now public-banned.");
        DEFAULTS.put("claim.bar-usage", "&eUsage: {usage}");
        DEFAULTS.put("claim.color-invalid", "&cUnknown color. Valid colors: &e{colors}&c, a &e#RRGGBB&c hex value, or &ereset&c.");
        DEFAULTS.put("claim.color-no-permission", "&cYou lack permission: &egriefprevention.claim.color");
        DEFAULTS.put("claim.color-usage", "&eUsage: /claim color <color|#hex|reset> [claimId]");
        DEFAULTS.put("claim.inner-subdivision-too-close", "&cThis subdivision must stay at least one block inside its parent on all sides.");
        DEFAULTS.put("claim.list-divider", "&8&m───────────────────────────");
        DEFAULTS.put("claim.list-entry-area", " &8· &7{area} blocks");
        DEFAULTS.put("claim.list-entry-coordinates", " &8· &7x{x}, z{z}");
        DEFAULTS.put("claim.list-entry-id", "&6#{id}");
        DEFAULTS.put("claim.list-entry-name", " &e{name}");
        DEFAULTS.put("claim.list-entry-parent", " &8(Child of #{parent})");
        DEFAULTS.put("claim.list-entry-prefix", "&8 • ");
        DEFAULTS.put("claim.list-entry-world", " &8· &7{world}");
        DEFAULTS.put("claim.list-hover-id", "&7Claim ID: &6{id}");
        DEFAULTS.put("claim.list-hover-teleport", "&eClick to teleport");
        DEFAULTS.put("claim.list-no-admin", "&7No admin claims found.");
        DEFAULTS.put("claim.list-page-next", "  &6Next »");
        DEFAULTS.put("claim.list-page-next-disabled", "  &8Next »");
        DEFAULTS.put("claim.list-page-next-hover", "&eGo to the next page");
        DEFAULTS.put("claim.list-page-previous", "&6« Previous  ");
        DEFAULTS.put("claim.list-page-previous-disabled", "&8« Previous  ");
        DEFAULTS.put("claim.list-page-previous-hover", "&eGo to the previous page");
        DEFAULTS.put("claim.list-subclaim-prefix", "&8   └ ");
        DEFAULTS.put("claim.list-unnamed", "Unnamed claim");
        DEFAULTS.put("claim.name-cleared", "&aClaim name cleared.");
        DEFAULTS.put("claim.name-invalid-chars", "&cClaim name contains invalid characters.");
        DEFAULTS.put("claim.name-too-long", "&cClaim name is too long (max {max} characters).");
        DEFAULTS.put("claim.resize-fail-overlap-subdivision", "&cYou can't create a subdivision here because it would overlap another subdivision. Consider /abandonclaim to delete it, or use your shovel at a corner to resize it.");
        DEFAULTS.put("claim.resize-fail-subdivision", "&cCannot resize claim: The new boundaries would exclude an existing subdivision. Please remove or move the subdivision first.");
        DEFAULTS.put("claim.resize-too-narrow", "&cThis new size would be too small. Claims must be at least &e{min} &cblocks wide.");
        DEFAULTS.put("claim.resize-too-small-area", "&cThis claim would be too small. Any claim must use at least &e{min} &ctotal claim blocks.");
        DEFAULTS.put("claim.transfer-at-limit", "&c{player} has reached their maximum claim limit.");
        DEFAULTS.put("claim.transfer-cannot-afford", "&cYou need &e{fee}&c to transfer this claim.");
        DEFAULTS.put("claim.transfer-claim-missing", "&cCould not locate this claim.");
        DEFAULTS.put("claim.transfer-failed", "&cFailed to transfer claim {id}.");
        DEFAULTS.put("claim.transfer-fee-free", "Free");
        DEFAULTS.put("claim.transfer-gui-success", "&aTransferred claim &6#{id}&a to &f{player}&a!");
        DEFAULTS.put("claim.transfer-gui-success-fee", "&aTransferred claim &6#{id}&a to &f{player}&a for &e{fee}&a!");
        DEFAULTS.put("claim.transfer-needs-blocks", "&c{player} needs &e{blocks}&c more claim blocks to receive this claim.");
        DEFAULTS.put("claim.transfer-no-economy", "&cEconomy is unavailable to process the transfer fee.");
        DEFAULTS.put("claim.transfer-not-owner", "&cOnly the claim owner can transfer ownership.");
        DEFAULTS.put("claim.transfer-player-not-found", "&cPlayer not found or has never joined: &e{player}");
        DEFAULTS.put("claim.transfer-received-from", "&a{player} transferred ownership of claim &6#{id}&a to you!");
        DEFAULTS.put("claim.transfer-self", "&cYou already own this claim.");
        DEFAULTS.put("claim.transfer-top-level-only", "&cOnly top-level claims can be transferred (not subdivisions).");
        DEFAULTS.put("claim.transfer-unavailable-disabled", "&cPlayer claim transfers are disabled on this server.");
        DEFAULTS.put("claim.transfer-unavailable-fork", "&cPlayer claim transfers require GriefPrevention3D with TransferClaim enabled.");
        DEFAULTS.put("claim.trust-anywhere-required", "&cYou need &egriefprevention.trust.anywhere &cto use a claim ID with trust commands.");
        DEFAULTS.put("claim.trust-usage", "&eUsage: /claim trust <player> [build|access|containers|manage] [claimId]");
        DEFAULTS.put("claim.trustlist-empty", "&7No players are trusted in this claim.");
        DEFAULTS.put("claim.trustlist-entry", "&7- &e{player}&7: {level}");
        DEFAULTS.put("claim.trustlist-header", "&6=== Trust List for Claim {id} ===");
        DEFAULTS.put("claim.unban-public", "&aClaim {id} is no longer public-banned.");
        DEFAULTS.put("claim.waypoint-disabled", "&cClaim waypoints are disabled on this server.");
        DEFAULTS.put("claim.waypoint-hidden", "&aClaim {id}'s waypoint is back to owner and trusted only.");
        DEFAULTS.put("claim.waypoint-limit-reached", "&cYou already publish {used}/{max} claim waypoints. Hide one first.");
        DEFAULTS.put("claim.waypoint-published", "&aClaim {id}'s waypoint is now shown to everyone.");
        DEFAULTS.put("commands.mailbox-cmd-help", "&7/mailbox help - Show this help");
        DEFAULTS.put("commands.mailbox-cmd-setup", "&7/mailbox - Start setup wizard (no claim ID needed)");
        DEFAULTS.put("commands.mailbox-commands-title", "&eCommands:");
        DEFAULTS.put("commands.mailbox-help-step1", "&71. Place a container (chest/barrel) in a claim you own or rent");
        DEFAULTS.put("commands.mailbox-help-step2", "&72. Run &e/mailbox&7 to start the setup wizard (choose self or buyable)");
        DEFAULTS.put("commands.mailbox-help-step3", "&73. Or create a [Mailbox] sign manually: line 0 &e[Mailbox]&7, line 1 empty or &ekind;amount&7 for buyable");
        DEFAULTS.put("commands.mailbox-help-step4", "&74. Put the sign on the container as a wall sign");
        DEFAULTS.put("commands.mailbox-help-title", "&6Mailbox Usage:");
        DEFAULTS.put("commands.rentclaim-cmd-hint", "&7(If no ID provided, uses current claim)");
        DEFAULTS.put("commands.rentclaim-cmd-setup", "&7/rentclaim [id] - Start rental sign setup wizard");
        DEFAULTS.put("commands.rentclaim-help-desc", "&7Creates a rental sign for your claim");
        DEFAULTS.put("commands.rentclaim-help-title", "&6Rent Claim Usage:");
        DEFAULTS.put("commands.sellclaim-cmd-hint", "&7(If no ID provided, uses current claim)");
        DEFAULTS.put("commands.sellclaim-cmd-setup", "&7/sellclaim [id] - Start sell sign setup wizard");
        DEFAULTS.put("commands.sellclaim-help-desc", "&7Creates a sell sign for your claim");
        DEFAULTS.put("commands.sellclaim-help-title", "&6Sell Claim Usage:");
        DEFAULTS.put("flags.denied", "&c{flag} is disabled in this claim.");
        DEFAULTS.put("flags.filter-mode", "&aSet &6{flag}&a filter mode to &e{mode}&a.");
        DEFAULTS.put("flags.filter-prompt", "&eEnter {kind} for &6{flag}&e (space/comma separated, prefix &f-&e to remove, &fclear&e to empty, or &fcancel&e):");
        DEFAULTS.put("flags.filter-rejected", "&eIgnored unrecognized entries: &f{entries}");
        DEFAULTS.put("flags.filter-updated", "&aUpdated &6{flag}&a filter (&e{count}&a entries).");
        DEFAULTS.put("flags.reset", "&eReset flag &6{flag}&e to its default.");
        DEFAULTS.put("flags.reset-all", "&aReset all custom flags on claim &6#{id}&a.");
        DEFAULTS.put("flags.state-allowed", "&aallowed");
        DEFAULTS.put("flags.state-denied", "&cdenied");
        DEFAULTS.put("general.error", "&cAn unexpected error occurred.");
        DEFAULTS.put("general.reload-fail", "&cFailed to reload configuration: {error}");
        DEFAULTS.put("general.unknown-player", "&cUnknown player: {player}");
        DEFAULTS.put("gui.prompt-cancelled", "&eInput cancelled.");
        DEFAULTS.put("mailbox.container-break-confirm", "&eBreak the container again while sneaking within 10 seconds to confirm removing this mailbox.");
        DEFAULTS.put("mailbox.container-evict-required", "&cThis container is a sold mailbox. Use &e/claim evict mailbox {id} &cto reclaim it.");
        DEFAULTS.put("mailbox.container-protected", "&cThis container is a protected mailbox.");
        DEFAULTS.put("mailbox.container-sneak-to-break", "&eSneak while breaking to remove your mailbox container.");
        DEFAULTS.put("mailbox.create-failed", "&cCould not create mailbox subdivision.");
        DEFAULTS.put("mailbox.nested-not-allowed", "&cMailboxes cannot be created inside another subdivision.");
        DEFAULTS.put("mailbox.purchased", "&aYou have purchased this mailbox!");
        DEFAULTS.put("market.abandon-blocked-footer", "&eClick the [EVICT] or [CANCEL] actions above before abandoning this claim.");
        DEFAULTS.put("market.abandon-blocked-header", "&cCannot abandon or delete claim &6#{id}&c — resolve active listings first:");
        DEFAULTS.put("market.already-rented", "&cThat claim is already being rented by another player.");
        DEFAULTS.put("market.banned-from-claim", "&cYou are banned from that claim.");
        DEFAULTS.put("market.cannot-buy-own", "&cYou cannot buy or rent your own listing.");
        DEFAULTS.put("market.disabled", "&cThe Claim Market is disabled on this server.");
        DEFAULTS.put("market.evict-mailbox-completed", "&aCompleted mailbox eviction for &e{id}&a (former buyer: &f{player}&a).");
        DEFAULTS.put("market.evict-mailbox-completed-notify", "&cYour mailbox &e{id}&c has been reclaimed after the eviction notice expired.");
        DEFAULTS.put("market.evict-mailbox-finalize-hint", "&aRun &e/claim evict mailbox {id}&a again to finalize removal.");
        DEFAULTS.put("market.evict-mailbox-not-sold", "&cMailbox &e{id}&c does not have an active buyer to evict.");
        DEFAULTS.put("market.evict-mailbox-notice-received", "&cYou received a {duration} eviction notice for mailbox &e{id}&c.");
        DEFAULTS.put("market.evict-mailbox-self", "&cYou own that mailbox yourself; sneak-break the sign or container to remove it.");
        DEFAULTS.put("market.evict-mailbox-usage", "&eUsage: /claim evict mailbox <mailboxId> [player]");
        DEFAULTS.put("market.evict-mailbox-wrong-player", "&cThat mailbox belongs to &e{player}&c.");
        DEFAULTS.put("market.gui-purchase-disabled", "&eDirect GUI purchases are disabled. Travel to the sign in person:");
        DEFAULTS.put("market.invalid-price", "&cInvalid price: &e{reason}");
        DEFAULTS.put("market.letter-mailbox-full", "&c{player}'s mailbox is full — letter could not be delivered.");
        DEFAULTS.put("market.letter-prompt", "&eWrite your letter to &f{player}&e in chat (or type &ccancel&e to cancel):");
        DEFAULTS.put("market.letter-sent", "&aDelivered your written letter to &f{player}&a's mailbox!");
        DEFAULTS.put("market.listing-gone", "&cThat listing's sign is no longer available.");
        DEFAULTS.put("market.mail-accepting-off", "&eMailbox &6{id}&e will no longer accept remote gifts or letters.");
        DEFAULTS.put("market.mail-accepting-on", "&aMailbox &e{id}&a is now accepting remote gifts and letters.");
        DEFAULTS.put("market.mail-disabled", "&cRemote mailbox delivery is disabled on this server.");
        DEFAULTS.put("market.mail-letters-disabled", "&cSending written letters is disabled on this server.");
        DEFAULTS.put("market.mail-not-accepting", "&cThat mailbox is not currently accepting remote gifts or letters.");
        DEFAULTS.put("market.mail-owner-remote-disabled", "&cRemote opening of your own mailbox is not permitted.");
        DEFAULTS.put("market.mail-received-gift", "&d✉ &f{player}&a deposited a gift in your mailbox (&e{id}&a)!");
        DEFAULTS.put("market.mail-received-letter", "&d✉ &f{player}&a sent a written letter to your mailbox (&e{id}&a)!");
        DEFAULTS.put("market.mail-unread-join", "&d✉ &aYou received &e{count}&a mailbox delivery(s) while offline! Check &f/claim&a -> &fMy Claims (Mailboxes)&a.");
        DEFAULTS.put("market.mailbox-evict-required", "&cThis mailbox has been purchased. Use &e{command}&c to start a mailbox eviction notice.");
        DEFAULTS.put("market.note-cleared", "&eMarket listing note cleared.");
        DEFAULTS.put("market.note-prompt", "&eType a short market tagline in chat (max {max} chars), or type &ccancel&e to cancel:");
        DEFAULTS.put("market.note-updated", "&aMarket listing note updated!");
        DEFAULTS.put("market.price-updated", "&aUpdated listing price for claim &6#{id}&a to &e{price}&a.");
        DEFAULTS.put("market.removelisting-not-found", "&cNo active for-sale listing found matching: &e{id}");
        DEFAULTS.put("market.removelisting-success", "&aRemoved market listing and sign for claim &6#{id}&a.");
        DEFAULTS.put("market.removelisting-usage", "&eUsage: /claim removelisting <listingKey|claimId>");
        DEFAULTS.put("market.teleport-not-allowed", "&cTeleportation is not enabled for this market listing.");
        DEFAULTS.put("market.teleport-toggled-off", "&eMarket preview teleport disabled for claim &6#{id}&e.");
        DEFAULTS.put("market.teleport-toggled-on", "&aMarket preview teleport enabled for claim &6#{id}&a.");
        DEFAULTS.put("market.travel-instructions", "&7Sign Location: &f{world} &7@ &ex{x}, y{y}, z{z}");
        DEFAULTS.put("market.untrust-blocked-footer", "&eUse the [EVICT] or [CANCEL] buttons above to resolve the listings first.");
        DEFAULTS.put("market.untrust-blocked-header-all", "&cCannot untrust &f{player}&c — active listings exist in your claims:");
        DEFAULTS.put("market.untrust-blocked-header-single", "&cCannot untrust &f{player}&c — they have active listings in this claim:");
        DEFAULTS.put("market.visibility-off", "&eListing for claim &6#{id}&e is now hidden from the Claim Market.");
        DEFAULTS.put("market.visibility-on", "&aListing for claim &6#{id}&a is now visible on the Claim Market.");
        DEFAULTS.put("migration.console-backup-created", "&8[&6GPX&8] &7Backup created: {backup-file}");
        DEFAULTS.put("migration.console-deprecated-file", "&8[&6GPX&8] &eDeprecated file found: {file} - This file is safe to delete.");
        DEFAULTS.put("migration.console-file-recreated", "&8[&6GPX&8] &7Recreated file: {file} with new format");
        DEFAULTS.put("migration.console-migration-complete", "&8[&6GPX&8] &aConfiguration migration completed successfully!");
        DEFAULTS.put("migration.console-migration-failed", "&8[&6GPX&8] &cConfiguration migration failed: {error}");
        DEFAULTS.put("migration.console-starting", "&8[&6GPX&8] &7Starting configuration migration from version {old} to {new}...");
        DEFAULTS.put("migration.ingame-deprecated-files", "&8[&6GPX&8] &eSome deprecated files were detected. Check console for details.");
        DEFAULTS.put("migration.ingame-migration-complete", "&8[&6GPX&8] &aConfiguration has been migrated to the new version!");
        DEFAULTS.put("permissions.use-sign-denied", "&cYou don't have permission to use {signtype} signs.");
        DEFAULTS.put("permissions.use-sign-denied-detail", "&7  Missing: &e{permission}");
        DEFAULTS.put("sign-creation.claim-already-for-sale", "&cThis claim is already for sale.");
        DEFAULTS.put("sign-creation.claim-already-rented", "&cThis claim is already being rented.");
        DEFAULTS.put("sign-creation.gp3d-required", "&cMailbox signs require GP3D (GriefPrevention 3D) to be installed.");
        DEFAULTS.put("sign-creation.invalid-format", "&cInvalid sign format. {details}");
        DEFAULTS.put("sign-creation.mailbox-must-be-subdivision", "&cMailbox signs must reference a 3D subdivision.");
        DEFAULTS.put("sign-creation.mailbox-sign-created", "&aMailbox sign created successfully!");
        DEFAULTS.put("sign-creation.mailbox-wrong-size", "&cMailbox signs must reference a 1x1x1 subdivision (current: {width}x{height}x{depth}).");
        DEFAULTS.put("sign-creation.not-claim-owner", "&cYou must be the owner of this claim to create this sign.");
        DEFAULTS.put("sign-creation.not-in-claim", "&cThis sign must be placed inside a claim.");
        DEFAULTS.put("sign-creation.rent-sign-created", "&aRent sign created successfully!");
        DEFAULTS.put("sign-creation.sell-sign-created", "&aSell sign created successfully!");
        DEFAULTS.put("sign-creation.unsafe-location", "&cThis location is not safe for a spawn point. Please place the sign in a safer area.");
        DEFAULTS.put("sign-destruction.cannot-destroy-active-rental", "&cYou cannot destroy this sign while the claim is being rented.");
        DEFAULTS.put("sign-destruction.cannot-destroy-not-owner", "&cYou cannot destroy this sign - you are not the claim owner.");
        DEFAULTS.put("sign-destruction.confirm-destroy-mailbox", "&eAre you sure you want to destroy this mailbox sign? Type &6/confirm&e to confirm.");
        DEFAULTS.put("sign-destruction.confirm-destroy-rent", "&eAre you sure you want to destroy this rent sign? Type &6/confirm&e to confirm.");
        DEFAULTS.put("sign-destruction.confirm-destroy-sell", "&eAre you sure you want to destroy this sell sign? Type &6/confirm&e to confirm.");
        DEFAULTS.put("sign-destruction.mailbox-sign-destroyed", "&aMailbox sign destroyed.");
        DEFAULTS.put("sign-destruction.rent-sign-destroyed", "&aRent sign destroyed.");
        DEFAULTS.put("sign-destruction.sell-sign-destroyed", "&aSell sign destroyed.");
        DEFAULTS.put("sign-interaction.buy-already-owner", "&cYou already own this claim.");
        DEFAULTS.put("sign-interaction.buy-cannot-afford", "&cYou cannot afford this claim. Cost: {cost}");
        DEFAULTS.put("sign-interaction.buy-claim-unavailable", "&cThis claim is not available for purchase.");
        DEFAULTS.put("sign-interaction.buy-success", "&aCongratulations! You now own this claim!");
        DEFAULTS.put("sign-interaction.mailbox-collected", "&aCollected {amount} from the mailbox!");
        DEFAULTS.put("sign-interaction.mailbox-deposit-fail", "&cFailed to deposit: {reason}");
        DEFAULTS.put("sign-interaction.mailbox-deposit-success", "&aDeposited {amount} into the mailbox!");
        DEFAULTS.put("sign-interaction.mailbox-empty", "&eThe mailbox is empty.");
        DEFAULTS.put("sign-interaction.rent-already-renting", "&cYou are already renting this claim.");
        DEFAULTS.put("sign-interaction.rent-cannot-afford", "&cYou cannot afford this rental. Cost: {cost}");
        DEFAULTS.put("sign-interaction.rent-claim-unavailable", "&cThis claim is not available for rent.");
        DEFAULTS.put("sign-interaction.rent-extended", "&aRental extended by {duration}. Total: {total}");
        DEFAULTS.put("sign-interaction.rent-max-reached", "&eYou have reached the maximum rental duration.");
        DEFAULTS.put("sign-interaction.snapshot-restoring", "&eSnapshot is restoring, please wait a few seconds...");
        DEFAULTS.put("sign-move.cancelled", "&eSign moving cancelled.");
        DEFAULTS.put("sign-move.destination-failed", "&cThe destination sign could not be placed. Try placing another sign.");
        DEFAULTS.put("sign-move.expired", "&eSign moving timed out. Run &6/moveclaimsign &eagain.");
        DEFAULTS.put("sign-move.failed", "&cThe sign could not be prepared for moving.");
        DEFAULTS.put("sign-move.no-permission", "&cYou cannot move that sign. Missing permission: &e{permission}");
        DEFAULTS.put("sign-move.not-managed", "&cThat is not a GPExpansion rent, sell, mailbox, global, or self-mailbox sign.");
        DEFAULTS.put("sign-move.nothing-to-cancel", "&7You are not currently moving a sign.");
        DEFAULTS.put("sign-move.place-destination", "&aSelected the {type} sign. &ePlace another sign where it should move.");
        DEFAULTS.put("sign-move.same-location", "&cPlace the destination sign at a different location.");
        DEFAULTS.put("sign-move.select-source", "&eRight-click the managed sign you want to move. &7Use &6/moveclaimsign cancel &7to cancel.");
        DEFAULTS.put("sign-move.select-source-sign", "&cThat is not a sign. Right-click the managed sign you want to move.");
        DEFAULTS.put("sign-move.success", "&aMoved the {type} sign. The old sign has been cleared.");
        DEFAULTS.put("sign-protection.confirm-alternative", "&7You can also confirm by &6sneak + right clicking &7the sign.");
        DEFAULTS.put("sign-protection.renter-warning", "&eWarning: &f{renter} &eis currently renting this from you. Try &n/claim evict {renter}&e while standing in the claim to start the eviction process.");
        DEFAULTS.put("sign-protection.renter-warning-hover", "Click to run /claim evict {renter}");
        DEFAULTS.put("sign-protection.sneak-to-manage", "&eSneak and break to manage this sign.");
        DEFAULTS.put("sign-protection.trust-removal-warning", "&eWarning: trust will be removed for &6{player}");
        DEFAULTS.put("snapshot.claim-too-large", "&cThis claim is too large to snapshot. Maximum 128 blocks on each axis (X, Y, Z). Subdivide or use a smaller claim.");
        DEFAULTS.put("snapshot.create-failed", "&cFailed to create snapshot.");
        DEFAULTS.put("snapshot.created", "&aSnapshot created: &e{id}&a.");
        DEFAULTS.put("snapshot.list-empty", "&eNo snapshots found. This claim will not be restored on eviction.");
        DEFAULTS.put("snapshot.list-entry", "&7- &e{id} &7({date})");
        DEFAULTS.put("snapshot.list-header", "&eSnapshots ({count}):");
        DEFAULTS.put("snapshot.not-available-for-rent", "&cThis claim is currently rented. Snapshots can only be created for claims available for rent.");
        DEFAULTS.put("snapshot.remove-failed", "&cFailed to remove snapshot.");
        DEFAULTS.put("snapshot.remove-usage", "&cUsage: /claim snapshot remove <snapshotId>");
        DEFAULTS.put("snapshot.removed", "&aSnapshot removed: &e{id}&a.");
        DEFAULTS.put("snapshot.restore-prompt", "&eYou have snapshot restore enabled. Run &6/claim snapshot create {claimId}&e to save the claim state before listing it for rent.");
        DEFAULTS.put("snapshot.restored-on-eviction", "&aClaim restored from snapshot on eviction.");
        DEFAULTS.put("snapshot.usage", "&eUsage: /claim snapshot [list|remove|create] [claimId|all]. &7list = current claim, list <id> = that claim, list all = all claims.");
        DEFAULTS.put("update-checker.console-check-failed", "&8[&6GPX&8] &cFailed to check for updates: {error}");
        DEFAULTS.put("update-checker.console-checking", "&8[&6GPX&8] &7Checking for updates on SpigotMC...");
        DEFAULTS.put("update-checker.console-download-link", "&8[&6GPX&8] &7Download: {url}");
        DEFAULTS.put("update-checker.console-up-to-date", "&8[&6GPX&8] &aYou are running the latest version!");
        DEFAULTS.put("update-checker.console-update-available", "&8[&6GPX&8] &eUpdate available! Current: {current}, Latest: {latest}");
        DEFAULTS.put("update-checker.ingame-check-failed", "&8[&6GPX&8] &cFailed to check for updates");
        DEFAULTS.put("update-checker.ingame-download-link", "&8[&6GPX&8] &7Download at: &e&n{url}");
        DEFAULTS.put("update-checker.ingame-update-available", "&8[&6GPX&8] &e&lUPDATE AVAILABLE!&r &7Current: {current}, Latest: {latest}");
        DEFAULTS.put("wizard.already-in-wizard", "&cYou are already in a setup wizard. Use &e/cancelsetup&c to cancel.");
        DEFAULTS.put("wizard.auto-paste-expired", "&eAuto-paste mode has expired. Run the wizard again if needed.");
        DEFAULTS.put("wizard.mailbox-complete", "&a✓ Mailbox sign format ready!\n&7The next sign you place will be automatically configured.\n&7Type &e/cancelsetup&7 to cancel auto-paste mode.");
        DEFAULTS.put("wizard.mailbox-enter-amount", "&eWhat's the deposit amount?");
        DEFAULTS.put("wizard.rent-complete", "&a✓ Rent sign format ready!\n&7The next sign you place will be automatically configured.\n&7Type &e/cancelsetup&7 to cancel auto-paste mode.");
        DEFAULTS.put("wizard.rent-enter-amount", "&eWhat's the price per rental period?");
        DEFAULTS.put("wizard.rent-enter-duration", "&eHow long should each rental period be?");
        DEFAULTS.put("wizard.rent-enter-duration-hint", "&7Format: &e<number><s/m/h/d/w>&7 (e.g., &e1d&7 for 1 day, &e1w&7 for 1 week)");
        DEFAULTS.put("wizard.rent-enter-max-duration", "&eWhat's the maximum total rental time?");
        DEFAULTS.put("wizard.rent-enter-max-duration-hint", "&7Format: &e<number><s/m/h/d/w>&7 (e.g., &e30d&7 for 30 days, &e12w&7 for 12 weeks)");
        DEFAULTS.put("wizard.sell-complete", "&a✓ Sell sign format ready!\n&7The next sign you place will be automatically configured.\n&7Type &e/cancelsetup&7 to cancel auto-paste mode.");
        DEFAULTS.put("wizard.sell-enter-amount", "&eWhat's the sale price?");
        DEFAULTS.put("wizard.timeout", "&cSetup wizard timed out.");

        // Version for migration tracking
        DEFAULTS.put("version", "0.1.3a");
    }
    
    public Messages(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        loadLanguageFile();
    }
    
    /**
     * Load or reload the language file.
     */
    public void loadLanguageFile() {
        messageCache.clear();
        
        langFile = new File(plugin.getDataFolder(), "lang.yml");
        
        if (!langFile.exists()) {
            plugin.saveResource("lang.yml", false);
        }
        
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // If no version key exists, treat as legacy and replace with fresh lang.yml
        if (!langConfig.contains("version")) {
            File backup = new File(plugin.getDataFolder(), "lang.yml.backup");
            if (backup.exists()) {
                backup = new File(plugin.getDataFolder(), "lang.yml.backup." + System.currentTimeMillis());
            }
            if (langFile.renameTo(backup)) {
                plugin.getLogger().warning("Detected legacy lang.yml without version. Backed up to " + backup.getName());
            } else {
                plugin.getLogger().warning("Detected legacy lang.yml without version. Failed to back it up.");
            }
            
            plugin.saveResource("lang.yml", false);
            langConfig = YamlConfiguration.loadConfiguration(langFile);
        }
        
        // Load defaults from jar
        InputStream defaultStream = plugin.getResource("lang.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultStream, StandardCharsets.UTF_8)
            );
            langConfig.setDefaults(defaultConfig);
        }
        
        // Add any missing keys from DEFAULTS to the user's lang.yml
        addMissingKeys();
        
        // Add any missing keys from the JAR resource defaults (covers keys not in DEFAULTS)
        ensureDefaultsInFile();
        
        // Check version and handle migrations
        checkAndMigrateVersion();
    }
    
    /**
     * Check for missing keys in the lang.yml and add them from DEFAULTS.
     * This allows new messages to be automatically added without users deleting their config.
     */
    private void addMissingKeys() {
        boolean modified = false;
        
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            String key = entry.getKey();
            if (!langConfig.isSet(key)) {
                langConfig.set(key, entry.getValue());
                modified = true;
                plugin.getLogger().info("Added missing lang key: " + key);
            }
        }
        
        if (modified) {
            saveLanguageFile();
            plugin.getLogger().info("Updated lang.yml with missing keys.");
        }
    }
    
    /**
     * Add keys present in the JAR resource defaults but missing from the user's lang.yml.
     * This ensures the user's file stays complete even when new keys are added to the resource.
     */
    private void ensureDefaultsInFile() {
        ConfigurationSection defaults = langConfig.getDefaults();
        if (defaults == null) return;
        
        boolean modified = false;
        for (String key : defaults.getKeys(true)) {
            Object val = defaults.get(key);
            if (val instanceof String && !langConfig.isSet(key)) {
                langConfig.set(key, val.toString());
                modified = true;
                plugin.getLogger().info("Added missing lang key from resource: " + key);
            }
        }
        
        if (modified) {
            saveLanguageFile();
            plugin.getLogger().info("Updated lang.yml with missing keys from resource defaults.");
        }
    }
    
    /**
     * Check the lang file version and handle migrations for older versions.
     */
    private void checkAndMigrateVersion() {
        String langVersion = langConfig.getString("version", "0.1.2a");
        
        // If version is older than 0.1.3a, add upgrade notices
        if (isOlderVersion(langVersion, "0.1.3a")) {
            boolean modified = false;
            
            // Add upgrade notices to eviction and expiry messages if they don't have {coords}
            String startEvictionNotice = langConfig.getString("eviction.start-eviction-notice");
            if (startEvictionNotice != null && !startEvictionNotice.contains("{coords}")) {
                langConfig.set("eviction.start-eviction-notice", startEvictionNotice + " &7(New: {coords} placeholder available)");
                modified = true;
                plugin.getLogger().info("Added {coords} placeholder notice to eviction.start-eviction-notice");
            }
            
            String rentalExpired = langConfig.getString("eviction.rental-expired");
            if (rentalExpired != null && !rentalExpired.contains("{coords}")) {
                langConfig.set("eviction.rental-expired", rentalExpired + " &7(Location: {coords})");
                modified = true;
                plugin.getLogger().info("Added {coords} placeholder notice to eviction.rental-expired");
            }
            
            // Update version to current
            langConfig.set("version", "0.1.3a");
            
            if (modified) {
                saveLanguageFile();
                plugin.getLogger().info("Updated lang.yml to version 0.1.3a with {coords} placeholder support.");
            }
        }
        
        // Migrate to 1.1.3: permissions.anywhere-denied was split into sign-anywhere-denied
        // (sign context) and command-anywhere-denied (command context). Copy any custom
        // legacy text forward, then drop the old key so it is never referenced again.
        String legacyAnywhereDenied = langConfig.getString("permissions.anywhere-denied");
        if (isOlderVersion(langVersion, "1.1.3")) {
            boolean modified = false;
            
            if (!langConfig.isSet("permissions.sign-anywhere-denied") && legacyAnywhereDenied != null) {
                langConfig.set("permissions.sign-anywhere-denied", legacyAnywhereDenied);
                modified = true;
                plugin.getLogger().info("Migrated permissions.anywhere-denied to permissions.sign-anywhere-denied");
            }
            
            if (!langConfig.isSet("permissions.command-anywhere-denied")) {
                langConfig.set("permissions.command-anywhere-denied", DEFAULTS.get("permissions.command-anywhere-denied"));
                modified = true;
            }
            
            if (!langConfig.isSet("claim.abandon-subdivision-warning")) {
                langConfig.set("claim.abandon-subdivision-warning", DEFAULTS.get("claim.abandon-subdivision-warning"));
                modified = true;
            }
            
            langConfig.set("version", "1.1.3");
            if (modified) {
                saveLanguageFile();
                plugin.getLogger().info("Updated lang.yml to version 1.1.3 (sign/command anywhere-denied message keys).");
            }
        }
        
        // Remove the legacy permissions.anywhere-denied key whenever it is still present.
        if (langConfig.contains("permissions.anywhere-denied")) {
            langConfig.set("permissions.anywhere-denied", null);
            saveLanguageFile();
            plugin.getLogger().info("Removed deprecated lang key permissions.anywhere-denied.");
        }
        
        // Auto-bump version if no migrations needed and version is older than project version
        String projectVersion = getProjectVersion();
        String currentLangVersion = langConfig.getString("version", "0.1.3a");
        if (isOlderVersion(currentLangVersion, projectVersion)) {
            autoBumpLangVersion(projectVersion);
        }
        
        currentVersion = langConfig.getString("version", "0.1.3a");
    }
    
    /**
     * Get the project version from plugin.yml
     * Falls back to reading from pom.properties or hardcoded version
     */
    @SuppressWarnings("deprecation")
    private String getProjectVersion() {
        String version = plugin.getDescription().getVersion();
        // Remove any ${project.version} placeholders if present
        if (version.contains("${") || version.isEmpty()) {
            // Try to read from Maven properties file
            try {
                java.io.InputStream is = plugin.getClass().getClassLoader().getResourceAsStream("META-INF/maven/codes.castled.gpexpansion/gpexpansion/pom.properties");
                if (is != null) {
                    java.util.Properties props = new java.util.Properties();
                    props.load(is);
                    version = props.getProperty("version", "0.1.8a");
                    is.close();
                } else {
                    version = "0.1.8a"; // Fallback to current version
                }
            } catch (Exception e) {
                version = "0.1.8a"; // Fallback to current version
            }
        }
        return version;
    }
    
    /**
     * Auto-bump lang version to project version (when no migrations are needed)
     */
    private void autoBumpLangVersion(String targetVersion) {
        currentVersion = langConfig.getString("version", "0.1.3a");

        if (isOlderVersion(currentVersion, targetVersion)) {
            langConfig.set("version", targetVersion);
            saveLanguageFile();
            plugin.getLogger().info("Messages: Auto-bumped lang version from " + currentVersion + " to " + targetVersion + " (no migrations needed)");
        }
    }
    
    /**
     * Compare version strings to check if installed version is older than target version.
     */
    private boolean isOlderVersion(String current, String target) {
        // Simple version comparison for X.Y.Za format
        String[] currentParts = current.replaceAll("[^0-9.]", "").split("\\.");
        String[] targetParts = target.replaceAll("[^0-9.]", "").split("\\.");
        
        for (int i = 0; i < Math.max(currentParts.length, targetParts.length); i++) {
            int currentNum = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            int targetNum = i < targetParts.length ? Integer.parseInt(targetParts[i]) : 0;
            
            if (currentNum < targetNum) return true;
            if (currentNum > targetNum) return false;
        }
        return false; // Same version or newer
    }
    
    /**
     * Save any changes to the language file.
     */
    public void saveLanguageFile() {
        try {
            langConfig.save(langFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save lang.yml: " + e.getMessage());
        }
    }
    
    /**
     * Get a raw message string from the language file.
     * Uses caching for performance. Falls back to hardcoded defaults if missing.
     */
    public String getRaw(String path) {
        if (messageCache.containsKey(path)) {
            return messageCache.get(path);
        }
        
        String message = langConfig.getString(path);
        if (message == null) {
            // Try hardcoded fallback
            message = DEFAULTS.get(path);
        }
        if (message == null) {
            // Ultimate fallback - show path for debugging
            message = "&7[" + path + "]";
        }
        
        messageCache.put(path, message);
        return message;
    }
    
    /**
     * Get a raw message with a specific fallback if not found.
     */
    public String getRaw(String path, String fallback) {
        if (messageCache.containsKey(path)) {
            return messageCache.get(path);
        }
        
        String message = langConfig.getString(path);
        if (message == null) {
            message = DEFAULTS.get(path);
        }
        if (message == null) {
            message = fallback;
        }
        
        messageCache.put(path, message);
        return message;
    }
    
    /**
     * Get a message string with placeholders replaced.
     */
        public String getRaw(String path, String... replacements) {
        String message = getRaw(path);
        
        // Replace placeholders in pairs: {key}, value, {key2}, value2, etc.
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = replacements[i];
            String value = replacements[i + 1];
            message = message.replace(placeholder, value);
        }
        
        return message;
    }
    
    public int getMessageCount() {
        return DEFAULTS.size();
    }
    
    public String getLangVersion() {
        return currentVersion;
    }
    
    /**
     * Get a message as a Component with color codes parsed.
     */
    public Component get(String path) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(getRaw(path));
    }
    
    /**
     * Get a message as a Component with placeholders replaced.
     */
    public @Nullable @NotNull TextComponent get(String path, String... replacements) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(getRaw(path, replacements));
    }
    
    /**
     * Send a message to a player.
     */
    public void send(Player player, String path) {
        Mini.send(player, get(path));
    }
    
    /**
     * Send a message to a player with placeholders.
     */
    public void send(Player player, String path, String... replacements) {
        Mini.send(player, get(path, replacements));
    }
    
    /**
     * Send a prefixed message to a player.
     */
    public void sendPrefixed(Player player, String path) {
        String prefix = getRaw("general.prefix");
        String message = getRaw(path);
        Mini.send(player, LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + message));
    }
    
    /**
     * Send a prefixed message to a player with placeholders.
     */
    public void sendPrefixed(Player player, String path, String... replacements) {
        String prefix = getRaw("general.prefix");
        String message = getRaw(path, replacements);
        Mini.send(player, LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + message));
    }
    
    /**
     * Check if a message path exists.
     */
    public boolean hasMessage(String path) {
        return langConfig.contains(path);
    }
    
    /**
     * Get the underlying configuration for advanced access.
     */
    public FileConfiguration getConfig() {
        return langConfig;
    }
    
    // =========================================================================
    // CONVENIENCE METHODS FOR COMMON MESSAGES
    // =========================================================================
    
    /**
     * Send economy type permission denied message.
     */
    public void sendEconomyPermissionDenied(Player player, String economy, String signType, String permission) {
        send(player, "permissions.economy-type-denied", 
            "{economy}", economy,
            "{signtype}", signType);
        
        if (plugin.getConfig().getBoolean("messages.show-permission-details", true)) {
            send(player, "permissions.economy-type-denied-detail",
                "{permission}", permission);
        }
    }
    
    /**
     * Send sign creation permission denied message.
     */
    public void sendCreatePermissionDenied(Player player, String signType, String permission) {
        send(player, "permissions.create-sign-denied",
            "{signtype}", signType);
        
        if (plugin.getConfig().getBoolean("messages.show-permission-details", true)) {
            send(player, "permissions.create-sign-denied-detail",
                "{permission}", permission);
        }
    }
    
    /**
     * Send "command can only be run in your own claims" message.
     */
    public void sendCommandAnywhereDenied(Player player, String permission) {
        send(player, "permissions.command-anywhere-denied");
        
        if (plugin.getConfig().getBoolean("messages.show-permission-details", true)) {
            send(player, "permissions.anywhere-denied-detail",
                "{permission}", permission);
        }
    }
    
    /**
     * Send sign limit reached message.
     */
    public void sendLimitReached(Player player, String signType, int current, int max) {
        String path = "sign-creation." + signType.toLowerCase() + "-limit-reached";
        send(player, path,
            "{current}", String.valueOf(current),
            "{max}", String.valueOf(max));
    }
    
    /**
     * Send wizard step message.
     */
    public void sendWizardMessage(Player player, String wizardType, String step) {
        String path = "wizard." + wizardType + "-" + step;
        send(player, path);
    }
}
