package zelisline.ub.tenancy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.tenancy.api.dto.SelfServeCountryResponse;
import zelisline.ub.tenancy.config.SelfServeRegionProperties;

class RegionDefaultsTest {

    private RegionDefaults regionDefaults;

    @BeforeEach
    void setUp() {
        regionDefaults = new RegionDefaults(new SelfServeRegionProperties("KE", ""));
    }

    @Test
    void omitCountry_defaultsToKenya() {
        RegionProfile profile = regionDefaults.requireSelfServe(null);
        assertEquals("KE", profile.countryCode());
        assertEquals("KES", profile.currency());
        assertEquals("Africa/Nairobi", profile.timezone());
    }

    @Test
    void kenyaIsSelfServeEnabled() {
        RegionProfile profile = regionDefaults.requireSelfServe("ke");
        assertEquals("KE", profile.countryCode());
    }

    @Test
    void ugandaRejectedWhenNotInAllowList() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> regionDefaults.requireSelfServe("UG")
        );
        assertTrue(ex.getReason().contains("not available for self-serve"));
    }

    @Test
    void ugandaAllowedWhenConfigured() {
        RegionDefaults withUg = new RegionDefaults(new SelfServeRegionProperties("KE,UG", "UG"));
        RegionProfile profile = withUg.requireSelfServe("UG");
        assertEquals("UGX", profile.currency());
        assertEquals("Africa/Kampala", profile.timezone());
        assertEquals("ug-retail", profile.catalogCode());

        SelfServeCountryResponse dto = withUg.toSelfServeCountry(profile);
        assertTrue(dto.cashCreditOnly());
        assertTrue(dto.paymentHint().toLowerCase().contains("cash"));
    }

    @Test
    void wildcardEnablesAllPublishedCountries() {
        RegionDefaults world = new RegionDefaults(new SelfServeRegionProperties("*", "UG"));
        RegionProfile us = world.requireSelfServe("US");
        assertEquals("USD", us.currency());
        assertEquals("America/New_York", us.timezone());

        SelfServeCountryResponse usDto = world.toSelfServeCountry(us);
        assertFalse(usDto.cashCreditOnly());
        assertTrue(usDto.paymentHint().toLowerCase().contains("cash"));

        SelfServeCountryResponse ug = world.toSelfServeCountry(world.require("UG"));
        assertTrue(ug.cashCreditOnly());

        SelfServeCountryResponse ke = world.toSelfServeCountry(world.require("KE"));
        assertFalse(ke.cashCreditOnly());
        assertTrue(ke.paymentHint().contains("M-Pesa"));

        assertTrue(world.selfServeProfiles().size() > 100);
        assertEquals("KE", world.selfServeProfiles().get(0).countryCode());
    }

    @Test
    void cashCreditWildcardMeansAllExceptKenya() {
        RegionDefaults world = new RegionDefaults(new SelfServeRegionProperties("*", "*"));
        assertTrue(world.toSelfServeCountry(world.require("US")).cashCreditOnly());
        assertFalse(world.toSelfServeCountry(world.require("KE")).cashCreditOnly());
    }

    @Test
    void unknownCountryRejected() {
        assertThrows(ResponseStatusException.class, () -> regionDefaults.require("XX"));
    }

    @Test
    void currencyMatchesCountry() {
        assertTrue(regionDefaults.currencyMatchesCountry("UG", "ugx"));
        assertFalse(regionDefaults.currencyMatchesCountry("UG", "KES"));
        assertTrue(regionDefaults.currencyMatchesCountry("JP", "JPY"));
    }

    @Test
    void selfServeProfilesRespectAllowList() {
        assertEquals(1, regionDefaults.selfServeProfiles().size());
        assertEquals("KE", regionDefaults.selfServeProfiles().get(0).countryCode());
    }

    @Test
    void loadsWorldProfilesFromClasspath() {
        assertTrue(regionDefaults.allProfiles().size() >= 200);
        assertEquals("EUR", regionDefaults.require("DE").currency());
        assertEquals("INR", regionDefaults.require("IN").currency());
    }
}
