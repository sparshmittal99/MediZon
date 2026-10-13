package com.medizon.domain;

/** Splits a whole number into parts by weight, so the parts always add back up to the total. */
public final class Shares {

    private Shares() {}

    /**
     * {@code total} split in proportion to {@code weights}; earlier parts get the remainder. Equal parts when every
     * weight is zero.
     */
    public static int[] split(int total, int[] weights) {
        int n = weights.length;
        int[] out = new int[n];
        if (n == 0) return out;
        long sum = 0;
        for (int w : weights) sum += Math.max(0, w);
        if (sum == 0) {
            for (int i = 0; i < n; i++) out[i] = total / n + (i < total % n ? 1 : 0);
            return out;
        }
        int given = 0;
        for (int i = 0; i < n; i++) {
            out[i] = (int) (total * (long) Math.max(0, weights[i]) / sum);
            given += out[i];
        }
        for (int i = 0; given < total; i = (i + 1) % n) {
            if (weights[i] > 0) { out[i]++; given++; }
        }
        return out;
    }
}
