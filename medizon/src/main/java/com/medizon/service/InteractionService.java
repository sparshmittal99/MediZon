package com.medizon.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks a set of medicines one person takes for well-known interactions and doubled-up salts. It works from a short,
 * curated list of the interactions every pharmacist knows (bleeding with blood thinners and painkillers, serotonin
 * syndrome, dangerous drops in blood pressure, antibiotics blocked by calcium ...), not a full drug database: finding
 * nothing never means a combination is safe, and every answer says so.
 */
@Service
public class InteractionService {

    public static final String DISCLAIMER = "This checks a list of well-known interactions and repeated salts only. "
            + "Not finding a problem doesn't mean a combination is safe: ask your doctor or our pharmacist, and never "
            + "stop or change a prescribed medicine on your own.";

    public enum Severity {
        SERIOUS("Serious"), DUPLICATE("Duplicate"), CAUTION("Caution");

        private final String label;

        Severity(String label) { this.label = label; }

        public String getLabel() { return label; }
    }

    /** A medicine to check: what to call it and its ingredient names (from {@link Composition#names()}). */
    public record Item(String label, List<String> ingredients) {}

    public record Finding(Severity severity, String first, String second, String note) {}

    /** A kind of medicine, found by its ingredient names. {@code noDouble}: why two of this kind shouldn't be combined. */
    record Group(String label, Set<String> words, String noDouble) {
        boolean has(String ingredient) {
            for (String w : ingredient.split("[^a-z]+")) if (words.contains(w)) return true;
            // multi-word names ("glyceryl trinitrate", "acetylsalicylic acid")
            return words.stream().anyMatch(w -> w.contains(" ") && ingredient.contains(w));
        }
    }

    record Rule(String a, String b, Severity severity, String note) {}

    private static final Map<String, Group> GROUPS = new java.util.LinkedHashMap<>();
    private static final List<Rule> RULES = new ArrayList<>();

    private static void group(String id, String label, String noDouble, String... words) {
        GROUPS.put(id, new Group(label, Set.of(words), noDouble));
    }

    private static void rule(String a, String b, Severity s, String note) {
        RULES.add(new Rule(a, b, s, note));
    }

    static {
        group("nsaid", "NSAID painkiller", "Two NSAID painkillers together raise the risk of stomach bleeding and kidney damage without "
                        + "working better.", "ibuprofen", "diclofenac", "naproxen", "aceclofenac", "ketorolac", "etoricoxib",
                "mefenamic", "piroxicam", "indomethacin", "celecoxib", "nimesulide", "aspirin", "acetylsalicylic acid");
        group("anticoagulant", "blood thinner", "Two blood thinners together greatly raise the risk of bleeding.",
                "warfarin", "acenocoumarol", "apixaban", "rivaroxaban", "dabigatran", "heparin", "enoxaparin");
        group("antiplatelet", "antiplatelet", null, "clopidogrel", "prasugrel", "ticagrelor", "aspirin", "acetylsalicylic acid");
        group("ace", "ACE inhibitor", "Two ACE inhibitors shouldn't be combined.", "ramipril", "enalapril", "lisinopril",
                "perindopril", "captopril");
        group("arb", "ARB blood-pressure medicine", "Two ARB blood-pressure medicines shouldn't be combined.", "telmisartan",
                "losartan", "olmesartan", "valsartan", "irbesartan", "candesartan", "azilsartan");
        group("ksparing", "potassium-sparing diuretic", null, "spironolactone", "eplerenone", "amiloride");
        group("potassium", "potassium supplement", null, "potassium chloride", "potassium citrate");
        group("diuretic", "diuretic", null, "hydrochlorothiazide", "chlorthalidone", "indapamide", "furosemide", "torsemide");
        group("sulfonylurea", "sulfonylurea (sugar medicine)", "Two sulfonylureas together can push blood sugar dangerously low.",
                "glimepiride", "gliclazide", "glibenclamide", "glipizide");
        group("insulin", "insulin", null, "insulin");
        group("metformin", "metformin", null, "metformin");
        group("statin", "statin", "Two cholesterol statins together raise the risk of muscle damage.", "atorvastatin",
                "rosuvastatin", "simvastatin", "pravastatin", "lovastatin", "pitavastatin");
        group("statin3a4", "statin", null, "atorvastatin", "simvastatin", "lovastatin");
        group("cyp3a4", "strong enzyme blocker", null, "clarithromycin", "erythromycin", "ketoconazole", "itraconazole",
                "voriconazole");
        group("bleedboost", "antibiotic/antifungal that boosts warfarin", null, "metronidazole", "fluconazole", "clarithromycin",
                "erythromycin", "ciprofloxacin", "levofloxacin", "cotrimoxazole", "sulfamethoxazole");
        group("qt", "medicine that affects heart rhythm (QT)", null, "azithromycin", "clarithromycin", "erythromycin",
                "levofloxacin", "moxifloxacin", "ciprofloxacin", "ondansetron", "domperidone", "hydroxychloroquine",
                "amiodarone", "haloperidol", "citalopram", "escitalopram");
        group("ssri", "antidepressant (SSRI/SNRI)", "Two SSRI/SNRI antidepressants together can cause serotonin syndrome.",
                "sertraline", "fluoxetine", "escitalopram", "citalopram", "paroxetine", "fluvoxamine", "venlafaxine", "duloxetine",
                "desvenlafaxine");
        group("serotonergic", "medicine that raises serotonin", null, "tramadol", "linezolid", "sumatriptan", "rizatriptan",
                "dextromethorphan", "tapentadol");
        group("sedative", "sleeping/anxiety medicine", "Two sleeping or anxiety medicines together can dangerously slow breathing.",
                "alprazolam", "clonazepam", "diazepam", "lorazepam", "etizolam", "zolpidem", "nitrazepam", "chlordiazepoxide");
        group("opioid", "opioid painkiller", "Two opioid painkillers together can dangerously slow breathing.", "tramadol",
                "codeine", "tapentadol", "morphine", "oxycodone", "fentanyl");
        group("sedating", "drowsy antihistamine", null, "chlorpheniramine", "promethazine", "diphenhydramine", "hydroxyzine",
                "doxylamine", "pheniramine");
        group("antihistamine", "antihistamine", "Two anti-allergy medicines together add drowsiness without more benefit.",
                "cetirizine", "levocetirizine", "fexofenadine", "loratadine", "desloratadine", "bilastine", "chlorpheniramine",
                "promethazine", "diphenhydramine", "hydroxyzine", "pheniramine");
        group("ppi", "acid reducer (PPI)", "Two acid reducers of the same kind (PPIs) aren't needed together.", "omeprazole",
                "esomeprazole", "pantoprazole", "rabeprazole", "lansoprazole");
        group("ppi2c19", "omeprazole/esomeprazole", null, "omeprazole", "esomeprazole");
        group("pde5", "erectile dysfunction medicine", null, "sildenafil", "tadalafil", "vardenafil");
        group("nitrate", "nitrate heart medicine", null, "isosorbide", "nitroglycerin", "glyceryl trinitrate", "nicorandil");
        group("thyroxine", "thyroid hormone", null, "levothyroxine", "thyroxine");
        group("binder", "calcium, iron or antacid", null, "calcium", "ferrous", "ferric", "iron", "aluminium", "aluminum",
                "magnesium hydroxide", "sucralfate", "zinc", "antacid");
        group("quinolone", "quinolone or tetracycline antibiotic", null, "ciprofloxacin", "levofloxacin", "ofloxacin",
                "moxifloxacin", "norfloxacin", "doxycycline", "tetracycline", "minocycline");
        group("methotrexate", "methotrexate", null, "methotrexate");
        group("trimethoprim", "trimethoprim", null, "trimethoprim", "cotrimoxazole");
        group("lithium", "lithium", null, "lithium");
        group("digoxin", "digoxin", null, "digoxin");
        group("digoxinboost", "medicine that raises digoxin", null, "amiodarone", "clarithromycin", "verapamil");
        group("betablocker", "beta blocker", "Two beta blockers together can slow the heart too much.", "metoprolol",
                "atenolol", "propranolol", "bisoprolol", "carvedilol", "nebivolol");
        group("ratecc", "verapamil/diltiazem", null, "verapamil", "diltiazem");
        group("allopurinol", "allopurinol", null, "allopurinol", "febuxostat");
        group("thiopurine", "azathioprine", null, "azathioprine", "mercaptopurine");
        group("theophylline", "theophylline", null, "theophylline", "aminophylline", "doxofylline", "tizanidine");
        group("cipro", "ciprofloxacin/fluvoxamine", null, "ciprofloxacin", "fluvoxamine");
        group("nitroimidazole", "metronidazole/tinidazole", null, "metronidazole", "tinidazole");
        group("paracetamol", "paracetamol", null, "paracetamol", "acetaminophen");
        group("alcohol", "alcohol", null, "alcohol", "ethanol", "beer", "wine", "whisky", "whiskey", "vodka", "rum", "liquor",
                "daru", "sharab");

        Severity S = Severity.SERIOUS, C = Severity.CAUTION;
        rule("anticoagulant", "nsaid", S, "A blood thinner with an NSAID painkiller sharply raises the risk of serious bleeding.");
        rule("anticoagulant", "antiplatelet", S, "A blood thinner with an antiplatelet raises the risk of serious bleeding; only "
                + "combine them if your doctor planned it.");
        rule("anticoagulant", "bleedboost", S, "This antibiotic or antifungal can make the blood thinner much stronger and cause "
                + "bleeding; your doctor may need to check your INR.");
        rule("antiplatelet", "nsaid", C, "An antiplatelet with an NSAID painkiller raises the risk of stomach bleeding.");
        rule("antiplatelet", "ppi2c19", C, "Omeprazole and esomeprazole can make clopidogrel work less well; pantoprazole is "
                + "usually preferred. Ask your doctor.");
        rule("ace", "arb", S, "An ACE inhibitor with an ARB raises the risk of kidney problems and high potassium.");
        rule("ace", "ksparing", C, "Together they can raise potassium too high; potassium levels need checking.");
        rule("arb", "ksparing", C, "Together they can raise potassium too high; potassium levels need checking.");
        rule("ace", "potassium", C, "Potassium supplements with an ACE inhibitor can raise potassium too high.");
        rule("arb", "potassium", C, "Potassium supplements with an ARB can raise potassium too high.");
        rule("ksparing", "potassium", S, "A potassium-sparing diuretic with potassium supplements can raise potassium dangerously.");
        rule("ace", "nsaid", C, "NSAID painkillers can weaken blood-pressure control and strain the kidneys.");
        rule("arb", "nsaid", C, "NSAID painkillers can weaken blood-pressure control and strain the kidneys.");
        rule("statin3a4", "cyp3a4", S, "This antibiotic or antifungal raises statin levels and the risk of serious muscle damage.");
        rule("ssri", "serotonergic", S, "Together they can cause serotonin syndrome (agitation, fever, shaking, fast heartbeat).");
        rule("ssri", "nsaid", C, "An SSRI with an NSAID painkiller raises the risk of stomach bleeding.");
        rule("ssri", "anticoagulant", C, "An SSRI with a blood thinner raises the risk of bleeding.");
        rule("sedative", "opioid", S, "A sleeping or anxiety medicine with an opioid can dangerously slow breathing.");
        rule("sedative", "sedating", C, "Both cause drowsiness; together they add up. Avoid driving.");
        rule("opioid", "sedating", C, "Both cause drowsiness; together they add up. Avoid driving.");
        rule("pde5", "nitrate", S, "Together they can drop blood pressure dangerously low. Never combine them.");
        rule("thyroxine", "binder", C, "Calcium, iron and antacids stop thyroid tablets being absorbed: take them at least "
                + "4 hours apart.");
        rule("quinolone", "binder", C, "Calcium, iron, zinc and antacids stop this antibiotic being absorbed: take the "
                + "antibiotic 2 hours before or 6 hours after.");
        rule("methotrexate", "nsaid", S, "NSAID painkillers can raise methotrexate to toxic levels.");
        rule("methotrexate", "trimethoprim", S, "Trimethoprim with methotrexate can seriously harm the blood and bone marrow.");
        rule("lithium", "nsaid", S, "NSAID painkillers can raise lithium to toxic levels.");
        rule("lithium", "ace", S, "ACE inhibitors can raise lithium to toxic levels.");
        rule("lithium", "arb", S, "ARBs can raise lithium to toxic levels.");
        rule("lithium", "diuretic", S, "Diuretics can raise lithium to toxic levels.");
        rule("digoxin", "digoxinboost", S, "This can raise digoxin to toxic levels.");
        rule("betablocker", "ratecc", S, "Together they can slow the heart dangerously.");
        rule("betablocker", "insulin", C, "Beta blockers can hide the warning signs of low blood sugar.");
        rule("betablocker", "sulfonylurea", C, "Beta blockers can hide the warning signs of low blood sugar.");
        rule("allopurinol", "thiopurine", S, "Allopurinol raises azathioprine to toxic levels; the dose has to be changed by a doctor.");
        rule("cipro", "theophylline", S, "This can raise theophylline or tizanidine to dangerous levels.");
        rule("alcohol", "nitroimidazole", S, "Alcohol with metronidazole or tinidazole causes severe vomiting, flushing and a "
                + "racing heart. Avoid alcohol during the course and for 3 days after.");
        rule("alcohol", "sedative", S, "Alcohol with sleeping or anxiety medicines can dangerously slow breathing.");
        rule("alcohol", "opioid", S, "Alcohol with opioid painkillers can dangerously slow breathing.");
        rule("alcohol", "paracetamol", C, "Regular drinking with paracetamol raises the risk of liver damage.");
        rule("alcohol", "metformin", C, "Alcohol with metformin raises the risk of low blood sugar and lactic acidosis.");
        rule("alcohol", "sulfonylurea", C, "Alcohol with this sugar medicine can push blood sugar dangerously low.");
        rule("alcohol", "insulin", C, "Alcohol with insulin can push blood sugar dangerously low.");
        rule("alcohol", "nsaid", C, "Alcohol with NSAID painkillers raises the risk of stomach bleeding.");
        rule("alcohol", "anticoagulant", C, "Alcohol can change how strongly blood thinners work and raises the risk of bleeding.");
        rule("alcohol", "antihistamine", C, "Alcohol adds to the drowsiness anti-allergy medicines can cause, strongly so "
                + "with drowsy ones like chlorpheniramine. Avoid driving.");
    }

    /** A name the check knows on its own, without the catalogue: "aspirin", "warfarin", "alcohol" ... */
    public boolean knows(String ingredient) {
        String i = normalize(ingredient);
        return !i.isEmpty() && GROUPS.values().stream().anyMatch(g -> g.has(i));
    }

    public boolean isAlcohol(String text) {
        return GROUPS.get("alcohol").has(normalize(text));
    }

    /** Every problem among {@code items}, taken by the same person: serious first, then duplicates, then cautions. */
    public List<Finding> check(List<Item> items) {
        List<Finding> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int x = 0; x < items.size(); x++) {
            for (int y = x + 1; y < items.size(); y++) {
                Item a = items.get(x), b = items.get(y);
                for (Finding f : pair(a, b)) {
                    if (seen.add(f.first() + "|" + f.second() + "|" + f.note())) out.add(f);
                }
            }
        }
        out.sort(Comparator.comparing(Finding::severity));
        return out;
    }

    private List<Finding> pair(Item a, Item b) {
        List<Finding> out = new ArrayList<>();
        List<String> ia = a.ingredients().stream().map(InteractionService::normalize).toList();
        List<String> ib = b.ingredients().stream().map(InteractionService::normalize).toList();

        // The same salt in both: easy to take twice the dose without noticing
        Set<String> shared = ia.stream().filter(ib::contains).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!shared.isEmpty()) {
            out.add(new Finding(Severity.DUPLICATE, a.label(), b.label(), "Both contain " + String.join(" and ", shared)
                    + ". Taking both means a double dose: check with your doctor or pharmacist which one to take."));
        }
        Set<String> ga = groups(ia), gb = groups(ib);
        // Two different medicines of a kind that shouldn't be doubled up
        for (String g : ga) {
            Group group = GROUPS.get(g);
            if (group.noDouble() != null && gb.contains(g) && shared.isEmpty()) {
                out.add(new Finding(Severity.DUPLICATE, a.label(), b.label(), "Both are a " + group.label() + ". " + group.noDouble()));
            }
        }
        for (Rule r : RULES) {
            if (ga.contains(r.a()) && gb.contains(r.b()) || ga.contains(r.b()) && gb.contains(r.a())) {
                out.add(new Finding(r.severity(), a.label(), b.label(), r.note()));
            }
        }
        // Two different medicines that both affect heart rhythm
        if (ga.contains("qt") && gb.contains("qt") && shared.isEmpty()) {
            out.add(new Finding(Severity.CAUTION, a.label(), b.label(), "Both can affect heart rhythm (QT prolongation); "
                    + "together the risk is higher, especially with heart problems."));
        }
        return out;
    }

    private static Set<String> groups(List<String> ingredients) {
        Set<String> out = new LinkedHashSet<>();
        GROUPS.forEach((id, g) -> {
            if (ingredients.stream().anyMatch(g::has)) out.add(id);
        });
        return out;
    }

    private static final Pattern NON_LETTERS = Pattern.compile("[^a-z ]+");

    static String normalize(String s) {
        return s == null ? "" : NON_LETTERS.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }
}
