package com.medizon.domain;

/**
 * What the customer wants for packs they send back: their money (a return) or the same medicine again (an exchange).
 * Both are reviewed, picked up and tracked the same way; an exchange ships a free replacement order instead of a refund.
 */
public enum ReturnKind {
    RETURN("Return", "RT"), EXCHANGE("Exchange", "EX");

    private final String label;
    private final String prefix;

    ReturnKind(String label, String prefix) { this.label = label; this.prefix = prefix; }

    public String getLabel() { return label; }

    /** Start of the request number: RT2611180042 for a return, EX2611180042 for an exchange. */
    public String getPrefix() { return prefix; }

    /** "exchange", "return": for sentences. */
    public String getNoun() { return label.toLowerCase(); }

    /** An exchange only makes sense when something is wrong with the packs, not when they're no longer needed. */
    public boolean allows(ReturnReason reason) { return this == RETURN || reason != ReturnReason.NOT_NEEDED; }

    /** Parses "exchange" / "EXCHANGE" from a link; anything else is a return. */
    public static ReturnKind of(String s) {
        return s != null && s.trim().equalsIgnoreCase("exchange") ? EXCHANGE : RETURN;
    }
}
