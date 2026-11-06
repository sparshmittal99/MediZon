package com.medizon.domain;

/**
 * The order reference customers see in links (/orders/k7Qx2mPaB3) in place of the database id, so their URLs don't
 * show how many orders there are or count up. It's the id run through a small keyed permutation (a Feistel network
 * over 58 bits), written as 10 base-62 characters: every id has exactly one reference and turns back into it.
 * Ownership is still checked on every page; this only masks the id. Admin and staff pages keep the plain id.
 */
public final class OrderRef {

    public static final int LENGTH = 10;

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BITS = 29;            // each half; 58 bits in all, under 62^10
    private static final long HALF = (1L << BITS) - 1;
    private static final long LIMIT = 1L << (2 * BITS);
    private static final long[] KEYS = {0x9E3779L, 0x7F4A7CL, 0x15A4E3L, 0xC2B2AEL};

    private OrderRef() {}

    public static String encode(long id) {
        if (id < 0 || id >= LIMIT) throw new IllegalArgumentException("Order id out of range: " + id);
        long l = id >>> BITS, r = id & HALF;
        for (long k : KEYS) {
            long t = r;
            r = l ^ round(r, k);
            l = t;
        }
        long v = (l << BITS) | r;
        char[] out = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (v % 62));
            v /= 62;
        }
        // The leading digit only reaches about 21, so shift it by the rest to keep every position looking random
        out[0] = ALPHABET.charAt((ALPHABET.indexOf(out[0]) + tailSum(out)) % 62);
        return new String(out);
    }

    /**
     * The order id for a reference, or for a plain numeric id from a link sent before references existed (old
     * emails, saved notifications); null for anything else.
     */
    public static Long decode(String ref) {
        if (ref == null) return null;
        if (isPlainId(ref)) return Long.valueOf(ref);
        if (ref.length() != LENGTH) return null;
        char[] in = ref.toCharArray();
        for (char c : in) if (ALPHABET.indexOf(c) < 0) return null;
        in[0] = ALPHABET.charAt(Math.floorMod(ALPHABET.indexOf(in[0]) - tailSum(in), 62));
        long v = 0;
        for (char c : in) v = v * 62 + ALPHABET.indexOf(c);
        if (v >= LIMIT) return null;
        long l = v >>> BITS, r = v & HALF;
        for (int i = KEYS.length - 1; i >= 0; i--) {
            long t = l;
            l = r ^ round(l, KEYS[i]);
            r = t;
        }
        return (l << BITS) | r;
    }

    /** A plain database id (an old link) rather than a reference. */
    public static boolean isPlainId(String ref) {
        return ref != null && !ref.isEmpty() && ref.length() < LENGTH && ref.chars().allMatch(Character::isDigit);
    }

    private static int tailSum(char[] ref) {
        int sum = 0;
        for (int i = 1; i < ref.length; i++) sum += ALPHABET.indexOf(ref[i]);
        return sum % 62;
    }

    private static long round(long x, long key) {
        long h = (x ^ key) * 0x2545F49L;
        h ^= h >>> 13;
        return (h * 0x1B873593L) & HALF;
    }
}
