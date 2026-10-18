package com.medizon.service;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;

/**
 * Matches the patient's name on a prescription to a household member. Doctors write names loosely ("Mrs. Asha
 * Sharma", "ASHA SHARMA", "Asha"), so titles, case and punctuation are ignored. When in doubt there's no match: a
 * medicine under "Others" is better than one under the wrong person.
 */
public final class PatientNames {

    private static final Set<String> TITLES = Set.of("mr", "mrs", "ms", "miss", "master", "baby", "smt", "shri", "sri",
            "kumari", "kum", "dr", "md", "b/o", "s/o", "d/o", "w/o");

    private PatientNames() {}

    /** Lowercase words without titles or punctuation: "Mrs. Asha  SHARMA" is [asha, sharma]. */
    static List<String> words(String name) {
        if (name == null) return List.of();
        String plain = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String w : plain.split("[^\\p{L}/]+")) {
            if (!w.isBlank() && !TITLES.contains(w)) out.add(w);
        }
        return out;
    }

    /**
     * The one candidate whose name matches: the same words in any order; or, with two or more words written, all of
     * them in the candidate's name ("Ravi Kumar" for "Ravi Kumar Sharma"); or a lone first name that only one
     * candidate has. Empty when nobody, or more than one person, matches.
     */
    public static <T> Optional<T> match(String written, List<T> candidates, Function<T, String> nameOf) {
        List<String> w = words(written);
        if (w.isEmpty()) return Optional.empty();
        for (Rule rule : Rule.values()) {
            List<T> hits = candidates.stream().filter(c -> rule.matches(w, words(nameOf.apply(c)))).toList();
            if (hits.size() == 1) return Optional.of(hits.get(0));
            if (hits.size() > 1) return Optional.empty();      // ambiguous: don't guess
        }
        return Optional.empty();
    }

    private enum Rule {
        SAME {
            boolean matches(List<String> written, List<String> name) {
                return !name.isEmpty() && new HashSet<>(written).equals(new HashSet<>(name));
            }
        },
        CONTAINED {
            boolean matches(List<String> written, List<String> name) {
                List<String> small = written.size() <= name.size() ? written : name;
                List<String> big = small == written ? name : written;
                return small.size() >= 2 && new HashSet<>(big).containsAll(small);
            }
        },
        FIRST_NAME {
            boolean matches(List<String> written, List<String> name) {
                return written.size() == 1 && !name.isEmpty() && name.get(0).equals(written.get(0));
            }
        };

        abstract boolean matches(List<String> written, List<String> name);
    }
}
