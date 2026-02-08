package dev.towki.gpexpansion.util;

import dev.towki.gpexpansion.GPExpansionPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

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
        
        // Permissions
        DEFAULTS.put("permissions.economy-type-denied", "&c✖ You don't have permission to use &6{economy}&c economy for {signtype} signs.");
        DEFAULTS.put("permissions.economy-type-denied-detail", "&7  Missing: &e{permission}");
        DEFAULTS.put("permissions.create-sign-denied", "&cYou don't have permission to create {signtype} signs.");
        DEFAULTS.put("permissions.create-sign-denied-detail", "&7  Missing: &e{permission}");
        
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
        DEFAULTS.put("claim.player-not-found", "&cPlayer not found: &6{player}");
        DEFAULTS.put("claim.setspawn-not-in-claim", "&cYou must be standing inside a claim to set its spawn point.");
        DEFAULTS.put("claim.setspawn-not-owner", "&cYou must be the owner of this claim to set its spawn point.");
        DEFAULTS.put("claim.setspawn-success", "&aSpawn point set for claim &6{id}&a.");
        DEFAULTS.put("claim.setspawn-error", "&cAn error occurred while setting the spawn point.");
        
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
        DEFAULTS.put("claim.global-not-in-claim", "&cYou must be standing in a claim or provide a claim ID.");
        DEFAULTS.put("claim.global-not-owner", "&cYou must own this claim to toggle its global listing.");
        DEFAULTS.put("claim.global-limit-reached", "&cYou have reached your global claim limit! &7({current}/{max})");
        
        // Wizard - General
        DEFAULTS.put("wizard.cancelled", "&cSetup wizard cancelled.");
        DEFAULTS.put("wizard.previous-cancelled", "&7(Previous setup wizard cancelled)");
        DEFAULTS.put("wizard.claim-not-found", "&cClaim ID not found: {id}");
        DEFAULTS.put("wizard.not-claim-owner", "&cYou don't own that claim!");
        DEFAULTS.put("wizard.invalid-claim-id", "&cInvalid claim ID. Please enter a number.\n&7(Type 'cancel' to exit the wizard)");
        DEFAULTS.put("wizard.invalid-duration", "&cInvalid duration format. Use: &e<number><s/m/h/d/w>\n&7Examples: &e30s&7, &e1h&7, &e7d&7, &e1w");
        DEFAULTS.put("wizard.invalid-economy-type", "&cInvalid economy type.\n&7Valid options: &emoney&7, &eexp&7, &eclaimblocks&7, &eitem");
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
        
        // Wizard - Sell
        DEFAULTS.put("wizard.sell-start", "&aSelling claim &6{id}&a...");
        DEFAULTS.put("wizard.sell-start-no-claim", "&a&l=== Sell Claim Sign Setup ===");
        DEFAULTS.put("wizard.sell-enter-claim-id", "&eEnter the claim ID:");
        DEFAULTS.put("wizard.sell-enter-claim-id-hint", "&7(Quick tip: do &6/claimlist&7 to view your claim IDs)");
        
        // Wizard - Mailbox
        DEFAULTS.put("wizard.mailbox-start", "&aSetting up mailbox for claim &6{id}&a...");
        DEFAULTS.put("wizard.mailbox-start-no-claim", "&a&l=== Mailbox Sign Setup ===");
        DEFAULTS.put("wizard.mailbox-choose-type", "&eSelf mailbox or buyable? Type &aself&e or &abuyable&e.");
        DEFAULTS.put("wizard.mailbox-self-or-buyable-hint", "&7Self = instant mailbox for you (place [Mailbox] on container). Buyable = others pay to get the mailbox.");
        DEFAULTS.put("wizard.mailbox-enter-claim-id", "&eEnter the claim ID:");
        DEFAULTS.put("wizard.mailbox-enter-claim-id-hint", "&7(Quick tip: do &6/claimlist&7 to view your claim IDs)");
        
        // Wizard - Auto-paste
        DEFAULTS.put("wizard.auto-paste-ready", "&a✓ Sign format loaded! Edit if needed, then click Done.");
        DEFAULTS.put("wizard.auto-paste-item-reminder", "&e⚠ Hold the payment item in your offhand when placing the sign!");
        DEFAULTS.put("wizard.auto-paste-cancelled", "&cAuto-paste mode cancelled.");
        
        // Wizard - Step prompts
        DEFAULTS.put("wizard.step-prompt", "&aStep {step}: {prompt}");
        DEFAULTS.put("wizard.cancel-hint", "&8(Type 'cancel' at any time to exit)");
        
        // Claim
        DEFAULTS.put("claim.list-header", "&eClaims ({count}):");
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
        DEFAULTS.put("claim.transfer-usage", "&eUsage: /claim transfer <claimId> <player>");
        DEFAULTS.put("claim.transfer-failed-contact", "&cFailed to transfer claim ownership. Please contact an admin.");
        DEFAULTS.put("claim.name-usage", "&eUsage: /claim name <newName> [claimId]");
        DEFAULTS.put("claim.icon-no-permission", "&cYou lack permission: &egriefprevention.claim.icon");
        DEFAULTS.put("claim.icon-hold-item", "&cYou must hold an item to set as the claim icon.");
        DEFAULTS.put("claim.icon-set", "&aClaim {id} icon set to {icon}.");
        DEFAULTS.put("claim.description-no-permission", "&cYou lack permission: &egriefprevention.claim.description");
        DEFAULTS.put("claim.description-usage", "&eUsage: /claim desc <description...> [claimId]");
        DEFAULTS.put("claim.description-truncated", "&eDescription truncated to {max} characters.");
        DEFAULTS.put("claim.description-set", "&aClaim {id} description set to: {description}");
        DEFAULTS.put("claim.ban-usage", "&eUsage: /claim ban <player|public> [claimId]");
        DEFAULTS.put("claim.ban-no-permission", "&cYou lack permission: &egriefprevention.claim.ban");
        DEFAULTS.put("claim.ban-self", "&cYou cannot ban yourself.");
        DEFAULTS.put("claim.unban-usage", "&eUsage: /claim unban <player|public> [claimId]");
        DEFAULTS.put("claim.unban-no-permission", "&cYou lack permission: &egriefprevention.claim.unban");
        DEFAULTS.put("claim.unban-public-missing", "&eClaim {id} is not public-banned.");
        DEFAULTS.put("claim.unban-not-banned", "&e{player} is not banned from claim {id}.");
        DEFAULTS.put("claim.banlist-header", "&eBan list for claim {id}:");
        DEFAULTS.put("claim.banlist-public", "&6 - Public banned");
        DEFAULTS.put("claim.banlist-empty", "&7 - No players banned");
        DEFAULTS.put("claim.banlist-entry", "&c - {player}");
        DEFAULTS.put("claim.evict-usage", "&eUsage: /claim evict [claimId]");
        DEFAULTS.put("claim.evict-help", "&7Starts a {days}-day eviction notice for the renter.");
        DEFAULTS.put("claim.evict-no-permission", "&cYou lack permission: &egriefprevention.evict");
        DEFAULTS.put("claim.not-rented", "&cThis claim is not currently rented.");
        DEFAULTS.put("claim.rental-sign-confirm-usage", "&eUsage: /claim rentalsignconfirm <world> <x> <y> <z>");
        DEFAULTS.put("claim.coords-must-be-int", "&cCoordinates must be integers.");
        DEFAULTS.put("claim.world-unknown", "&cUnknown world: {world}");
        DEFAULTS.put("claim.sign-not-found", "&cNo managed sign found at that location.");
        DEFAULTS.put("claim.sign-not-managed", "&cThat sign is not managed by GPExpansion.");
        DEFAULTS.put("claim.sign-use-denied", "&cYou don't have permission to use this sign.");
        DEFAULTS.put("claim.pending-rent-none", "&eYou have no pending rental payments to collect.");
        DEFAULTS.put("claim.pending-rent-failed-money", "&cFailed to give you ${amount} from rentals.");
        DEFAULTS.put("claim.pending-rent-claimblocks", "&aYou received {amount} claim blocks from rentals!");
        DEFAULTS.put("claim.pending-rent-collected", "&aSuccessfully collected all pending rental payments!");
        DEFAULTS.put("claim.teleport-safe-location-fail", "&cCould not compute a safe location in claim {id}.");
        DEFAULTS.put("claim.untrust-renter", "&cYou cannot untrust {renter} while they are renting your claim.");
        DEFAULTS.put("claim.untrust-renter-hint", "&eUse {command} instead.");
        
        // Admin
        DEFAULTS.put("admin.gpx-help-header", "&6=== GPExpansion Admin Commands ===");
        DEFAULTS.put("admin.gpx-reload", "&e/gpx reload &7- Reload config and language files");
        DEFAULTS.put("admin.gpx-debug", "&e/gpx debug &7- Toggle debug mode");
        DEFAULTS.put("admin.gpx-max", "&e/gpx max &7- Modify player creation limits.");
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
        DEFAULTS.put("sign-creation.item-setup-reminder", "&eHold the payment item in your offhand and right-click the sign to set it.");
        DEFAULTS.put("sign-interaction.rent-own-claim", "&cYou cannot rent your own claim.");
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
        DEFAULTS.put("eviction.notice-duration", "&eThey have {days} days before you can remove them from the claim or break the sign.");
        DEFAULTS.put("eviction.notice-no-extend", "&7During this time, the renter cannot extend their rental.");
        DEFAULTS.put("eviction.notice-received", "&cYou have received an eviction notice for claim {id}.");
        DEFAULTS.put("eviction.notice-days", "&eYou have {days} days before you will be removed from this claim.");
        DEFAULTS.put("eviction.no-pending", "&cThere is no pending eviction for this claim.");
        DEFAULTS.put("eviction.no-pending-info", "&7There is no pending eviction for this claim.");
        DEFAULTS.put("eviction.cancelled", "&aEviction cancelled for claim {id}.");
        DEFAULTS.put("eviction.cancelled-notify", "&aThe eviction notice for claim {id} has been cancelled.");
        DEFAULTS.put("eviction.effective", "&aEviction for {renter} is now effective.");
        DEFAULTS.put("eviction.effective-hint", "&7You can now remove them from the claim or break the rental sign.");
        DEFAULTS.put("eviction.pending", "&eEviction notice for {renter} is pending.");
        
        // Eviction messages
        DEFAULTS.put("eviction.cannot-manage-sign", "&cYou cannot manage this sign.");
        DEFAULTS.put("eviction.active-renter", "&cThis claim has an active renter.");
        DEFAULTS.put("eviction.start-eviction-notice", "&eUse {command} to start a {days}-day eviction notice. &7(New: {coords} placeholder available)");
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
        
        // Empty line for formatting
        DEFAULTS.put("general.empty-line", "");
        
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
            if (!langConfig.contains(key)) {
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
    private String getProjectVersion() {
        String version = plugin.getDescription().getVersion();
        // Remove any ${project.version} placeholders if present
        if (version.contains("${") || version.isEmpty()) {
            // Try to read from Maven properties file
            try {
                java.io.InputStream is = plugin.getClass().getClassLoader().getResourceAsStream("META-INF/maven/dev.towki.gpexpansion/gpexpansion/pom.properties");
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
        String currentVersion = langConfig.getString("version", "0.1.3a");
        
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
    
    /**
     * Get a message as a Component with color codes parsed.
     */
    public Component get(String path) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(getRaw(path));
    }
    
    /**
     * Get a message as a Component with placeholders replaced.
     */
    public Component get(String path, String... replacements) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(getRaw(path, replacements));
    }
    
    /**
     * Send a message to a player.
     */
    public void send(Player player, String path) {
        player.sendMessage(get(path));
    }
    
    /**
     * Send a message to a player with placeholders.
     */
    public void send(Player player, String path, String... replacements) {
        player.sendMessage(get(path, replacements));
    }
    
    /**
     * Send a prefixed message to a player.
     */
    public void sendPrefixed(Player player, String path) {
        String prefix = getRaw("general.prefix");
        String message = getRaw(path);
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + message));
    }
    
    /**
     * Send a prefixed message to a player with placeholders.
     */
    public void sendPrefixed(Player player, String path, String... replacements) {
        String prefix = getRaw("general.prefix");
        String message = getRaw(path, replacements);
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + message));
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
