package com.medizon.service;

import java.util.HashMap;
import java.util.Map;

/** GST state codes, looked up from the free-text state on a profile ("Tamil Nadu", "tamilnadu", "TN" ...). */
final class GstStates {

    private static final Map<String, String> CODES = new HashMap<>();

    static {
        add("01", "Jammu and Kashmir", "jk", "j&k");
        add("02", "Himachal Pradesh", "hp");
        add("03", "Punjab", "pb");
        add("04", "Chandigarh", "ch");
        add("05", "Uttarakhand", "uk", "uttaranchal");
        add("06", "Haryana", "hr");
        add("07", "Delhi", "dl", "new delhi", "nct of delhi");
        add("08", "Rajasthan", "rj");
        add("09", "Uttar Pradesh", "up");
        add("10", "Bihar", "br");
        add("11", "Sikkim", "sk");
        add("12", "Arunachal Pradesh", "ar");
        add("13", "Nagaland", "nl");
        add("14", "Manipur", "mn");
        add("15", "Mizoram", "mz");
        add("16", "Tripura", "tr");
        add("17", "Meghalaya", "ml");
        add("18", "Assam", "as");
        add("19", "West Bengal", "wb");
        add("20", "Jharkhand", "jh");
        add("21", "Odisha", "od", "orissa");
        add("22", "Chhattisgarh", "cg", "chattisgarh");
        add("23", "Madhya Pradesh", "mp");
        add("24", "Gujarat", "gj");
        add("26", "Dadra and Nagar Haveli and Daman and Diu", "dnhdd", "daman and diu", "dadra and nagar haveli");
        add("27", "Maharashtra", "mh");
        add("29", "Karnataka", "ka");
        add("30", "Goa", "ga");
        add("31", "Lakshadweep", "ld");
        add("32", "Kerala", "kl");
        add("33", "Tamil Nadu", "tn");
        add("34", "Puducherry", "py", "pondicherry");
        add("35", "Andaman and Nicobar Islands", "an", "andaman and nicobar");
        add("36", "Telangana", "ts", "tg");
        add("37", "Andhra Pradesh", "ap");
        add("38", "Ladakh", "la");
    }

    private GstStates() { }

    private static void add(String code, String name, String... aliases) {
        CODES.put(key(name), code);
        for (String a : aliases) CODES.put(key(a), code);
    }

    private static String key(String s) {
        return s.toLowerCase().replace("&", "and").replaceAll("[^a-z]", "");
    }

    /** Two-digit GST state code, or null when the state isn't recognised. */
    static String code(String state) {
        return state == null || state.isBlank() ? null : CODES.get(key(state));
    }
}
