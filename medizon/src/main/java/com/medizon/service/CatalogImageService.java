package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.User;
import com.medizon.repository.MedicineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Product pictures from a folder. Each picture's file name is matched to a product: part of the name is enough when it
 * tells the product apart ("dolo 650.jpg" finds "Dolo 650 Tablet"), and a product code in it ("MZP00042.jpg") picks
 * that product outright. The staff member checks the matches before anything is uploaded; then each picture is
 * uploaded on its own with {@link #setImage}.
 *
 * <p>Only the admin does this, from the master catalogue: a picture is part of the product, the same in every store.
 */
@Service
@RequiredArgsConstructor
public class CatalogImageService {

    /** Most pictures matched at a time. */
    public static final int MAX_FILES = 1000;
    /** Most products offered to choose from for one picture. */
    static final int MAX_CHOICES = 8;

    private static final Pattern CODE = Pattern.compile("(?i)(?<![a-z0-9])MZP\\d{5}(?![0-9])");
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,5}$");
    /** What copying a file adds to its name: "dolo (1)", "dolo - Copy", "dolo copy 2". */
    private static final Pattern COPY = Pattern.compile("(?i)(\\s*(\\(\\d+\\)|-?\\s*copy(\\s*\\d+)?))+$");

    private final MedicineRepository medicines;
    private final FileStorageService fileStorage;

    /**
     * MATCHED: one product, sure. CHECK: one likely product, chosen but worth a look. CHOOSE: several fit; the staff
     * member picks. NONE: nothing fits.
     */
    public enum Status { MATCHED, CHECK, CHOOSE, NONE }

    public record Candidate(Long id, String code, String name, String manufacturer, String imageUrl) {
        static Candidate of(Medicine m) {
            return new Candidate(m.getId(), m.getProductCode(), m.getName(), m.getManufacturer(), m.getImageUrl());
        }
    }

    /** One picture: its file name, how sure the match is and the products to choose from (the match first). */
    public record Match(String file, Status status, List<Candidate> candidates) {}

    /** Matches each file name to a product in the master catalogue; nothing is changed. Admin only. */
    @Transactional(readOnly = true)
    public List<Match> match(User me, List<String> fileNames) {
        requireAdmin(me);
        if (fileNames == null || fileNames.isEmpty()) throw new BusinessException("Choose a folder with product pictures.");
        if (fileNames.size() > MAX_FILES) throw new BusinessException("Choose up to " + MAX_FILES + " pictures at a time.");
        List<Named> pool = medicines.findAll().stream().map(m -> new Named(m, tokens(m.getName()))).toList();
        return fileNames.stream().map(f -> match(f == null ? "" : f, pool)).toList();
    }

    /** A product with its name split into words, worked out once per request. */
    record Named(Medicine medicine, List<String> tokens) {}

    static Match match(String file, List<Named> pool) {
        String stem = stem(file);
        Matcher code = CODE.matcher(stem);
        if (code.find()) {
            String c = code.group();
            Optional<Named> byCode = pool.stream().filter(n -> c.equalsIgnoreCase(n.medicine().getProductCode())).findFirst();
            if (byCode.isPresent()) return new Match(file, Status.MATCHED, List.of(Candidate.of(byCode.get().medicine())));
            stem = stem.replace(c, " ");   // a code that isn't here: match by the rest of the name
        }
        List<String> words = tokens(stem);
        if (words.isEmpty()) return new Match(file, Status.NONE, List.of());

        int best = 0;
        List<Named> top = new ArrayList<>();
        for (Named n : pool) {
            int s = score(words, n.tokens());
            if (s == 0 || s < best) continue;
            if (s > best) { best = s; top.clear(); }
            top.add(n);
        }
        Status status;
        if (top.isEmpty()) {
            status = Status.NONE;
        } else if (best == 1) {
            // Every word of the product's name is in the file name, which says more ("dolo 650 front.jpg"): the
            // product with the longest such name is the likely one, but it's only a guess
            int longest = top.stream().mapToInt(n -> n.tokens().size()).max().orElse(0);
            List<Named> longestOnes = top.stream().filter(n -> n.tokens().size() == longest).toList();
            top = new ArrayList<>(longestOnes);
            status = top.size() == 1 ? Status.CHECK : Status.CHOOSE;
        } else {
            status = top.size() == 1 ? Status.MATCHED : Status.CHOOSE;
        }
        top.sort(Comparator.comparing((Named n) -> n.medicine().getName(), String.CASE_INSENSITIVE_ORDER));

        // Products sharing words with the file name follow, so the staff member can pick another
        List<Candidate> out = new ArrayList<>(top.stream().limit(MAX_CHOICES).map(n -> Candidate.of(n.medicine())).toList());
        Set<Long> listed = new HashSet<>(out.stream().map(Candidate::id).toList());
        record Near(Named named, int shared) {}
        pool.stream()
                .filter(n -> !listed.contains(n.medicine().getId()))
                .map(n -> new Near(n, overlap(words, n.tokens())))
                .filter(o -> o.shared() > 0)
                .sorted(Comparator.comparingInt((Near o) -> -o.shared())
                        .thenComparingInt(o -> o.named().tokens().size())
                        .thenComparing(o -> o.named().medicine().getName(), String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(0, MAX_CHOICES - out.size()))
                .forEach(o -> out.add(Candidate.of(o.named().medicine())));
        if (status == Status.NONE && !out.isEmpty()) status = Status.CHOOSE;
        return new Match(file, status, out);
    }

    /**
     * How well a file name's words fit a product's: 4 the same words in order, 3 the same words, 2 every file word
     * starts a different word of the name ("para 500" for "Paracetamol 500 mg"), 1 every word of the name is in the
     * file name, which has more ("dolo 650 front"), 0 not a fit.
     */
    static int score(List<String> file, List<String> name) {
        if (name.isEmpty()) return 0;
        if (file.equals(name)) return 4;
        if (file.size() == name.size() && covers(name, file, false)) return 3;
        if (covers(name, file, true)) return 2;
        if (covers(file, name, false)) return 1;
        return 0;
    }

    /** Whether each of {@code needles} matches a different word of {@code haystack} (exactly, or as its start). */
    static boolean covers(List<String> haystack, List<String> needles, boolean prefix) {
        if (needles.size() > haystack.size()) return false;
        boolean[] used = new boolean[haystack.size()];
        List<String> left = new ArrayList<>();
        for (String w : needles) {   // exact words first, so a prefix can't take a word another needle equals
            int k = indexOf(haystack, used, w, false);
            if (k < 0) left.add(w); else used[k] = true;
        }
        for (String w : left) {
            int k = prefix ? indexOf(haystack, used, w, true) : -1;
            if (k < 0) return false;
            used[k] = true;
        }
        return true;
    }

    private static int indexOf(List<String> haystack, boolean[] used, String w, boolean prefix) {
        for (int k = 0; k < haystack.size(); k++) {
            if (used[k]) continue;
            String h = haystack.get(k);
            if (h.equals(w) || (prefix && startsWord(h, w))) return k;
        }
        return -1;
    }

    /** "para" starts "paracetamol"; numbers and words under 3 letters have to be whole ("50" isn't "500"). */
    private static boolean startsWord(String word, String start) {
        return start.length() >= 3 && !Character.isDigit(start.charAt(0)) && word.startsWith(start);
    }

    /** How many of the file's words fit a word of the name; numbers alone don't count. */
    static int overlap(List<String> file, List<String> name) {
        int n = 0;
        boolean word = false;
        for (String w : file) {
            boolean hit = name.stream().anyMatch(h -> h.equals(w) || startsWord(h, w));
            if (hit) {
                n++;
                if (!Character.isDigit(w.charAt(0)) && w.length() >= 3) word = true;
            }
        }
        return word ? n : 0;
    }

    /** The file name without its folder, extension and copy marks ("Dolo 650 (1).jpg" is "Dolo 650"). */
    static String stem(String file) {
        String s = file.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        s = EXTENSION.matcher(s).replaceFirst("");
        return COPY.matcher(s.trim()).replaceFirst("").trim();
    }

    /** Lower-case words and numbers, a number split from the word it touches: "Dolo-650mg" is [dolo, 650, mg]. */
    static List<String> tokens(String s) {
        if (s == null) return List.of();
        String t = s.toLowerCase(Locale.ROOT)
                .replaceAll("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return t.isEmpty() ? List.of() : List.of(t.split(" "));
    }

    /**
     * Puts {@code file} on the product as its picture. A picture the product already has is replaced only with
     * {@code replace}; the replaced upload is deleted once the change is saved. Admin only.
     */
    @Transactional
    public Medicine setImage(User me, Long medicineId, MultipartFile file, boolean replace) {
        requireAdmin(me);
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That product isn't in the catalogue any more."));
        if (m.getImageUrl() != null && !replace) {
            throw new BusinessException(m.getName() + " already has a picture.");
        }
        String old = m.getImageUrl();
        String next = fileStorage.storeMedicineImage(file);
        m.setImageUrl(next);
        afterSave(() -> fileStorage.deleteMedicineImage(old), () -> fileStorage.deleteMedicineImage(next));
        return m;
    }

    private static void requireAdmin(User me) {
        if (!me.isAdmin()) throw new BusinessException("Only the admin adds product pictures.");
    }

    /** Runs {@code saved} once the transaction commits, {@code undone} if it rolls back (now, outside one). */
    private static void afterSave(Runnable saved, Runnable undone) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            saved.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                (status == STATUS_COMMITTED ? saved : undone).run();
            }
        });
    }
}
