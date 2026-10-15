package com.medizon.service;

import com.medizon.domain.Member;
import com.medizon.domain.MemberKind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Who can track which medicine in My medicines: only what was delivered for them, or for the common (OTC) shelf. */
class ReminderEligibilityTest {

    private static final Long METFORMIN = 10L, PARACETAMOL = 20L, CATALOG_ONLY = 30L;

    private final Member me = member(1L, MemberKind.SELF);
    private final Member mother = member(2L, MemberKind.FAMILY);
    private final Member common = member(3L, MemberKind.COMMON);
    private final Member others = member(4L, MemberKind.OTHERS);

    /** Metformin was delivered for me; paracetamol for the common shelf. */
    private final Map<Long, Set<Long>> delivered = Map.of(METFORMIN, Set.of(me.getId()), PARACETAMOL, Set.of(common.getId()));

    @Test
    void aPersonCanTrackWhatWasDeliveredForThem() {
        assertThat(MedicationPlanService.canTrack(me, METFORMIN, delivered, common.getId())).isTrue();
        assertThat(MedicationPlanService.canTrack(mother, METFORMIN, delivered, common.getId())).isFalse();
    }

    @Test
    void anyoneCanTakeCommonMedicinesFromTheShelf() {
        assertThat(MedicationPlanService.canTrack(me, PARACETAMOL, delivered, common.getId())).isTrue();
        assertThat(MedicationPlanService.canTrack(mother, PARACETAMOL, delivered, common.getId())).isTrue();
        assertThat(MedicationPlanService.canTrack(common, PARACETAMOL, delivered, common.getId())).isTrue();
    }

    @Test
    void theShelfOnlyHoldsWhatWasDeliveredForIt() {
        assertThat(MedicationPlanService.canTrack(common, METFORMIN, delivered, common.getId())).isFalse();
    }

    @Test
    void neverDeliveredOrBoughtForOthersCantBeTracked() {
        assertThat(MedicationPlanService.canTrack(me, CATALOG_ONLY, delivered, common.getId())).isFalse();
        assertThat(MedicationPlanService.canTrack(others, PARACETAMOL, delivered, common.getId())).isFalse();
    }

    private static Member member(Long id, MemberKind kind) {
        Member m = new Member();
        m.setId(id);
        m.setKind(kind);
        m.setName(kind.name());
        return m;
    }
}
