package com.medizon.service;

import com.medizon.service.InteractionService.Finding;
import com.medizon.service.InteractionService.Item;
import com.medizon.service.InteractionService.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Same-salt matching for cheaper brands, and the interaction and duplicate check. */
class SubstitutesAndInteractionsTest {

    private final InteractionService check = new InteractionService();

    private static Composition c(String salt, String strength, String form) {
        return Composition.parse(salt, strength, form);
    }

    // ------------------------------------------------------------------ same salt

    @Test
    void saltFormsUnitsAndOrderDontMatter() {
        assertThat(c("Amlodipine besylate 5 mg", null, "Tablet").sameAs(c("Amlodipine 5mg", null, "Tablet"))).isTrue();
        assertThat(c("Metformin hydrochloride 500 mg", null, "Tablet").sameAs(c("Metformin 500 mg IP", null, "Film-coated tablet"))).isTrue();
        assertThat(c("Paracetamol 325 mg + Ibuprofen 400 mg", null, "Tablet")
                .sameAs(c("Ibuprofen 400mg / Paracetamol 325mg", null, "Tablet"))).isTrue();
        assertThat(c("Amoxicillin 0.5 g", null, "Capsule").sameAs(c("Amoxicillin 500 mg", null, "Capsule"))).isTrue();
        assertThat(c("Cholecalciferol 60,000 IU", null, "Capsule").sameAs(c("Cholecalciferol 60000 IU", null, "Capsule"))).isTrue();
        assertThat(c("Paracetamol Fast Relief Tablets 500 mg", null, "Tablet").sameAs(c("Paracetamol 500 mg", "500 mg", "Tablet"))).isTrue();
    }

    @Test
    void strengthFormAndReleaseMustMatch() {
        assertThat(c("Paracetamol 650 mg", null, "Tablet").sameAs(c("Paracetamol 500 mg", null, "Tablet"))).isFalse();
        assertThat(c("Paracetamol 500 mg", null, "Tablet").sameAs(c("Paracetamol 500 mg", null, "Capsule"))).isFalse();
        assertThat(c("Metformin 500 mg", null, "Tablet").sameAs(c("Metformin 500 mg", null, "Tablet SR"))).isFalse();
        assertThat(c("Paracetamol 125 mg/5 ml", null, "Syrup").sameAs(c("Paracetamol 250 mg per 5 ml", null, "Syrup"))).isFalse();
        assertThat(c("Paracetamol 125 mg/5 ml", null, "Syrup").sameAs(c("Paracetamol 125mg per 5ml", null, "Syrup"))).isTrue();
        // An extra ingredient makes it a different medicine
        assertThat(c("Paracetamol 500 mg", null, "Tablet").sameAs(c("Paracetamol 500 mg + Caffeine 65 mg", null, "Tablet"))).isFalse();
    }

    @Test
    void withoutAStrengthNothingIsMatched() {
        assertThat(c("Multivitamin and minerals", null, "Tablet").isComparable()).isFalse();
        assertThat(c("Oral rehydration salts (WHO formula)", null, "Sachet").isComparable()).isFalse();
        // ... unless the product's strength field gives it
        assertThat(c("Glimepiride", "2 mg", "Tablet").sameAs(c("Glimepiride 2 mg", null, "Tablet"))).isTrue();
    }

    @Test
    void aSaltFormAsTheNameItselfIsKept() {
        assertThat(c("Calcium carbonate 500 mg", null, "Tablet").names()).containsExactly("calcium carbonate");
        assertThat(c("Atorvastatin calcium 10 mg", null, "Tablet").names()).containsExactly("atorvastatin");
    }

    // ------------------------------------------------------------------ interactions

    private static Item item(String label, String salt) {
        return new Item(label, c(salt, null, "Tablet").names());
    }

    @Test
    void bloodThinnerWithPainkillerIsSerious() {
        List<Finding> f = check.check(List.of(item("Warf 5", "Warfarin sodium 5 mg"), item("Brufen 400", "Ibuprofen 400 mg")));
        assertThat(f).first().extracting(Finding::severity).isEqualTo(Severity.SERIOUS);
        assertThat(f.get(0).note()).contains("bleeding");
    }

    @Test
    void theSameSaltUnderTwoBrandsIsADuplicate() {
        List<Finding> f = check.check(List.of(item("Dolo 650", "Paracetamol 650 mg"),
                item("Combiflam", "Ibuprofen 400 mg + Paracetamol 325 mg")));
        assertThat(f).extracting(Finding::severity).contains(Severity.DUPLICATE);
        assertThat(f).anyMatch(x -> x.note().contains("paracetamol"));
    }

    @Test
    void twoOfAKindThatShouldntBeDoubledUp() {
        List<Finding> f = check.check(List.of(item("Pan 40", "Pantoprazole 40 mg"), item("Omez", "Omeprazole 20 mg")));
        assertThat(f).singleElement().extracting(Finding::severity).isEqualTo(Severity.DUPLICATE);
    }

    @Test
    void alcoholAndSafeCombinations() {
        assertThat(check.check(List.of(new Item("alcohol", List.of("alcohol")), item("Flagyl", "Metronidazole 400 mg"))))
                .first().extracting(Finding::severity).isEqualTo(Severity.SERIOUS);
        assertThat(check.check(List.of(item("Metformin", "Metformin hydrochloride 500 mg"), item("Cetirizine", "Cetirizine 10 mg"))))
                .isEmpty();
        assertThat(check.isAlcohol("Alcohol")).isTrue();
        assertThat(check.knows("aspirin")).isTrue();
        assertThat(check.knows("pregnancy")).isFalse();
    }

    @Test
    void seriousComesFirst() {
        List<Finding> f = check.check(List.of(item("Thyrox", "Levothyroxine sodium 50 mcg"),
                item("Shelcal", "Calcium carbonate 500 mg"), item("Sildenafil", "Sildenafil 50 mg"),
                item("Isordil", "Isosorbide dinitrate 5 mg")));
        assertThat(f).extracting(Finding::severity).containsExactly(Severity.SERIOUS, Severity.CAUTION);
    }
}
