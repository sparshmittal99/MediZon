package com.medizon.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PrescriptionScoreTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static final PrescriptionReading.Line DOLO =
            new PrescriptionReading.Line("Dolo 650", "Paracetamol", "650 mg", "1-0-1", "5 days", 10, 1L, "high");

    private static PrescriptionReading reading(String reg, boolean signed, String date, List<String> concerns,
                                               PrescriptionReading.Line... lines) {
        return new PrescriptionReading("Anil Mehta", reg, "Ravi Kumar", date, date, true, signed, concerns, List.of(lines));
    }

    @Test
    void completePrescriptionIsFastTracked() {
        PrescriptionScore s = PrescriptionScore.of(reading("MMC 12345", true, "2026-10-12", List.of(), DOLO), Set.of(1L), TODAY, 80);
        assertEquals(100, s.total());
        assertTrue(s.fastTrack());
        assertEquals("Fast-track", s.getBand());
    }

    @Test
    void missingRegistrationAndSignatureNeedReview() {
        PrescriptionScore s = PrescriptionScore.of(reading("", false, "2026-10-12", List.of(), DOLO), Set.of(1L), TODAY, 80);
        assertEquals(70, s.total());
        assertFalse(s.fastTrack());
        assertEquals("Review", s.getBand());
    }

    @Test
    void anyConcernBlocksFastTrack() {
        PrescriptionScore s = PrescriptionScore.of(reading("MMC 12345", true, "2026-10-12", List.of("date looks altered"), DOLO),
                Set.of(1L), TODAY, 80);
        assertEquals(100, s.total());
        assertFalse(s.fastTrack());
    }

    @Test
    void oldFutureOrUnreadableDatesFail() {
        assertFalse(PrescriptionScore.recent("2026-01-01", TODAY));   // more than 6 months ago
        assertFalse(PrescriptionScore.recent("2026-12-01", TODAY));   // in the future
        assertFalse(PrescriptionScore.recent("12/10/2026", TODAY));
        assertFalse(PrescriptionScore.recent("", TODAY));
        assertTrue(PrescriptionScore.recent("2026-05-01", TODAY));
    }

    @Test
    void madeUpCatalogueIdsAndUnsureLinesCost() {
        PrescriptionReading.Line unsure = new PrescriptionReading.Line("Pan 4?", null, null, null, null, null, 99L, "low");
        PrescriptionScore s = PrescriptionScore.of(reading("MMC 12345", true, "2026-10-12", List.of(), DOLO, unsure),
                Set.of(1L), TODAY, 80);
        assertEquals(80, s.total());     // not "every medicine clearly read", not "every medicine in our catalogue"
        assertTrue(s.fastTrack());
    }

    @Test
    void unreadableFileScoresLow() {
        PrescriptionReading r = new PrescriptionReading(null, null, null, null, null, false, false, null, null);
        PrescriptionScore s = PrescriptionScore.of(r, Set.of(), TODAY, 80);
        assertEquals(0, s.total());
        assertEquals("Check carefully", s.getBand());
    }
}
