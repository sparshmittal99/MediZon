package com.medizon.domain;

/**
 * Where a return's refund goes, and how long after pickup it lands. Prepaid orders go back to the payment method used
 * or to MediZon Money; cash on delivery orders have nothing to reverse, so the customer picks MediZon Money, a UPI ID or
 * a bank account. Whatever was paid with MediZon Money always goes back to it.
 */
public enum RefundMode {
    ORIGINAL("Original payment method"), WALLET("MediZon Money"), UPI("UPI"), BANK("Bank account");

    private final String label;

    RefundMode(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Days after pickup the money arrives: MediZon Money at once, UPI in 2, bank accounts and cards in 7. */
    public int days(PaymentMethod paidBy) {
        return switch (this) {
            case WALLET -> 0;
            case UPI -> 2;
            case BANK -> 7;
            case ORIGINAL -> paidBy == PaymentMethod.UPI ? 2 : paidBy == PaymentMethod.WALLET ? 0 : 7;
        };
    }

    /** "Instantly after pickup", "2 days after pickup". */
    public String timing(PaymentMethod paidBy) {
        int d = days(paidBy);
        return d == 0 ? "Instantly after pickup" : d + " days after pickup";
    }
}
