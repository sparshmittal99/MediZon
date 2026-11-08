package com.medizon.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Address lookups: Ola Maps, then LocationIQ, then OpenStreetMap; answers cached; visitors limited; usage counted. */
class GeoServiceTest {

    private GeoProvider ola, locationIq, osm;
    private IndiaPostClient indiaPost;
    private GeoService geo;

    private static final GeoPlace MALL = new GeoPlace("Phoenix Marketcity", "LBS Marg, Kurla West, Mumbai", 19.0866, 72.8890,
            "Phoenix Marketcity", "LBS Marg", "Kurla West", "Mumbai", "Maharashtra", "400070", false);

    private static GeoProvider provider(String name) {
        GeoProvider p = mock(GeoProvider.class);
        when(p.name()).thenReturn(name);
        when(p.isConfigured()).thenReturn(true);
        return p;
    }

    @BeforeEach
    void setUp() {
        ola = provider("Ola Maps");
        locationIq = provider("LocationIQ");
        osm = provider("OpenStreetMap");
        indiaPost = mock(IndiaPostClient.class);
        // Daily limits: Ola 2, LocationIQ 3, OpenStreetMap none
        geo = new GeoService(List.of(ola, locationIq, osm), List.of(2, 3, 0), indiaPost);
    }

    @Test
    void olaAnswersFirstAndIsCached() {
        when(ola.suggest(eq("phoenix mall"), anyInt())).thenReturn(List.of(MALL));
        assertThat(geo.suggest("phoenix mall")).containsExactly(MALL);
        assertThat(geo.suggest("  Phoenix   Mall ")).containsExactly(MALL);
        verify(ola, times(1)).suggest(anyString(), anyInt());
        verifyNoInteractions(osm);
        verify(locationIq, never()).suggest(anyString(), anyInt());
        assertThat(geo.provider()).isEqualTo("Ola Maps");
    }

    @Test
    void nextOneTakesOverWhenOneFailsAndRests() {
        when(ola.suggest(anyString(), anyInt())).thenThrow(new GeoUnavailableException("Ola Maps answered 429"));
        when(locationIq.suggest(anyString(), anyInt())).thenReturn(List.of(MALL));
        assertThat(geo.suggest("phoenix")).containsExactly(MALL);
        // Ola rests after failing: the next lookup goes straight to LocationIQ
        when(locationIq.reverse(anyDouble(), anyDouble())).thenReturn(MALL);
        assertThat(geo.reverse(19.0866, 72.8890)).contains(MALL);
        verify(ola, never()).reverse(anyDouble(), anyDouble());
        assertThat(geo.provider()).isEqualTo("LocationIQ");
        assertThat(geo.primaryOffReason()).contains("429");
    }

    @Test
    void providersWithoutAKeyAreSkipped() {
        when(ola.isConfigured()).thenReturn(false);
        when(locationIq.isConfigured()).thenReturn(false);
        when(osm.reverse(anyDouble(), anyDouble())).thenReturn(MALL);
        assertThat(geo.reverse(19.0866, 72.8890)).contains(MALL);
        verify(ola, never()).reverse(anyDouble(), anyDouble());
        assertThat(geo.provider()).isEqualTo("OpenStreetMap");
        assertThat(geo.anyKeyedProvider()).isFalse();
    }

    @Test
    void dailyLimitsHandOverDownTheChain() {
        when(ola.reverse(anyDouble(), anyDouble())).thenReturn(MALL);
        when(locationIq.reverse(anyDouble(), anyDouble())).thenReturn(MALL);
        when(osm.reverse(anyDouble(), anyDouble())).thenReturn(MALL);
        for (int i = 0; i < 7; i++) geo.reverse(19 + i * 0.01, 72.8);    // limits: Ola 2, LocationIQ 3
        verify(ola, times(2)).reverse(anyDouble(), anyDouble());
        verify(locationIq, times(3)).reverse(anyDouble(), anyDouble());
        verify(osm, times(2)).reverse(anyDouble(), anyDouble());
    }

    @Test
    void allDownGivesNothingAndIsNotCached() {
        for (GeoProvider p : List.of(ola, locationIq, osm)) when(p.suggest(anyString(), anyInt())).thenThrow(new GeoUnavailableException("down"));
        assertThat(geo.suggest("andheri")).isEmpty();
        GeoService fresh = new GeoService(List.of(osm), List.of(0), indiaPost);
        reset(osm);
        when(osm.name()).thenReturn("OpenStreetMap");
        when(osm.isConfigured()).thenReturn(true);
        when(osm.suggest(anyString(), anyInt())).thenReturn(List.of(MALL));
        assertThat(fresh.suggest("andheri")).containsExactly(MALL);
    }

    @Test
    void shortQueriesAreNotLookedUp() {
        assertThat(geo.suggest("an")).isEmpty();
        verify(ola, never()).suggest(anyString(), anyInt());
    }

    @Test
    void pincodeComesFromIndiaPostAndIsCached() {
        IndiaPostClient.PincodeInfo info = new IndiaPostClient.PincodeInfo("400069", "Mumbai", "Mumbai", "Maharashtra",
                List.of("Andheri East", "Marol Bazar"));
        when(indiaPost.lookup("400069")).thenReturn(info);
        assertThat(geo.pincode("400 069")).contains(info);
        assertThat(geo.pincode("400069")).contains(info);
        verify(indiaPost, times(1)).lookup("400069");
        assertThat(geo.pincode("012345")).isEmpty();
    }

    @Test
    void indiaPostDownMeansNoDetailsNotAnError() {
        when(indiaPost.lookup(anyString())).thenThrow(new GeoUnavailableException("down"));
        assertThat(geo.pincode("110001")).isEmpty();
        assertThat(geo.isIndiaPostResting()).isTrue();
    }

    @Test
    void eachVisitorHasALimit() {
        for (int i = 0; i < GeoService.PER_CLIENT; i++) assertThat(geo.allow("1.2.3.4")).isTrue();
        assertThat(geo.allow("1.2.3.4")).isFalse();
        assertThat(geo.allow("5.6.7.8")).isTrue();
    }

    @Test
    void countsTodaysLookupsForMonitoring() {
        when(ola.suggest(anyString(), anyInt())).thenReturn(List.of(MALL));
        when(locationIq.remainingToday()).thenReturn(4990);
        geo.suggest("phoenix");
        geo.suggest("phoenix");                                   // from memory
        when(ola.reverse(anyDouble(), anyDouble())).thenThrow(new GeoUnavailableException("Ola Maps answered 429"));
        when(locationIq.reverse(anyDouble(), anyDouble())).thenReturn(MALL);
        geo.reverse(19.08, 72.88);                                // Ola fails, LocationIQ answers
        when(indiaPost.lookup(anyString())).thenReturn(null);
        geo.pincode("400069");

        GeoService.Status s = geo.status();
        GeoService.DayStats t = s.getToday();
        assertThat(t.answeredBy("Ola Maps")).isEqualTo(1);
        assertThat(t.failedBy("Ola Maps")).isEqualTo(1);
        assertThat(t.answeredBy("LocationIQ")).isEqualTo(1);
        assertThat(t.answeredBy(GeoService.INDIA_POST)).isEqualTo(1);
        assertThat(t.cached()).isEqualTo(1);
        assertThat(t.getTotal()).isEqualTo(4);
        assertThat(t.getFailedTotal()).isEqualTo(1);
        assertThat(s.provider()).isEqualTo("LocationIQ");
        assertThat(s.getSources()).containsExactly("Ola Maps", "LocationIQ", "OpenStreetMap", "India Post");
        GeoService.ProviderStatus olaStatus = s.providers().get(0);
        assertThat(olaStatus.usedToday()).isEqualTo(1);
        assertThat(olaStatus.getUsedPercent()).isEqualTo(50);
        assertThat(olaStatus.offReason()).contains("429");
        assertThat(s.providers().get(1).remainingToday()).isEqualTo(4990);
        assertThat(s.days()).hasSize(1);
    }

    @Test
    void healthCardSaysWhatsHappening() {
        var health = new com.medizon.monitoring.LocationHealthIndicator(geo);
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(health.health().getDetails().get("reason")).isEqualTo("Ola Maps answers address lookups");
        verify(locationIq, never()).remainingToday();             // health checks never call out

        when(ola.suggest(anyString(), anyInt())).thenThrow(new GeoUnavailableException("Ola Maps answered 401"));
        when(locationIq.suggest(anyString(), anyInt())).thenReturn(List.of(MALL));
        geo.suggest("kurla");
        assertThat(health.health().getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.health().getDetails().get("effect")).isEqualTo("LocationIQ answers address lookups");
    }

    @Test
    void readsLocationIqAndNominatimAnswers() throws Exception {
        ObjectMapper json = new ObjectMapper();
        GeoPlace a = GeoPlace.from(json.readTree("""
            {"lat":"19.0866","lon":"72.8890","display_place":"Phoenix Marketcity","display_address":"LBS Marg, Kurla West, Mumbai, Maharashtra",
             "address":{"name":"Phoenix Marketcity","road":"LBS Marg","suburb":"Kurla West","city":"Mumbai","state":"Maharashtra","postcode":"400 070"}}"""));
        assertThat(a.title()).isEqualTo("Phoenix Marketcity");
        assertThat(a.area()).isEqualTo("Kurla West");
        assertThat(a.pincode()).isEqualTo("400070");
        assertThat(a.partial()).isFalse();
        GeoPlace b = GeoPlace.from(json.readTree("""
            {"lat":"28.61","lon":"77.20","display_name":"Rajpath, Central Delhi, Delhi, 110001, India",
             "address":{"road":"Rajpath","neighbourhood":"Central Delhi","town":"New Delhi","state":"Delhi","postcode":"11000"}}"""));
        assertThat(b.title()).isEqualTo("Rajpath");
        assertThat(b.pincode()).isNull();
        assertThat(GeoPlace.from(json.readTree("{\"error\":\"Unable to geocode\"}"))).isNull();
    }

    /** Trimmed from a real Ola Maps autocomplete answer. */
    @Test
    void readsOlaSuggestions() throws Exception {
        var p = new ObjectMapper().readTree("""
            {"terms":[{"offset":0,"value":"Arctica A Wing"},{"offset":16,"value":"Casa Rio"},{"offset":26,"value":"Lodha Palava"},
                      {"offset":80,"value":"Trimurti Nagar"},{"offset":96,"value":"Dombivli East"},{"offset":121,"value":"Maharashtra"},
                      {"offset":134,"value":"421201"},{"offset":142,"value":"India"}],
             "structured_formatting":{"secondary_text":"Arctica A Wing, Casa Rio, Lodha Palava, Trimurti Nagar, Dombivli East, Dombivli, Maharashtra, 421201, India",
                                      "main_text":"Arctica A Wing, Casa Rio, Lodha Palava"},
             "description":"Arctica A Wing, Casa Rio, Lodha Palava, Trimurti Nagar, Dombivli East, Dombivli, Maharashtra, 421201, India",
             "geometry":{"location":{"lng":73.0989,"lat":19.2161}},"place_id":"ola-platform:5000038289941","layer":["venue"]}""");
        GeoPlace s = OlaMapsClient.fromPrediction(p);
        assertThat(s.title()).isEqualTo("Arctica A Wing, Casa Rio, Lodha Palava");
        assertThat(s.subtitle()).endsWith("Maharashtra, 421201");
        assertThat(s.pincode()).isEqualTo("421201");
        assertThat(s.state()).isEqualTo("Maharashtra");
        assertThat(s.lat()).isEqualTo(19.2161);
        assertThat(s.lon()).isEqualTo(73.0989);
        assertThat(s.partial()).isTrue();
        // Outside India: dropped
        assertThat(OlaMapsClient.fromPrediction(new ObjectMapper().readTree(
                "{\"structured_formatting\":{\"main_text\":\"x\"},\"geometry\":{\"location\":{\"lng\":-0.12,\"lat\":51.5}}}"))).isNull();
    }

    /** Trimmed from a real Ola Maps reverse-geocode answer. */
    @Test
    void readsOlaReverseGeocode() throws Exception {
        String result = """
            {"formatted_address":"TB Hospital, Shahid Major Kaustubh Rane FlyOver, Hallow Pul, L Ward, Zone 5, Mumbai, Mumbai Suburban, Maharashtra, 400070, India",
             "types":["hospital"],"distance_meters":%s,"name":"TB Hospital",
             "geometry":{"location":{"lng":72.87766,"lat":19.07598}},
             "address_components":[{"types":["country"],"long_name":"India"},{"types":["administrative_area_level_1"],"long_name":"Maharashtra"},
               {"types":["administrative_area_level_2"],"long_name":"Mumbai Suburban"},{"types":["locality"],"long_name":"Mumbai"},
               {"types":["sublocality"],"long_name":"Kurla West"},{"types":["neighborhood"],"long_name":"Hallow Pul"},
               {"types":["postal_code"],"long_name":"400070"},{"types":["street_address"],"long_name":"Shahid Major Kaustubh Rane flyover"}]}""";
        ObjectMapper json = new ObjectMapper();
        GeoPlace on = OlaMapsClient.fromResult(json.readTree(result.formatted(5)));
        assertThat(on.building()).isEqualTo("TB Hospital");
        assertThat(on.title()).isEqualTo("TB Hospital");
        assertThat(on.road()).isEqualTo("Shahid Major Kaustubh Rane flyover");
        assertThat(on.area()).isEqualTo("Hallow Pul, Kurla West");
        assertThat(on.city()).isEqualTo("Mumbai");
        assertThat(on.state()).isEqualTo("Maharashtra");
        assertThat(on.pincode()).isEqualTo("400070");
        assertThat(on.partial()).isFalse();
        // 40 m away: a nearby place, not the customer's building
        GeoPlace near = OlaMapsClient.fromResult(json.readTree(result.formatted(40)));
        assertThat(near.building()).isNull();
        assertThat(near.title()).isEqualTo("Shahid Major Kaustubh Rane flyover");
    }

    @Test
    void onlyPlacesInIndiaAreKept() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(GeoPlace.inIndia(json.readTree("{\"lat\":\"19.07\",\"lon\":\"72.87\",\"address\":{\"country_code\":\"in\"}}"))).isTrue();
        assertThat(GeoPlace.inIndia(json.readTree("{\"lat\":\"39.80\",\"lon\":\"-89.64\",\"address\":{\"country_code\":\"us\"}}"))).isFalse();
        assertThat(GeoPlace.inIndia(json.readTree("{\"lat\":\"27.71\",\"lon\":\"85.32\",\"address\":{\"country_code\":\"np\"}}"))).isFalse();
        assertThat(GeoPlace.inIndia(json.readTree("{\"lat\":\"51.50\",\"lon\":\"-0.12\"}"))).isFalse();
    }

    @Test
    void reverseNothingHereIsEmpty() {
        when(ola.reverse(anyDouble(), anyDouble())).thenReturn(null);
        assertThat(geo.reverse(10, 80)).isEqualTo(Optional.empty());
    }
}
