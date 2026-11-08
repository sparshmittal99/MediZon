package com.medizon.domain;

import java.util.Locale;

/** Links that open a map pin in Google Maps (the app on phones, the website elsewhere). */
public final class MapLinks {

    private MapLinks() {}

    public static String of(double lat, double lon) {
        return String.format(Locale.ROOT, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", lat, lon);
    }
}
