package dev.towki.gpexpansion.setup;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.scheduler.SchedulerAdapter;
import dev.towki.gpexpansion.setup.SetupSession.SetupStep;
import dev.towki.gpexpansion.setup.SetupSession.SetupType;
import dev.towki.gpexpansion.util.EcoKind;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Manages setup wizard sessions for rent/sell/mailbox sign creation.
 */
public class SetupWizardManager {
    
    private final GPExpansionPlugin plugin;
    private final GPBridge gp;
    private final Map<UUID, SetupSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSignData> pendingAutoPaste = new ConcurrentHashMap<>();
    
    // Duration pattern: number followed by s/m/h/d/w
    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d+[smhdw]$", Pattern.CASE_INSENSITIVE);
    // Number pattern for prices
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^\\d+(\\.\\d+)?[Ll]?$");
    
    /**
     * Holds data for auto-pasting sign format when player places a sign.
     */
    public static class PendingSignData {
        public final SetupType type;
        public final String claimId;
        public final String renewalTime;
        public final String maxTime;
        public final EcoKind ecoKind;
        public final String price;
        
        public PendingSignData(SetupSession session) {
            this.type = session.getType();
            this.claimId = session.getClaimId();
            this.renewalTime = session.getRenewalTime();
            this.maxTime = session.getMaxTime();
            this.ecoKind = session.getEcoKind();
            this.price = session.getPrice();
        }
        
        public String[] getSignLines() {
            switch (type) {
                case RENT:
                    return new String[] {
                        "[rent claim]",
                        claimId,
                        ecoKind.name().toLowerCase(),
                        price + ";" + renewalTime + ";" + maxTime
                    };
                case SELL:
                    return new String[] {
                        "[sell claim]",
                        claimId,
                        ecoKind.name().toLowerCase(),
                        price
                    };
                case MAILBOX:
                    return new String[] {
                        "[mailbox]",
                        claimId,
                        ecoKind.name().toLowerCase(),
                        price
                    };
                default:
                    return new String[] {"", "", "", ""};
            }
        }
    }
    
    public SetupWizardManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.gp = new GPBridge();
        
        // Cleanup expired sessions every minute
        SchedulerAdapter.runRepeatingGlobal(plugin, () -> {
            activeSessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }, 20 * 60L, 20 * 60L);
    }

    public GPExpansionPlugin getPlugin() {
        return plugin;
    }
    
    /**
     * Start a new setup wizard session for a player.
     * @param player The player
     * @param type The type of sign to create
     * @param claimId Optional claim ID (if provided via command arg or resolved from location)
     * @return true if session was started, false if validation failed
     */
    public boolean startSession(Player player, SetupType type, String claimId) {
        UUID playerId = player.getUniqueId();
        
        // Cancel any existing session
        if (activeSessions.containsKey(playerId)) {
            activeSessions.remove(playerId);
            plugin.getMessages().send(player, "wizard.previous-cancelled");
        }
        
        SetupSession session = new SetupSession(playerId, type);
        
        // If claim ID is provided, validate and skip to step 2
        if (claimId != null && !claimId.isEmpty()) {
            // Validate claim exists
            Optional<Object> claimOpt = gp.findClaimById(claimId);
            if (claimOpt.isEmpty()) {
                plugin.getMessages().send(player, "wizard.claim-not-found", "{id}", claimId);
                return false;
            }
            
            // Validate ownership
            Object claim = claimOpt.get();
            if (!gp.isOwner(claim, playerId) && !player.hasPermission("griefprevention.admin")) {
                plugin.getMessages().send(player, "wizard.not-claim-owner");
                return false;
            }
            
            // For mailbox, validate it's a 1x1x1 3D subdivision
            if (type == SetupType.MAILBOX) {
                if (!gp.isGP3D()) {
                    plugin.getMessages().send(player, "wizard.gp3d-required");
                    return false;
                }
                if (!gp.isSubdivision(claim) || !gp.is3DClaim(claim)) {
                    plugin.getMessages().send(player, "wizard.mailbox-must-be-subdivision");
                    return false;
                }
                int[] dims = gp.getClaimDimensions(claim);
                if (dims[0] != 1 || dims[1] != 1 || dims[2] != 1) {
                    plugin.getMessages().send(player, "wizard.mailbox-wrong-size",
                        "{width}", String.valueOf(dims[0]),
                        "{height}", String.valueOf(dims[1]),
                        "{depth}", String.valueOf(dims[2]));
                    return false;
                }
            }
            
            session.setClaimId(claimId);
            session.setIdPreResolved(true);
            
            // Advance to next step based on type
            if (type == SetupType.RENT) {
                session.setCurrentStep(SetupStep.AWAITING_RENEWAL_TIME);
            } else {
                session.setCurrentStep(SetupStep.AWAITING_ECO_TYPE);
            }
        }
        
        activeSessions.put(playerId, session);
        sendCurrentStepPrompt(player, session);
        return true;
    }
    
    /**
     * Check if a player has an active session.
     */
    public boolean hasActiveSession(UUID playerId) {
        SetupSession session = activeSessions.get(playerId);
        if (session == null) return false;
        if (session.isExpired()) {
            activeSessions.remove(playerId);
            return false;
        }
        return true;
    }
    
    /**
     * Get a player's active session.
     */
    public SetupSession getSession(UUID playerId) {
        return activeSessions.get(playerId);
    }
    
    /**
     * Cancel a player's session.
     */
    public void cancelSession(UUID playerId) {
        activeSessions.remove(playerId);
    }
    
    /**
     * Check if player has pending auto-paste data.
     */
    public boolean hasPendingAutoPaste(UUID playerId) {
        return pendingAutoPaste.containsKey(playerId);
    }
    
    /**
     * Get pending auto-paste data for a player (without removing it).
     */
    public PendingSignData getPendingAutoPaste(UUID playerId) {
        return pendingAutoPaste.get(playerId);
    }
    
    /**
     * Get and remove pending auto-paste data for a player.
     */
    public PendingSignData consumePendingAutoPaste(UUID playerId) {
        return pendingAutoPaste.remove(playerId);
    }
    
    /**
     * Cancel pending auto-paste for a player.
     */
    public boolean cancelPendingAutoPaste(UUID playerId) {
        return pendingAutoPaste.remove(playerId) != null;
    }
    
    /**
     * Process chat input from a player in an active session.
     * @return true if the input was handled (chat event should be cancelled)
     */
    public boolean processInput(Player player, String input) {
        UUID playerId = player.getUniqueId();
        SetupSession session = activeSessions.get(playerId);
        
        if (session == null || session.isExpired()) {
            activeSessions.remove(playerId);
            return false;
        }
        
        // Handle cancel command
        if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
            activeSessions.remove(playerId);
            plugin.getMessages().send(player, "wizard.cancelled");
            return true;
        }
        
        String trimmed = input.trim();
        
        switch (session.getCurrentStep()) {
            case AWAITING_CLAIM_ID:
                return handleClaimIdInput(player, session, trimmed);
            case AWAITING_RENEWAL_TIME:
                return handleRenewalTimeInput(player, session, trimmed);
            case AWAITING_MAX_TIME:
                return handleMaxTimeInput(player, session, trimmed);
            case AWAITING_ECO_TYPE:
                return handleEcoTypeInput(player, session, trimmed);
            case AWAITING_PRICE:
                return handlePriceInput(player, session, trimmed);
            case AWAITING_AUTO_PASTE:
                return handleAutoPasteInput(player, session, trimmed);
            case AWAITING_CONFIRM:
                return handleConfirmInput(player, session, trimmed);
            default:
                return false;
        }
    }
    
    private boolean handleClaimIdInput(Player player, SetupSession session, String input) {
        // Must be a valid integer
        if (!input.matches("^\\d+$")) {
            plugin.getMessages().send(player, "wizard.invalid-claim-id");
            return true;
        }
        
        // Validate claim exists
        Optional<Object> claimOpt = gp.findClaimById(input);
        if (claimOpt.isEmpty()) {
            plugin.getMessages().send(player, "wizard.claim-not-found", "{id}", input);
            return true;
        }
        
        // Validate ownership
        Object claim = claimOpt.get();
        if (!gp.isOwner(claim, player.getUniqueId()) && !player.hasPermission("griefprevention.admin")) {
            plugin.getMessages().send(player, "wizard.not-claim-owner");
            return true;
        }
        
        // For mailbox, validate it's a 1x1x1 3D subdivision
        if (session.getType() == SetupType.MAILBOX) {
            if (!gp.isGP3D()) {
                plugin.getMessages().send(player, "wizard.gp3d-required");
                activeSessions.remove(player.getUniqueId());
                return true;
            }
            if (!gp.isSubdivision(claim) || !gp.is3DClaim(claim)) {
                plugin.getMessages().send(player, "wizard.mailbox-must-be-subdivision");
                return true;
            }
            int[] dims = gp.getClaimDimensions(claim);
            if (dims[0] != 1 || dims[1] != 1 || dims[2] != 1) {
                plugin.getMessages().send(player, "wizard.mailbox-wrong-size",
                    "{width}", String.valueOf(dims[0]),
                    "{height}", String.valueOf(dims[1]),
                    "{depth}", String.valueOf(dims[2]));
                return true;
            }
        }
        
        session.setClaimId(input);
        session.incrementStep();
        
        // Advance to next step
        if (session.getType() == SetupType.RENT) {
            session.setCurrentStep(SetupStep.AWAITING_RENEWAL_TIME);
        } else {
            session.setCurrentStep(SetupStep.AWAITING_ECO_TYPE);
        }
        
        sendCurrentStepPrompt(player, session);
        return true;
    }
    
    private boolean handleRenewalTimeInput(Player player, SetupSession session, String input) {
        if (!isValidDuration(input)) {
            plugin.getMessages().send(player, "wizard.invalid-duration");
            return true;
        }
        
        session.setRenewalTime(input.toLowerCase());
        session.incrementStep();
        session.setCurrentStep(SetupStep.AWAITING_MAX_TIME);
        sendCurrentStepPrompt(player, session);
        return true;
    }
    
    private boolean handleMaxTimeInput(Player player, SetupSession session, String input) {
        if (!isValidDuration(input)) {
            plugin.getMessages().send(player, "wizard.invalid-duration");
            return true;
        }
        
        session.setMaxTime(input.toLowerCase());
        session.incrementStep();
        session.setCurrentStep(SetupStep.AWAITING_ECO_TYPE);
        sendCurrentStepPrompt(player, session);
        return true;
    }
    
    private boolean handleEcoTypeInput(Player player, SetupSession session, String input) {
        String lower = input.toLowerCase();
        EcoKind kind = null;
        
        if (lower.equals("money") || lower.equals("$") || lower.equals("1")) {
            kind = EcoKind.MONEY;
        } else if (lower.equals("exp") || lower.equals("xp") || lower.equals("experience") || lower.equals("2")) {
            kind = EcoKind.EXPERIENCE;
        } else if (lower.equals("claimblocks") || lower.equals("blocks") || lower.equals("cb") || lower.equals("3")) {
            kind = EcoKind.CLAIMBLOCKS;
        } else if (lower.equals("item") || lower.equals("4")) {
            kind = EcoKind.ITEM;
        }
        
        if (kind == null) {
            plugin.getMessages().send(player, "wizard.invalid-economy-type");
            return true;
        }
        
        // Check if money requires Vault
        if (kind == EcoKind.MONEY && !plugin.isEconomyAvailable()) {
            plugin.getMessages().send(player, "wizard.vault-required");
            return true;
        }
        
        session.setEcoKind(kind);
        session.incrementStep();
        session.setCurrentStep(SetupStep.AWAITING_PRICE);
        sendCurrentStepPrompt(player, session);
        return true;
    }
    
    private boolean handlePriceInput(Player player, SetupSession session, String input) {
        if (!NUMBER_PATTERN.matcher(input).matches()) {
            plugin.getMessages().send(player, "wizard.invalid-price");
            return true;
        }
        
        session.setPrice(input);
        session.incrementStep();
        session.setCurrentStep(SetupStep.AWAITING_AUTO_PASTE);
        sendCurrentStepPrompt(player, session);
        return true;
    }
    
    private boolean handleAutoPasteInput(Player player, SetupSession session, String input) {
        String lower = input.toLowerCase();
        
        if (lower.equals("yes") || lower.equals("y")) {
            session.setWantsAutoPaste(true);
        } else if (lower.equals("no") || lower.equals("n")) {
            session.setWantsAutoPaste(false);
        } else {
            plugin.getMessages().send(player, "wizard.yes-or-no");
            return true;
        }
        
        session.incrementStep();
        session.setCurrentStep(SetupStep.AWAITING_CONFIRM);
        sendCurrentStepPrompt(player, session);
        return true;
    }
    
    private boolean handleConfirmInput(Player player, SetupSession session, String input) {
        String lower = input.toLowerCase();
        
        if (lower.equals("yes") || lower.equals("y") || lower.equals("confirm")) {
            completeSetup(player, session);
            return true;
        } else if (lower.equals("no") || lower.equals("n") || lower.equals("cancel")) {
            activeSessions.remove(player.getUniqueId());
            plugin.getMessages().send(player, "wizard.cancelled");
            return true;
        } else {
            plugin.getMessages().send(player, "wizard.confirm-prompt");
            return true;
        }
    }
    
    private void completeSetup(Player player, SetupSession session) {
        activeSessions.remove(player.getUniqueId());
        
        // If they want auto-paste, store the pending data
        if (session.wantsAutoPaste()) {
            pendingAutoPaste.put(player.getUniqueId(), new PendingSignData(session));
            
            sendMessage(player, "");
            sendMessage(player, "&a&l✓ Setup Complete!");
            sendMessage(player, "");
            sendMessage(player, "&aPlace a sign now to auto-fill the format!");
            sendMessage(player, "&7The next sign you place will be automatically configured.");
            sendMessage(player, "");
            if (session.getEcoKind() == EcoKind.ITEM) {
                sendMessage(player, "&e⚠ Hold the payment item in your offhand when placing the sign!");
            }
            sendMessage(player, "&8(Type &6/cancelsetup&8 to cancel auto-paste mode)");
            return;
        }
        
        // Build the sign format string for the player
        StringBuilder signFormat = new StringBuilder();
        String header;
        
        switch (session.getType()) {
            case RENT:
                header = "[rent claim]";
                signFormat.append("&a").append(header).append("\n");
                signFormat.append("&f").append(session.getClaimId()).append("\n");
                signFormat.append("&f").append(session.getEcoKind().name().toLowerCase()).append("\n");
                signFormat.append("&f").append(session.getPrice()).append(";")
                         .append(session.getRenewalTime()).append(";")
                         .append(session.getMaxTime());
                break;
            case SELL:
                header = "[sell claim]";
                signFormat.append("&a").append(header).append("\n");
                signFormat.append("&f").append(session.getClaimId()).append("\n");
                signFormat.append("&f").append(session.getEcoKind().name().toLowerCase()).append("\n");
                signFormat.append("&f").append(session.getPrice());
                break;
            case MAILBOX:
                header = "[mailbox]";
                signFormat.append("&9").append(header).append("\n");
                signFormat.append("&f").append(session.getClaimId()).append("\n");
                signFormat.append("&f").append(session.getEcoKind().name().toLowerCase()).append("\n");
                signFormat.append("&f").append(session.getPrice());
                break;
            default:
                return;
        }
        
        sendMessage(player, "");
        sendMessage(player, "&a&l✓ Setup Complete!");
        sendMessage(player, "");
        sendMessage(player, "&7Create a sign with the following format:");
        sendMessage(player, "&8─────────────────────");
        
        // Send each line of the sign format
        String[] lines = signFormat.toString().split("\n");
        for (String line : lines) {
            sendMessage(player, "  " + line);
        }
        
        sendMessage(player, "&8─────────────────────");
        sendMessage(player, "");
        
        if (session.getEcoKind() == EcoKind.ITEM) {
            sendMessage(player, "&e⚠ Remember to hold the payment item in your offhand when placing the sign!");
        }
        
        // Give summary
        sendMessage(player, "&7Summary:");
        sendMessage(player, "&8• &7Claim ID: &e" + session.getClaimId());
        sendMessage(player, "&8• &7Payment: &e" + formatPayment(session));
        if (session.getType() == SetupType.RENT) {
            sendMessage(player, "&8• &7Rental period: &e" + session.getRenewalTime());
            sendMessage(player, "&8• &7Max duration: &e" + session.getMaxTime());
        }
    }
    
    private String formatPayment(SetupSession session) {
        switch (session.getEcoKind()) {
            case MONEY:
                return "$" + session.getPrice();
            case EXPERIENCE:
                return session.getPrice() + " XP";
            case CLAIMBLOCKS:
                return session.getPrice() + " claim blocks";
            case ITEM:
                return session.getPrice() + "x item (offhand)";
            default:
                return session.getPrice();
        }
    }
    
    /**
     * Send the prompt for the current wizard step.
     */
    private void sendCurrentStepPrompt(Player player, SetupSession session) {
        sendMessage(player, "");
        
        // Send header based on whether we have a claim ID
        String claimId = session.getClaimId();
        String typePrefix = session.getType().name().toLowerCase();
        
        if (claimId != null && !claimId.isEmpty()) {
            // We have a claim ID - show "Renting claim X..." style header
            plugin.getMessages().send(player, "wizard." + typePrefix + "-start", "{id}", claimId);
        } else {
            // No claim ID yet - show generic header
            plugin.getMessages().send(player, "wizard." + typePrefix + "-start-no-claim");
        }
        
        int step = session.getStepNumber();
        
        switch (session.getCurrentStep()) {
            case AWAITING_CLAIM_ID:
                sendMessage(player, "&aStep " + step + ": ");
                plugin.getMessages().send(player, "wizard." + typePrefix + "-enter-claim-id");
                plugin.getMessages().send(player, "wizard." + typePrefix + "-enter-claim-id-hint");
                break;
                
            case AWAITING_RENEWAL_TIME:
                plugin.getMessages().send(player, "wizard.step-prompt", "{step}", String.valueOf(step), "{prompt}", "");
                plugin.getMessages().send(player, "wizard.rent-enter-duration");
                plugin.getMessages().send(player, "wizard.rent-enter-duration-hint");
                break;
                
            case AWAITING_MAX_TIME:
                plugin.getMessages().send(player, "wizard.step-prompt", "{step}", String.valueOf(step), "{prompt}", "");
                plugin.getMessages().send(player, "wizard.rent-enter-max-duration");
                plugin.getMessages().send(player, "wizard.rent-enter-max-duration-hint");
                break;
                
            case AWAITING_ECO_TYPE:
                plugin.getMessages().send(player, "wizard.step-prompt", "{step}", String.valueOf(step), "{prompt}", "");
                plugin.getMessages().send(player, "wizard." + typePrefix + "-enter-economy");
                plugin.getMessages().send(player, "wizard." + typePrefix + "-enter-economy-hint");
                if (session.getType() == SetupType.RENT) {
                    sendMessage(player, "&7(This is per rental period)");
                }
                break;
                
            case AWAITING_PRICE:
                plugin.getMessages().send(player, "wizard.step-prompt", "{step}", String.valueOf(step), "{prompt}", "");
                plugin.getMessages().send(player, "wizard." + typePrefix + "-enter-amount");
                switch (session.getEcoKind()) {
                    case MONEY:
                        sendMessage(player, "&7Enter the amount (e.g., &e100&7, &e1000&7)");
                        break;
                    case EXPERIENCE:
                        sendMessage(player, "&7Enter XP amount, add &eL&7 for levels (e.g., &e500&7, &e30L&7)");
                        break;
                    case CLAIMBLOCKS:
                        sendMessage(player, "&7Enter number of claim blocks (e.g., &e50&7)");
                        break;
                    case ITEM:
                        sendMessage(player, "&7Enter item quantity (e.g., &e16&7, &e64&7)");
                        break;
                }
                break;
                
            case AWAITING_AUTO_PASTE:
                sendMessage(player, "&aStep " + step + ": &eDo you want to have the next sign you place automatically paste the format?");
                sendMessage(player, "&7If 'no' you will have to type it manually when placing.");
                plugin.getMessages().send(player, "wizard.yes-or-no");
                break;
                
            case AWAITING_CONFIRM:
                sendMessage(player, "&aStep " + step + ": &eConfirm your settings");
                sendMessage(player, "");
                sendMessage(player, "&7Claim ID: &e" + session.getClaimId());
                sendMessage(player, "&7Payment: &e" + formatPayment(session));
                if (session.getType() == SetupType.RENT) {
                    sendMessage(player, "&7Rental period: &e" + session.getRenewalTime());
                    sendMessage(player, "&7Max duration: &e" + session.getMaxTime());
                }
                sendMessage(player, "");
                plugin.getMessages().send(player, "wizard.confirm-prompt");
                break;
        }
        
        plugin.getMessages().send(player, "wizard.cancel-hint");
    }
    
    private boolean isValidDuration(String input) {
        return DURATION_PATTERN.matcher(input).matches();
    }
    
    private void sendMessage(Player player, String message) {
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }
}
