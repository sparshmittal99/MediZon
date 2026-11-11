package com.medizon.service;

import com.medizon.domain.Medicine;

import java.util.*;
import java.util.function.Function;

/**
 * Forgiving medicine search, the way people type into a search box: part of a name ("azithro", "dolo"), a typo or a
 * spelling by sound ("paracitamol", "parasetamol", "omeprazol"), spacing ("dolo650"), any word order, extra words
 * ("crocin tablet for fever"). Matches the name, salt, maker, strength and category, best matches first.
 *
 * <p>Works in memory over a list the caller already has (what the shop sells at the shopper's location), so stock
 * and store rules stay with the caller. Every word the shopper typed has to match something; when nothing matches
 * all of them, medicines matching most of them come back instead, so a question still finds the medicine.
 */
public final class MedicineSearch {

    private MedicineSearch() {}

    /** Words that say nothing about which medicine: dropped. Includes common Hindi fillers. */
    private static final Set<String> FILLER = Set.of(
            "a", "an", "the", "of", "for", "to", "and", "or", "with", "me", "my", "i", "some", "any", "please", "pls",
            "medicine", "medicines", "medicin", "dawa", "dawai", "dava", "davai", "order", "buy", "need", "want",
            "find", "search", "show", "get", "price", "cost", "rate", "ki", "ka", "ke", "ko", "se", "mujhe", "chahiye");

    /** Form and unit words: they count when they match but don't have to ("crocin tablet" still finds Crocin syrup). */
    private static final Set<String> SOFT = Set.of(
            "tab", "tabs", "tablet", "tablets", "cap", "caps", "capsule", "capsules", "syrup", "syp", "susp",
            "suspension", "injection", "inj", "cream", "gel", "ointment", "drop", "drops", "spray", "powder",
            "sachet", "strip", "strips", "pack", "packs", "bottle", "mg", "ml", "gm", "g", "mcg", "iu");

    /** One medicine and how well it matched. */
    public record Hit<T>(T item, double score, boolean exact) {}

    /** {@link #rank(Collection, String, Function)} over medicines themselves. */
    public static List<Medicine> rank(Collection<Medicine> medicines, String query) {
        return rank(medicines, query, Function.identity()).stream().map(Hit::item).toList();
    }

    /**
     * {@code items} that match {@code query}, best first; an empty query matches nothing. {@code exact} on a hit
     * means the shopper's words appear as typed (no spelling fixed), so a page can offer "did you mean".
     */
    public static <T> List<Hit<T>> rank(Collection<T> items, String query, Function<T, Medicine> medicine) {
        Query q = Query.of(query);
        if (q.isEmpty()) return List.of();
        List<Hit<T>> all = new ArrayList<>(), partial = new ArrayList<>();
        int bestPartial = 0;
        for (T item : items) {
            Medicine m = medicine.apply(item);
            if (m == null) continue;
            Match match = q.match(m);
            if (match.matched == 0) continue;
            if (match.matched == q.hard.size()) {
                all.add(new Hit<>(item, match.score, match.exact));
            } else if (!all.isEmpty()) {
                // A full match exists: partial ones won't be shown
            } else if (match.matched >= bestPartial) {
                if (match.matched > bestPartial) { partial.clear(); bestPartial = match.matched; }
                partial.add(new Hit<>(item, match.score, false));
            }
        }
        List<Hit<T>> out = all.isEmpty() ? partial : all;
        out.sort(Comparator.<Hit<T>>comparingDouble(Hit::score).reversed()
                .thenComparing(h -> medicine.apply(h.item()).getName(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ------------------------------------------------------------------ the query

    private record Match(int matched, double score, boolean exact) {}

    /** A token of a medicine's text, with the field's weight. */
    private record Word(String text, String sound, double weight) {}

    private static final class Query {
        final String whole;          // normalised, spaces kept
        final String squashed;       // no spaces: "dolo650"
        final List<String> hard = new ArrayList<>();
        final List<String> soft = new ArrayList<>();

        private Query(String whole) {
            this.whole = whole;
            this.squashed = whole.replace(" ", "");
        }

        static Query of(String raw) {
            Query q = new Query(normalise(raw));
            for (String t : split(q.whole)) {
                if (FILLER.contains(t)) continue;
                (SOFT.contains(t) ? q.soft : q.hard).add(t);
            }
            // Only form words ("syrup"): they're what was asked for
            if (q.hard.isEmpty()) { q.hard.addAll(q.soft); q.soft.clear(); }
            return q;
        }

        boolean isEmpty() { return hard.isEmpty(); }

        Match match(Medicine m) {
            List<Word> words = words(m);

            int matched = 0;
            double score = 0;
            boolean exact = true;
            for (String t : hard) {
                double best = 0;
                boolean bestExact = false;
                for (Word w : words) {
                    double s = similarity(t, w) * w.weight;
                    if (s > best) { best = s; bestExact = literal(t, w.text); }
                }
                if (best > 0) { matched++; score += best; exact &= bestExact; }
            }
            if (matched == 0) return new Match(0, 0, false);
            score /= hard.size();
            for (String t : soft) {
                for (Word w : words) {
                    if (similarity(t, w) >= 0.85) { score += 0.05; break; }
                }
            }
            // The name as typed: starts with it beats contains it
            String name = normalise(m.getName());
            String nameSquashed = name.replace(" ", "");
            if (name.startsWith(whole) || nameSquashed.startsWith(squashed)) score += 0.35;
            else if (name.contains(whole) || squashed.length() >= 4 && nameSquashed.contains(squashed)) score += 0.15;
            return new Match(matched, score, exact);
        }
    }

    /** Each medicine's words, worked out once: suggestions search the whole shop on every key press. */
    private static final Map<String, List<Word>> WORDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CACHED = 50_000;

    private static List<Word> words(Medicine m) {
        String key = m.getId() + "|" + m.getName() + "|" + m.getGenericName() + "|" + m.getStrength() + "|"
                + m.getDosageForm() + "|" + m.getManufacturer() + "|" + m.getCategory();
        List<Word> cached = WORDS.get(key);
        if (cached != null) return cached;
        List<Word> words = new ArrayList<>();
        add(words, m.getName(), 1.0);
        add(words, m.getGenericName(), 0.9);
        add(words, m.getStrength(), 0.8);
        add(words, m.getDosageForm(), 0.5);
        add(words, m.getManufacturer(), 0.55);
        add(words, m.getCategory(), 0.45);
        if (WORDS.size() > MAX_CACHED) WORDS.clear();    // edited products leave old keys behind
        WORDS.put(key, List.copyOf(words));
        return words;
    }

    private static void add(List<Word> words, String text, double weight) {
        if (text == null || text.isBlank()) return;
        String norm = normalise(text);
        for (String t : split(norm)) words.add(new Word(t, sound(t), weight));
        // "Dolo 650" also as "dolo650", for people who type it together
        String joined = norm.replace(" ", "");
        if (!joined.equals(norm) && joined.length() <= 24) words.add(new Word(joined, sound(joined), weight * 0.95));
    }

    /** The query word appears as typed (start of a word, or inside a longer one). */
    private static boolean literal(String t, String word) {
        return word.startsWith(t) || t.length() >= 3 && word.contains(t);
    }

    /**
     * 1 for the same word, a little less for the start of it (still typing), then spelling by sound, then a typo or
     * two, scaled by length so short words need to be right.
     */
    static double similarity(String t, Word w) {
        String word = w.text;
        if (word.equals(t)) return 1.0;
        boolean digits = Character.isDigit(t.charAt(0));
        if (digits) {
            // Strengths: "500" matches 500 and 500mg, not 1500
            return word.startsWith(t) ? 0.9 : 0;
        }
        if (word.startsWith(t)) return t.length() == 1 ? 0.5 : 0.92;
        if (t.length() >= 3 && word.contains(t)) return 0.72;
        if (t.length() < 3) return 0;
        String ts = sound(t);
        if (ts.equals(w.sound)) return 0.88;
        if (ts.length() >= 3 && w.sound.startsWith(ts)) return 0.82;
        int allowed = t.length() <= 4 ? 1 : t.length() <= 7 ? 2 : 3;
        // Typed so far vs the start of the word, so a typo while still typing matches too
        int d = Math.min(distance(t, word), prefixDistance(t, word));
        if (d <= allowed) return 0.78 - 0.1 * d;
        int ds = Math.min(distance(ts, w.sound), prefixDistance(ts, w.sound));
        if (ds <= Math.max(1, allowed - 1)) return 0.7 - 0.1 * ds;
        return 0;
    }

    // ------------------------------------------------------------------ text helpers

    public static String normalise(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(lower.length() + 4);
        char prev = ' ';
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean alnum = Character.isLetterOrDigit(c);
            if (!alnum) c = ' ';
            // Letters and digits apart: "500mg" -> "500 mg", "dolo650" -> "dolo 650"
            else if (b.length() > 0 && prev != ' ' && Character.isDigit(c) != Character.isDigit(prev)) b.append(' ');
            if (c == ' ' && (b.length() == 0 || prev == ' ')) continue;
            b.append(c);
            prev = c;
        }
        return b.toString().trim();
    }

    private static List<String> split(String norm) {
        if (norm.isEmpty()) return List.of();
        return Arrays.asList(norm.split(" "));
    }

    /**
     * A rough sound-alike key for how medicine names get spelt in India: ph/f, c/k/s, z/s, w/v, y/i, x/ks, and
     * doubled letters and silent h dropped. "Paracitamol", "parasetamol" and "paracetamol" share most of it.
     */
    static String sound(String t) {
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            char next = i + 1 < t.length() ? t.charAt(i + 1) : ' ';
            String out;
            switch (c) {
                case 'p' -> { if (next == 'h') { out = "f"; i++; } else out = "p"; }
                case 'c' -> out = (next == 'e' || next == 'i' || next == 'y') ? "s" : "k";
                case 'q' -> out = "k";
                case 'z' -> out = "s";
                case 'x' -> out = "ks";
                case 'w' -> out = "v";
                case 'y' -> out = "i";
                case 'h' -> out = i == 0 ? "h" : "";
                case 'e' -> out = "i";        // vowels in the middle are where most misspellings are
                case 'o' -> out = i == t.length() - 1 ? "a" : "o";
                default -> out = String.valueOf(c);
            }
            for (int k = 0; k < out.length(); k++) {
                char o = out.charAt(k);
                if (b.length() == 0 || b.charAt(b.length() - 1) != o) b.append(o);
            }
        }
        return b.toString();
    }

    /** Edit distance with swapped neighbours counting as one (optimal string alignment). */
    static int distance(String a, String b) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > 3) return 99;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    /** The distance from {@code t} to the closest start of {@code word}: "paracit" is 1 from "paracet(amol)". */
    static int prefixDistance(String t, String word) {
        if (word.length() <= t.length()) return 99;
        int best = 99;
        for (int len = Math.max(1, t.length() - 1); len <= Math.min(word.length(), t.length() + 1); len++) {
            best = Math.min(best, distance(t, word.substring(0, len)));
        }
        return best;
    }
}
