package com.medizon.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SharesTest {

    @Test
    void splitsByPacksOrdered() {
        // 3 packs of 10 tablets: 2 packs for one person, 1 for another
        assertThat(Shares.split(30, new int[]{2, 1})).containsExactly(20, 10);
    }

    @Test
    void sharesAddBackUpToTheTotal() {
        int[] out = Shares.split(10, new int[]{1, 1, 1});
        assertThat(Arrays.stream(out).sum()).isEqualTo(10);
        assertThat(out).containsExactly(4, 3, 3);
    }

    @Test
    void evenWhenNothingWasRecorded() {
        assertThat(Shares.split(7, new int[]{0, 0})).containsExactly(4, 3);
    }

    @Test
    void someoneWithNoPacksGetsNothing() {
        assertThat(Shares.split(5, new int[]{0, 5})).containsExactly(0, 5);
    }
}
