package org.example.airplanner;

import org.example.airplanner.model.Airport;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.search.AirportGroups;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Named destination groups such as "greek islands". */
class AirportGroupsTest {

    /** A slice of the real directory, regions included, exactly as Ryanair publishes them. */
    private final AirportDirectory directory = new AirportDirectory(List.of(
            airport("ATH", "Athens", "Athens", "Default GR", "Greece", "gr"),
            airport("CFU", "Corfu", "Corfu", "Greek Islands", "Greece", "gr"),
            airport("CHQ", "Chania", "Chania", "Greek Islands", "Greece", "gr"),
            airport("JTR", "Santorini", "Santorini", "Greek Islands", "Greece", "gr"),
            airport("RHO", "Rhodes", "Rhodes", "Greek Islands", "Greece", "gr"),
            airport("SKG", "Thessaloniki", "Thessaloniki", "Central Macedonia", "Greece", "gr"),
            airport("PMI", "Palma de Mallorca", "Palma", "Default ES", "Spain", "es"),
            airport("IBZ", "Ibiza", "Ibiza", "Balearic Islands", "Spain", "es"),
            airport("MAH", "Menorca", "Menorca", "Balearic Islands", "Spain", "es"),
            airport("TFS", "Tenerife South", "Tenerife", "Canary Isles", "Spain", "es"),
            airport("CTA", "Catania", "Catania", "Sicily", "Italy", "it"),
            airport("VIE", "Vienna", "Vienna", "Default AT", "Austria", "at")));

    private static Airport airport(String code, String name, String city, String region,
                                   String country, String countryCode) {
        return new Airport(code, name, city, region, country, countryCode, 0, 0, "Europe/Athens", false);
    }

    @Test
    void greekIslandsExpandsToTheIslandsOnly() {
        assertEquals(List.of("CFU", "CHQ", "JTR", "RHO"), directory.resolveCodes(List.of("greek islands")));
    }

    @Test
    void groupNamesAreForgiving() {
        List<String> expected = List.of("CFU", "CHQ", "JTR", "RHO");
        assertEquals(expected, directory.resolveCodes(List.of("Greek Islands")));
        assertEquals(expected, directory.resolveCodes(List.of("greek isles")));
        assertEquals(expected, directory.resolveCodes(List.of("GREEK-ISLANDS")));
        assertEquals(expected, directory.resolveCodes(List.of("group:greek islands")));
    }

    @Test
    void groupsCanBeMixedWithOtherTokens() {
        assertEquals(List.of("CFU", "CHQ", "JTR", "RHO", "CTA"),
                directory.resolveCodes(List.of("greek islands,CTA")));
    }

    @Test
    void aGroupCanSpanRegionsAndHandPickedAirports() {
        // Palma has no usable region in Ryanair's data, so the group names it explicitly.
        assertEquals(List.of("IBZ", "MAH", "PMI"), directory.resolveCodes(List.of("balearics")));
        assertEquals(List.of("IBZ", "MAH", "PMI", "TFS"), directory.resolveCodes(List.of("spanish islands")));
        assertEquals(List.of("CFU", "CHQ", "CTA", "IBZ", "JTR", "MAH", "PMI", "RHO"),
                directory.resolveCodes(List.of("mediterranean islands")));
    }

    @Test
    void winterSunReachesAsFarAsTheseAirlinesGo() {
        AirportDirectory wider = new AirportDirectory(List.of(
                airport("TFS", "Tenerife South", "Tenerife", "Canary Isles", "Spain", "es"),
                airport("FNC", "Madeira Funchal", "Funchal", "Madeira", "Portugal", "pt"),
                airport("AGA", "Agadir", "Agadir", "Souss-Massa", "Morocco", "ma"),
                airport("PFO", "Paphos", "Paphos", "Paphos", "Cyprus", "cy"),
                airport("VIE", "Vienna", "Vienna", "Default AT", "Austria", "at")));
        assertEquals(List.of("AGA", "FNC", "PFO", "TFS"), wider.resolveCodes(List.of("winter sun")));
        assertEquals(List.of("AGA", "FNC", "PFO", "TFS"), wider.resolveCodes(List.of("warm")));
    }

    @Test
    void regionsResolveEvenWithoutAGroup() {
        assertEquals(List.of("CTA"), directory.resolveCodes(List.of("sicily")));
        assertEquals(List.of("SKG"), directory.resolveCodes(List.of("region:central macedonia")));
    }

    @Test
    void countryStillBeatsNothing() {
        assertEquals(List.of("ATH", "CFU", "CHQ", "JTR", "RHO", "SKG"), directory.resolveCodes(List.of("greece")));
    }

    @Test
    void onlyGroupsWithAirportsAreOffered() {
        List<String> names = directory.groups().stream().map(AirportGroups.Group::name).toList();
        assertTrue(names.contains("greek islands"), names.toString());
        assertTrue(names.contains("balearic islands"), names.toString());
        // No Madeira or Canaries beyond Tenerife in this slice, but the group still has a member.
        assertTrue(names.contains("atlantic islands"), names.toString());
    }

    @Test
    void anUnknownGroupSaysWhatIsAvailable() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> directory.resolve("group:hebrides"));
        assertTrue(e.getMessage().contains("greek islands"), e.getMessage());
    }
}
