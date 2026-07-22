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
    void unknownCountryRejected() {
        assertThrows(ResponseStatusException.class, () -> regionDefaults.require("XX"));
    }

    @Test
    void currencyMatchesCountry() {
        assertTrue(regionDefaults.currencyMatchesCountry("UG", "ugx"));
        assertFalse(regionDefaults.currencyMatchesCountry("UG", "KES"));
    }

    @Test
    void selfServeProfilesRespectAllowList() {
        assertEquals(1, regionDefaults.selfServeProfiles().size());
        assertEquals("KE", regionDefaults.selfServeProfiles().get(0).countryCode());
    }
}
