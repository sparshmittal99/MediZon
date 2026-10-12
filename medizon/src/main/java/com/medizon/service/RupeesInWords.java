package com.medizon.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** "Rupees One Lakh Twenty Thousand Five Hundred and Fifty Paise Only", Indian numbering, for invoices. */
final class RupeesInWords {

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private RupeesInWords() { }

    static String of(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP);
        long rupees = a.longValue();
        int paise = a.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String words = "Rupees " + (rupees == 0 ? "Zero" : words(rupees));
        if (paise > 0) words += " and " + words(paise) + " Paise";
        return words + " Only";
    }

    private static String words(long n) {
        StringBuilder out = new StringBuilder();
        n = chunk(out, n, 10_000_000, "Crore");
        n = chunk(out, n, 100_000, "Lakh");
        n = chunk(out, n, 1_000, "Thousand");
        n = chunk(out, n, 100, "Hundred");
        if (n > 0) append(out, belowHundred((int) n));
        return out.toString();
    }

    private static long chunk(StringBuilder out, long n, long unit, String name) {
        if (n < unit) return n;
        long count = n / unit;
        append(out, (count >= 100 ? words(count) : belowHundred((int) count)) + " " + name);
        return n % unit;
    }

    private static String belowHundred(int n) {
        if (n < 20) return ONES[n];
        return TENS[n / 10] + (n % 10 == 0 ? "" : " " + ONES[n % 10]);
    }

    private static void append(StringBuilder out, String part) {
        if (out.length() > 0) out.append(' ');
        out.append(part);
    }
}
