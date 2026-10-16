package com.medizon.domain;

/**
 * What a ticket is about, picked by the customer. Each topic suggests the team support passes it to when it needs a
 * specialist; support can choose another.
 */
public enum TicketTopic {
    ORDER("Order & delivery", "Late, missing or wrong delivery, order status", "bi-truck", Role.STORE),
    RETURN("Return or damaged item", "Damaged, expired or wrong medicine, return pickup", "bi-arrow-return-left", Role.STORE),
    PAYMENT("Payment & refund", "Charged twice, refund not received, invoice", "bi-credit-card", Role.FINANCE),
    PRESCRIPTION("Prescription", "Upload, approval or rejection of a prescription", "bi-file-earmark-medical", Role.PHARMACIST),
    MEDICINE("Medicine question", "Dosage, side effects, substitutes", "bi-capsule", Role.PHARMACIST),
    ACCOUNT("Account & app", "Sign-in, profile, notifications, something not working", "bi-person-gear", Role.ADMIN),
    OTHER("Something else", "Anything that doesn't fit above", "bi-three-dots", null);

    private final String label;
    private final String hint;
    private final String icon;
    private final Role suggestedTeam;

    TicketTopic(String label, String hint, String icon, Role suggestedTeam) {
        this.label = label;
        this.hint = hint;
        this.icon = icon;
        this.suggestedTeam = suggestedTeam;
    }

    public String getLabel() { return label; }

    public String getHint() { return hint; }

    public String getIcon() { return icon; }

    /** The team this kind of issue usually goes to; null when support handles it alone. */
    public Role getSuggestedTeam() { return suggestedTeam; }
}
