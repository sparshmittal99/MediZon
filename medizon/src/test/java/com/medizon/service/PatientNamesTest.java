package com.medizon.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Which family member a prescription's patient name belongs to; when in doubt, nobody (it goes under "Others"). */
class PatientNamesTest {

    private static final List<String> FAMILY = List.of("Ravi Kumar Sharma", "Asha Sharma", "Rohan Sharma");

    private static Optional<String> match(String written) {
        return PatientNames.match(written, FAMILY, Function.identity());
    }

    @Test
    void ignoresTitlesCaseAndPunctuation() {
        assertEquals(Optional.of("Asha Sharma"), match("Mrs. ASHA  sharma"));
        assertEquals(Optional.of("Asha Sharma"), match("Smt Asha Sharma"));
        assertEquals(Optional.of("Asha Sharma"), match("Sharma, Asha"));
    }

    @Test
    void shorterOrLongerFormsOfTheSameName() {
        assertEquals(Optional.of("Ravi Kumar Sharma"), match("Ravi Kumar"));
        assertEquals(Optional.of("Ravi Kumar Sharma"), match("Mr Ravi Sharma"));
        assertEquals(Optional.of("Rohan Sharma"), match("Master Rohan Kumar Sharma"));
    }

    @Test
    void aFirstNameOnlyOneMemberHas() {
        assertEquals(Optional.of("Asha Sharma"), match("Asha"));
    }

    @Test
    void noMatchOrAmbiguousGoesToOthers() {
        assertEquals(Optional.empty(), match("Priya Verma"));
        assertEquals(Optional.empty(), match("Sharma"));            // three Sharmas: don't guess
        assertEquals(Optional.empty(), match(""));
        assertEquals(Optional.empty(), match(null));
        assertEquals(Optional.empty(), PatientNames.match("Asha", List.of("Asha Sharma", "Asha Verma"), Function.identity()));
    }

    @Test
    void handlesAccents() {
        assertEquals(Optional.of("Jose Fernandes"), PatientNames.match("José Fernandes", List.of("Jose Fernandes"), Function.identity()));
    }
}
