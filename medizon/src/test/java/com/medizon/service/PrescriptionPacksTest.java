package com.medizon.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrescriptionPacksTest {

    @Test
    void packsCoverTheCourse() {
        assertEquals(1, PrescriptionScanService.packs(10, 15));    // 1-0-1 for 5 days, strip of 15
        assertEquals(2, PrescriptionScanService.packs(20, 15));
        assertEquals(1, PrescriptionScanService.packs(15, 15));
    }

    @Test
    void onePackWhenTheCourseIsUnclear() {
        assertEquals(1, PrescriptionScanService.packs(null, 10));
        assertEquals(1, PrescriptionScanService.packs(0, 10));
        assertEquals(1, PrescriptionScanService.packs(30, 0));
    }

    @Test
    void neverMoreThanFivePacks() {
        assertEquals(5, PrescriptionScanService.packs(900, 10));
    }
}
