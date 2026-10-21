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

    /** Days after pickup the money arrives, as the admin has set them; MediZon Money is always at once. */
    public int days(PaymentMethod paidBy, ReturnSettings s) {
        return switch (this) {
            case WALLET -> 0;
            case UPI -> s.getUpiRefundDays();
            case BANK -> s.getBankRefundDays();
            case ORIGINAL -> paidBy == PaymentMethod.UPI ? s.getUpiRefundDays()
                    : paidBy == PaymentMethod.WALLET ? 0 : s.getCardRefundDays();
        };
    }

    public String timing(PaymentMethod paidBy, ReturnSettings s) { return timing(days(paidBy, s)); }

    /** "Instantly after pickup", "1 day after pickup", "7 days after pickup". */
    public static String timing(int days) {
        return days <= 0 ? "Instantly after pickup" : days + (days == 1 ? " day" : " days") + " after pickup";
    }
}
