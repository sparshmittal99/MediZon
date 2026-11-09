package com.medizon.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Devices are named so a family can tell them apart: the Android app by its phone's own name. */
class DeviceLabelTest {

    private static final String WEBVIEW = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36";

    @Test
    void theAppIsNamedAfterItsPhone() {
        assertThat(DeviceService.describe(WEBVIEW + " MediZonApp/1.0 MediZonDevice/Sparsh%27s%20Galaxy%20S23"))
                .isEqualTo("Sparsh's Galaxy S23");
        assertThat(DeviceService.describe(WEBVIEW + " MediZonApp/1.0 MediZonDevice/Mummy%E2%80%99s%20Redmi"))
                .isEqualTo("Mummy’s Redmi");
    }

    @Test
    void anOlderAppOrABadNameKeepsTheGenericName() {
        assertThat(DeviceService.describe(WEBVIEW + " MediZonApp/1.0")).isEqualTo("MediZon app on Android");
        assertThat(DeviceService.describe(WEBVIEW + " MediZonApp/1.0 MediZonDevice/%ZZ")).isEqualTo("MediZon app on Android");
    }

    @Test
    void browsersAreNamedAsBefore() {
        assertThat(DeviceService.describe(WEBVIEW)).isEqualTo("Chrome on Android");
        assertThat(DeviceService.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0 Safari/537.36 Edg/130.0"))
                .isEqualTo("Edge on Windows");
    }

    @Test
    void onlyTheOldDefaultNameIsReplaced() {
        String app = WEBVIEW + " MediZonApp/1.0 MediZonDevice/Pixel%208";
        assertThat(DeviceService.renamed("MediZon app on Android", app)).isEqualTo("Pixel 8");
        // A name the user picked stays; an older app sends no name, so nothing changes
        assertThat(DeviceService.renamed("Kitchen tablet", app)).isNull();
        assertThat(DeviceService.renamed("MediZon app on Android", WEBVIEW + " MediZonApp/1.0")).isNull();
    }
}
