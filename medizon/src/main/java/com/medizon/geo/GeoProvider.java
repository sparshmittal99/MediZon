package com.medizon.geo;

import java.util.List;

/** A geocoder that can suggest places while typing and say what's at a spot. {@link GeoService} tries them in order. */
public interface GeoProvider {

    /** Shown in Admin > Monitoring only; customers never see which service answered. */
    String name();

    boolean isConfigured();

    /** Places in India matching what's typed. */
    List<GeoPlace> suggest(String q, int limit);

    /** What's at this spot, or null when there's nothing on the map here. */
    GeoPlace reverse(double lat, double lon);

    /** Lookups left today as the service itself counts them; null when it can't say. */
    default Integer remainingToday() { return null; }
}
