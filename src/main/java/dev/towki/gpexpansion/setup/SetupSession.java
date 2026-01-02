package dev.towki.gpexpansion.setup;

import dev.towki.gpexpansion.util.EcoKind;

import java.util.UUID;

/**
 * Tracks the state of a player's sign setup wizard session.
 */
public class SetupSession {
    
    public enum SetupType {
        RENT("Renting claim"),
        SELL("Selling claim"),
        MAILBOX("Setting up mailbox");
        
        private final String header;
        
        SetupType(String header) {
            this.header = header;
        }
        
        public String getHeader() {
            return header;
        }
    }
    
    public enum SetupStep {
        AWAITING_CLAIM_ID,      // Step 1: Need claim ID
        AWAITING_RENEWAL_TIME,  // Step 2.1 (rent only): Renewal duration
        AWAITING_MAX_TIME,      // Step 2.2 (rent only): Max rental duration
        AWAITING_ECO_TYPE,      // Step 3: Economy type
        AWAITING_PRICE,         // Step 4: Price amount
        AWAITING_AUTO_PASTE,    // Step 5: Ask if they want auto sign paste
        AWAITING_CONFIRM,       // Step 6: Confirmation
        COMPLETED
    }
    
    private final UUID playerId;
    private final SetupType type;
    private final long startTime;
    
    private SetupStep currentStep;
    private int stepNumber; // Tracks display step number (shifts if ID was pre-resolved)
    
    // Collected data
    private String claimId;
    private String renewalTime;  // For rent signs only
    private String maxTime;      // For rent signs only
    private EcoKind ecoKind;
    private String price;
    private boolean wantsAutoPaste;  // Whether to auto-paste format on next sign
    
    // Whether the ID was resolved from command arg or player location
    private boolean idPreResolved;
    
    public SetupSession(UUID playerId, SetupType type) {
        this.playerId = playerId;
        this.type = type;
        this.startTime = System.currentTimeMillis();
        this.currentStep = SetupStep.AWAITING_CLAIM_ID;
        this.stepNumber = 1;
        this.idPreResolved = false;
    }
    
    public UUID getPlayerId() {
        return playerId;
    }
    
    public SetupType getType() {
        return type;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public SetupStep getCurrentStep() {
        return currentStep;
    }
    
    public void setCurrentStep(SetupStep step) {
        this.currentStep = step;
    }
    
    public int getStepNumber() {
        return stepNumber;
    }
    
    public void incrementStep() {
        this.stepNumber++;
    }
    
    public String getClaimId() {
        return claimId;
    }
    
    public void setClaimId(String claimId) {
        this.claimId = claimId;
    }
    
    public String getRenewalTime() {
        return renewalTime;
    }
    
    public void setRenewalTime(String renewalTime) {
        this.renewalTime = renewalTime;
    }
    
    public String getMaxTime() {
        return maxTime;
    }
    
    public void setMaxTime(String maxTime) {
        this.maxTime = maxTime;
    }
    
    public EcoKind getEcoKind() {
        return ecoKind;
    }
    
    public void setEcoKind(EcoKind ecoKind) {
        this.ecoKind = ecoKind;
    }
    
    public String getPrice() {
        return price;
    }
    
    public void setPrice(String price) {
        this.price = price;
    }
    
    public boolean wantsAutoPaste() {
        return wantsAutoPaste;
    }
    
    public void setWantsAutoPaste(boolean wantsAutoPaste) {
        this.wantsAutoPaste = wantsAutoPaste;
    }
    
    public boolean isIdPreResolved() {
        return idPreResolved;
    }
    
    public void setIdPreResolved(boolean idPreResolved) {
        this.idPreResolved = idPreResolved;
        if (idPreResolved) {
            // Step numbers shift down by 1 since we skip step 1
            this.stepNumber = 1;
        }
    }
    
    /**
     * Check if the session has expired (5 minute timeout)
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - startTime > 5 * 60 * 1000;
    }
    
    /**
     * Get the action header for display
     */
    public String getActionHeader() {
        return type.getHeader() + "...";
    }
}
