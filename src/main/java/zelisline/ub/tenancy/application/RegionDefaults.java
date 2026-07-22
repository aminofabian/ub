package zelisline.ub.tenancy.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.globalcatalog.application.GlobalCatalogResolver;
import zelisline.ub.tenancy.api.dto.SelfServeCountryResponse;
import zelisline.ub.tenancy.config.SelfServeRegionProperties;

/**
 * Canonical country → currency / timezone / VAT / catalog defaults.
 * Backend source of truth; FE mirrors the picker list from the public endpoint.
 */
@Component
public class RegionDefaults {

    public static final String DEFAULT_COUNTRY = "KE";

    private static final Map<String, RegionProfile> BY_COUNTRY = buildProfiles();

    private final SelfServeRegionProperties selfServeRegionProperties;

    public RegionDefaults(SelfServeRegionProperties selfServeRegionProperties) {
        this.selfServeRegionProperties = selfServeRegionProperties;
    }

    public Optional<RegionProfile> find(String countryCode) {
        String normalized = normalizeCountry(countryCode);
        if (normalized == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_COUNTRY.get(normalized));
    }

    public RegionProfile require(String countryCode) {
        return find(countryCode).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported country code: " + countryCode
                ));
    }

    /**
     * Resolves a country for cloud self-serve create. Null/blank → KE.
     * Rejects unknown or non-enabled countries.
     */
    public RegionProfile requireSelfServe(String countryCode) {
        String normalized = normalizeCountry(countryCode);
        if (normalized == null) {
            normalized = DEFAULT_COUNTRY;
        }
        RegionProfile profile = require(normalized);
        if (!selfServeRegionProperties.isEnabled(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Country '" + normalized + "' is not available for self-serve signup yet"
            );
        }
        return profile;
    }

    public List<RegionProfile> selfServeProfiles() {
        return selfServeRegionProperties.enabledCountryCodes().stream()
                .map(this::find)
                .flatMap(Optional::stream)
                .toList();
    }

    public SelfServeCountryResponse toSelfServeCountry(RegionProfile profile) {
        boolean cashCreditOnly = selfServeRegionProperties.isCashCreditOnly(profile.countryCode());
        return new SelfServeCountryResponse(
                profile.countryCode(),
                profile.label(),
                profile.currency(),
                profile.timezone(),
                profile.dialCode(),
                profile.localityPlaceholders(),
                cashCreditOnly,
                paymentHint(profile.countryCode(), cashCreditOnly)
        );
    }

    public List<RegionProfile> allProfiles() {
        return List.copyOf(BY_COUNTRY.values());
    }

    public boolean currencyMatchesCountry(String countryCode, String currency) {
        Optional<RegionProfile> profile = find(countryCode);
        if (profile.isEmpty() || currency == null || currency.isBlank()) {
            return false;
        }
        return profile.get().currency().equalsIgnoreCase(currency.trim());
    }

    public static String normalizeCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return null;
        }
        return countryCode.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, RegionProfile> buildProfiles() {
        Map<String, RegionProfile> map = new LinkedHashMap<>();
        put(map, new RegionProfile(
                "KE", "Kenya", "KES", "Africa/Nairobi",
                new BigDecimal("16"), GlobalCatalogResolver.DEFAULT_CATALOG_CODE, "+254",
                List.of("Mirema", "Kasarani", "Ongata Rongai", "Westlands", "Karen")
        ));
        put(map, new RegionProfile(
                "UG", "Uganda", "UGX", "Africa/Kampala",
                new BigDecimal("18"), "ug-retail", "+256",
                List.of("Kampala", "Entebbe", "Jinja", "Gulu", "Mbarara")
        ));
        put(map, new RegionProfile(
                "TZ", "Tanzania", "TZS", "Africa/Dar_es_Salaam",
                new BigDecimal("18"), null, "+255",
                List.of("Dar es Salaam", "Arusha", "Mwanza", "Dodoma", "Zanzibar")
        ));
        put(map, new RegionProfile(
                "RW", "Rwanda", "RWF", "Africa/Kigali",
                new BigDecimal("18"), null, "+250",
                List.of("Kigali", "Butare", "Gisenyi", "Ruhengeri", "Musanze")
        ));
        put(map, new RegionProfile(
                "NG", "Nigeria", "NGN", "Africa/Lagos",
                new BigDecimal("7.5"), null, "+234",
                List.of("Lagos", "Abuja", "Port Harcourt", "Ibadan", "Kano")
        ));
        put(map, new RegionProfile(
                "ZA", "South Africa", "ZAR", "Africa/Johannesburg",
                new BigDecimal("15"), null, "+27",
                List.of("Johannesburg", "Cape Town", "Durban", "Pretoria", "Gqeberha")
        ));
        return Map.copyOf(map);
    }

    private static void put(Map<String, RegionProfile> map, RegionProfile profile) {
        map.put(profile.countryCode(), profile);
    }

    private static String paymentHint(String countryCode, boolean cashCreditOnly) {
        if (cashCreditOnly) {
            return "Cash and customer credit for now — mobile money rails are not enabled yet.";
        }
        if ("KE".equalsIgnoreCase(countryCode)) {
            return "M-Pesa STK available for Kenya shops.";
        }
        return "Use cash, card, or credit until local mobile money is configured.";
    }
}
