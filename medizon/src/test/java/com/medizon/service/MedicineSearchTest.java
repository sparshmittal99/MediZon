package com.medizon.service;

import com.medizon.domain.Medicine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MedicineSearchTest {

    private static final AtomicLong IDS = new AtomicLong();

    private static Medicine med(String name, String generic, String strength, String form, String maker, String category) {
        Medicine m = new Medicine();
        m.setId(IDS.incrementAndGet());
        m.setName(name);
        m.setGenericName(generic);
        m.setStrength(strength);
        m.setDosageForm(form);
        m.setManufacturer(maker);
        m.setCategory(category);
        return m;
    }

    private static final Medicine DOLO = med("Dolo 650 Tablet", "Paracetamol", "650 mg", "Tablet", "Micro Labs", "Fever & Pain");
    private static final Medicine CROCIN = med("Crocin Advance", "Paracetamol", "500 mg", "Tablet", "GSK", "Fever & Pain");
    private static final Medicine AZEE = med("Azee 500", "Azithromycin", "500 mg", "Tablet", "Cipla", "Antibiotics");
    private static final Medicine OMEZ = med("Omez 20", "Omeprazole", "20 mg", "Capsule", "Dr Reddy's", "Stomach Care");
    private static final Medicine PAN_D = med("Pan-D Capsule", "Pantoprazole + Domperidone", "40 mg", "Capsule", "Alkem", "Stomach Care");
    private static final Medicine BENADRYL = med("Benadryl Cough Syrup", "Diphenhydramine", "100 ml", "Syrup", "J&J", "Cough & Cold");
    private static final List<Medicine> SHOP = List.of(DOLO, CROCIN, AZEE, OMEZ, PAN_D, BENADRYL);

    private static List<Medicine> search(String q) { return MedicineSearch.rank(SHOP, q); }

    @Test
    void partOfANameFindsIt() {
        assertThat(search("dol")).first().isEqualTo(DOLO);
        assertThat(search("azithro")).containsExactly(AZEE);
        assertThat(search("pan d")).first().isEqualTo(PAN_D);
    }

    @Test
    void misspellingsAndSpellingsBySoundFindIt() {
        assertThat(search("paracitamol")).containsExactlyInAnyOrder(DOLO, CROCIN);
        assertThat(search("parasetamol")).containsExactlyInAnyOrder(DOLO, CROCIN);
        assertThat(search("omeprazol")).containsExactly(OMEZ);
        assertThat(search("azitromycin")).containsExactly(AZEE);
        assertThat(search("benadril")).containsExactly(BENADRYL);
        assertThat(search("crocn")).first().isEqualTo(CROCIN);
    }

    @Test
    void spacingStrengthAndExtraWordsDontGetInTheWay() {
        assertThat(search("dolo650")).first().isEqualTo(DOLO);
        assertThat(search("paracetamol 500")).containsExactly(CROCIN);
        assertThat(search("i need crocin tablet please")).containsExactly(CROCIN);
        assertThat(search("cough syrup")).containsExactly(BENADRYL);
    }

    @Test
    void theNameAsTypedComesFirst() {
        Medicine azeeSyrup = med("Azee Dry Syrup", "Azithromycin", "200 mg", "Syrup", "Cipla", "Antibiotics");
        assertThat(MedicineSearch.rank(List.of(azeeSyrup, AZEE), "azee 500")).first().isEqualTo(AZEE);
    }

    @Test
    void unrelatedWordsFindNothing() {
        assertThat(search("insulin")).isEmpty();
        assertThat(search("")).isEmpty();
        assertThat(search("   ")).isEmpty();
    }

    @Test
    void saysWhenTheSpellingWasFixed() {
        assertThat(MedicineSearch.rank(SHOP, "dolo", m -> m).get(0).exact()).isTrue();
        assertThat(MedicineSearch.rank(SHOP, "omeprazol", m -> m).get(0).exact()).isTrue();   // the start of omeprazole
        assertThat(MedicineSearch.rank(SHOP, "benadril", m -> m).get(0).exact()).isFalse();
    }
}
