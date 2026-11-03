package com.medizon.service;

import com.medizon.domain.Medicine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a medicine is made of, read from its salt ({@link Medicine#getGenericName()}), e.g. "Amlodipine besylate 5 mg"
 * or "Paracetamol 325 mg + Ibuprofen 400 mg". Two products with the same composition and dosage form can stand in for
 * each other: the salt-form word ("besylate", "hydrochloride" ...) is ignored, units are made comparable (1 g = 1000 mg),
 * and the order of ingredients doesn't matter.
 */
public record Composition(List<Ingredient> ingredients, String form) {

    /** One active ingredient; {@code amount} in {@code unit} (mg, mcg, iu, ml or %), null when the salt gives none. */
    public record Ingredient(String name, BigDecimal amount, String unit) {
        public boolean hasStrength() { return amount != null; }

        String key() { return name + (amount == null ? "" : "@" + amount.stripTrailingZeros().toPlainString() + unit); }
    }

    private static final Pattern SPLIT = Pattern.compile("\\s*(?:\\+|/|&|\\band\\b|\\bwith\\b|,(?!\\d{3}))\\s*");
    private static final Pattern PER_VOLUME = Pattern.compile("\\bper(\\d+(?:\\.\\d+)?)?ml\\b");
    private static final Pattern STRENGTH = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(mg|mcg|µg|ug|g|gm|iu|ml|%)(?![a-z])");
    /** Salt forms: after the ingredient's name they don't change what the medicine is ("Amlodipine besylate"). */
    private static final Set<String> SALT_FORMS = Set.of("hydrochloride", "hcl", "besylate", "besilate", "calcium",
            "sodium", "potassium", "maleate", "sulphate", "sulfate", "succinate", "tartrate", "citrate", "fumarate",
            "mesylate", "dihydrate", "monohydrate", "trihydrate", "anhydrous", "magnesium", "bromide", "acetate", "phosphate");
    /** Words that are never part of a name. */
    private static final Set<String> FILLER = Set.of("ip", "bp", "usp", "tablet", "tablets", "capsule", "capsules", "syrup",
            "oral", "suspension", "solution", "each", "contains", "per",
            // marketing words in salts like "Paracetamol Fast Relief Tablets 500 mg"
            "fast", "relief", "rapid", "quick", "extra", "advance", "advanced", "plus", "active", "new", "regular",
            "strength", "maximum", "max", "film", "coated", "uncoated");

    public static Composition of(Medicine m) {
        return parse(m.getGenericName(), m.getStrength(), m.getDosageForm());
    }

    static Composition parse(String salt, String strength, String dosageForm) {
        List<Ingredient> out = new ArrayList<>();
        String text = salt == null ? "" : salt.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)", " ")
                .replaceAll("(\\d),(\\d{3})", "$1$2").replaceAll("\\bw/[vw]\\b", " ")
                // "125 mg/5 ml", "125 mg per 5 ml": a liquid's concentration, kept with the strength's unit
                .replaceAll("\\s*(?:/|\\bper\\b)\\s*(\\d+(?:\\.\\d+)?)?\\s*ml\\b", " per$1ml ");
        for (String part : SPLIT.split(text)) {
            Ingredient i = ingredient(part);
            if (i != null) out.add(i);
        }
        // A single ingredient with no strength in the salt takes the product's strength field ("650 mg")
        if (out.size() == 1 && !out.get(0).hasStrength() && strength != null) {
            Matcher s = STRENGTH.matcher(strength.toLowerCase(Locale.ROOT).replaceAll("(\\d),(\\d{3})", "$1$2"));
            if (s.find()) out.set(0, withStrength(out.get(0).name(), s.group(1), s.group(2)));
        }
        out.sort(Comparator.comparing(Ingredient::name));
        return new Composition(List.copyOf(out), form(dosageForm));
    }

    private static Ingredient ingredient(String part) {
        String per = "";
        Matcher volume = PER_VOLUME.matcher(part);
        if (volume.find()) {
            per = "/" + (volume.group(1).isEmpty() ? "1" : new BigDecimal(volume.group(1)).stripTrailingZeros().toPlainString()) + "ml";
            part = volume.replaceAll(" ");
        }
        Matcher s = STRENGTH.matcher(part);
        String amount = null, unit = null;
        if (s.find()) {
            amount = s.group(1);
            unit = s.group(2);
            part = part.substring(0, s.start()) + " " + part.substring(s.end());
        }
        if (amount == null) per = "";
        List<String> words = new ArrayList<>();
        for (String w : part.replaceAll("[^a-z0-9\\- ]", " ").trim().split("\\s+")) {
            if (!w.isBlank() && !w.matches("\\d+(\\.\\d+)?")) words.add(w);
        }
        // A salt form after the name goes ("Atorvastatin calcium"); as the name itself it stays ("Calcium carbonate")
        List<String> kept = new ArrayList<>();
        for (String w : words) {
            if (FILLER.contains(w) || !kept.isEmpty() && SALT_FORMS.contains(w)) continue;
            kept.add(w);
        }
        String name = String.join(" ", kept);
        if (name.isBlank()) return null;
        if (amount == null) return new Ingredient(name, null, null);
        Ingredient i = withStrength(name, amount, unit);
        return new Ingredient(i.name(), i.amount(), i.unit() + per);
    }

    private static Ingredient withStrength(String name, String amount, String unit) {
        BigDecimal a = new BigDecimal(amount);
        String u = switch (unit) {
            case "g", "gm" -> { a = a.multiply(BigDecimal.valueOf(1000)); yield "mg"; }
            case "µg", "ug" -> "mcg";
            default -> unit;
        };
        return new Ingredient(name, a.stripTrailingZeros(), u);
    }

    /** "Tablet", "Film-coated tablet", "Tablet SR" -> "tablet"; an extended-release form is kept apart. */
    private static String form(String dosageForm) {
        String f = dosageForm == null ? "" : dosageForm.toLowerCase(Locale.ROOT);
        String release = f.matches(".*\\b(sr|er|xr|cr|mr|od|retard|extended|sustained|controlled|prolonged)\\b.*") ? "-sr" : "";
        for (String base : List.of("tablet", "capsule", "syrup", "suspension", "drops", "drop", "injection", "sachet",
                "powder", "cream", "gel", "ointment", "spray", "inhaler", "device", "strip")) {
            if (f.contains(base)) return (base.equals("drop") ? "drops" : base) + release;
        }
        return f.trim() + release;
    }

    /** Every ingredient has a strength: without one, "same salt" can't be told apart from "same salt, other dose". */
    public boolean isComparable() {
        return !ingredients.isEmpty() && ingredients.stream().allMatch(Ingredient::hasStrength);
    }

    /** Same ingredients at the same strengths, in the same dosage form. */
    public boolean sameAs(Composition other) {
        return isComparable() && other.isComparable() && form.equals(other.form) && key().equals(other.key());
    }

    public String key() {
        return ingredients.stream().map(Ingredient::key).collect(Collectors.joining("+"));
    }

    /** Ingredient names only, for interaction checks. */
    public List<String> names() {
        return ingredients.stream().map(Ingredient::name).toList();
    }
}
