package com.medizon.domain;

/**
 * The order reference customers see in links (/orders/k7Qx2mPaB3) in place of the database id: an {@link IdMask}
 * token with the empty kind, so order links sent before other ids were masked keep working. Ownership is still
 * checked on every page. Admin and staff pages keep the plain id.
 */
public final class OrderRef {

    public static final int LENGTH = IdMask.LENGTH;

    private OrderRef() {}

    public static String encode(long id) {
        return IdMask.encode(id, "");
    }

    /**
     * The order id for a reference, or for a plain numeric id from a link sent before references existed (old
     * emails, saved notifications); null for anything else.
     */
    public static Long decode(String ref) {
        if (ref == null) return null;
        if (isPlainId(ref)) return Long.valueOf(ref);
        return IdMask.decode(ref, "");
    }

    /** A plain database id (an old link) rather than a reference. */
    public static boolean isPlainId(String ref) {
        return IdMask.isPlainId(ref);
    }
}
