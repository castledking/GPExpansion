package dev.towki.gpexpansion.util;

/**
 * Represents the specification for a renewal operation.
 */
public class RenewalSpec {
    public final String ecoAmtRaw;
    public final String perClick;
    public final String maxCap;

    public RenewalSpec(String ecoAmtRaw, String perClick, String maxCap) {
        this.ecoAmtRaw = ecoAmtRaw;
        this.perClick = perClick;
        this.maxCap = maxCap;
    }
}
