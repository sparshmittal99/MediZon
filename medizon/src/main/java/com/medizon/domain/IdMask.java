package com.medizon.domain;

/**
 * Masks database ids in the links customers see (/plans/k7Qx2mPaB3 instead of /plans/42), so URLs don't show how
 * many plans, tickets or orders there are or count up. The id is run through a small keyed permutation (a Feistel
 * network over 58 bits) and written as {@value #LENGTH} base-62 characters: every id has exactly one token and turns
 * back into it. Each kind of record ("plan", "ticket" ...) has its own key, so the same number gives different tokens
 * for different things. Ownership is still checked on every page; this only masks the id.
 *
 * <p>Orders use the empty kind, which keeps the order links sent before other ids were masked working.
 */
public final class IdMask {

    public static final int LENGTH = 10;

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BITS = 29;            // each half; 58 bits in all, under 62^10
    private static final long HALF = (1L << BITS) - 1;
    private static final long LIMIT = 1L << (2 * BITS);
    private static final long[] KEYS = {0x9E3779L, 0x7F4A7CL, 0x15A4E3L, 0xC2B2AEL};

    private IdMask() {}

    public static String encode(long id, String kind) {
        if (id < 0 || id >= LIMIT) throw new IllegalArgumentException("Id out of range: " + id);
        long tweak = tweak(kind);
        long l = id >>> BITS, r = id & HALF;
        for (long k : KEYS) {
            long t = r;
            r = l ^ round(r, k ^ tweak);
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

    /** The id for a token of this kind; null when it isn't a token. Plain numbers are not accepted here. */
    public static Long decode(String token, String kind) {
        if (!isToken(token)) return null;
        char[] in = token.toCharArray();
        in[0] = ALPHABET.charAt(Math.floorMod(ALPHABET.indexOf(in[0]) - tailSum(in), 62));
        long v = 0;
        for (char c : in) v = v * 62 + ALPHABET.indexOf(c);
        if (v >= LIMIT) return null;
        long tweak = tweak(kind);
        long l = v >>> BITS, r = v & HALF;
        for (int i = KEYS.length - 1; i >= 0; i--) {
            long t = l;
            l = r ^ round(l, KEYS[i] ^ tweak);
            r = t;
        }
        return (l << BITS) | r;
    }

    /** Shaped like a token: {@value #LENGTH} letters and digits. */
    public static boolean isToken(String s) {
        if (s == null || s.length() != LENGTH) return false;
        for (int i = 0; i < LENGTH; i++) if (ALPHABET.indexOf(s.charAt(i)) < 0) return false;
        return true;
    }

    /** A plain database id (a link from before ids were masked) rather than a token. */
    public static boolean isPlainId(String s) {
        return s != null && !s.isEmpty() && s.length() < LENGTH && s.chars().allMatch(Character::isDigit);
    }

    private static long tweak(String kind) {
        if (kind == null || kind.isEmpty()) return 0;
        return (kind.hashCode() * 0x9E3779B1L) & 0xFFFFFFL;
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
