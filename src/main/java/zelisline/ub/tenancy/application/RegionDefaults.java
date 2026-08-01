package zelisline.ub.tenancy.application;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.globalcatalog.application.GlobalCatalogResolver;
import zelisline.ub.tenancy.api.dto.SelfServeCountryResponse;
import zelisline.ub.tenancy.config.SelfServeRegionProperties;

/**
 * Canonical country → currency / timezone / VAT / catalog defaults.
 * Profiles load from {@code classpath:region-profiles.csv}; a few countries
 * keep richer locality / catalog overrides below. Backend is source of truth;
 * FE mirrors the picker list from the public endpoint.
 */
@Component
public class RegionDefaults {

    public static final String DEFAULT_COUNTRY = "KE";

    private static final String PROFILES_RESOURCE = "region-profiles.csv";

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
        if (!selfServeRegionProperties.isEnabled(normalized, BY_COUNTRY.keySet())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Country '" + normalized + "' is not available for self-serve signup yet"
            );
        }
        return profile;
    }

    public List<RegionProfile> selfServeProfiles() {
        List<String> enabled = selfServeRegionProperties.enabledCountryCodes(BY_COUNTRY.keySet());
        List<RegionProfile> profiles = new ArrayList<>(enabled.size());
        for (String code : enabled) {
            RegionProfile profile = BY_COUNTRY.get(code);
            if (profile != null) {
                profiles.add(profile);
            }
        }
        profiles.sort(Comparator
                .comparing((RegionProfile p) -> !DEFAULT_COUNTRY.equals(p.countryCode()))
                .thenComparing(RegionProfile::label, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(profiles);
    }

    public SelfServeCountryResponse toSelfServeCountry(RegionProfile profile) {
        boolean cashCreditOnly = selfServeRegionProperties.isCashCreditOnly(
                profile.countryCode(),
                BY_COUNTRY.keySet()
        );
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
        Map<String, RegionProfile> map = loadFromCsv();
        applyRichOverrides(map);
        if (!map.containsKey(DEFAULT_COUNTRY)) {
            throw new IllegalStateException(
                    "region-profiles.csv must include default country " + DEFAULT_COUNTRY
            );
        }
        return Map.copyOf(map);
    }

    private static Map<String, RegionProfile> loadFromCsv() {
        Map<String, RegionProfile> map = new LinkedHashMap<>();
        ClassPathResource resource = new ClassPathResource(PROFILES_RESOURCE);
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalStateException(PROFILES_RESOURCE + " is empty");
            }
            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 6) {
                    throw new IllegalStateException(
                            PROFILES_RESOURCE + ":" + lineNo + " expected 6 columns, got " + cols.size()
                    );
                }
                String code = cols.get(0).trim().toUpperCase(Locale.ROOT);
                String label = cols.get(1).trim();
                String currency = cols.get(2).trim().toUpperCase(Locale.ROOT);
                String timezone = cols.get(3).trim();
                String dial = cols.get(4).trim();
                BigDecimal vat = new BigDecimal(cols.get(5).trim());
                if (code.isEmpty() || label.isEmpty() || currency.isEmpty() || timezone.isEmpty()) {
                    throw new IllegalStateException(PROFILES_RESOURCE + ":" + lineNo + " missing required fields");
                }
                map.put(code, new RegionProfile(
                        code,
                        label,
                        currency,
                        timezone,
                        vat,
                        null,
                        dial.isEmpty() ? null : dial,
                        List.of()
                ));
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load " + PROFILES_RESOURCE, ex);
        }
        if (map.isEmpty()) {
            throw new IllegalStateException(PROFILES_RESOURCE + " contained no countries");
        }
        return map;
    }

    /** Minimal RFC4180-ish CSV parser (quoted fields, commas). */
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>(6);
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * Locality examples + regional catalog codes for markets we actively tune.
     * Catalog resolution still falls back to {@code default} when null.
     */
    private static void applyRichOverrides(Map<String, RegionProfile> map) {
        override(map, "KE", GlobalCatalogResolver.DEFAULT_CATALOG_CODE,
                List.of("Mirema", "Kasarani", "Ongata Rongai", "Westlands", "Karen"));
        override(map, "UG", "ug-retail",
                List.of("Kampala", "Entebbe", "Jinja", "Gulu", "Mbarara"));
        override(map, "TZ", null,
                List.of("Dar es Salaam", "Arusha", "Mwanza", "Dodoma", "Zanzibar"));
        override(map, "RW", null,
                List.of("Kigali", "Butare", "Gisenyi", "Ruhengeri", "Musanze"));
        override(map, "NG", null,
                List.of("Lagos", "Abuja", "Port Harcourt", "Ibadan", "Kano"));
        override(map, "ZA", null,
                List.of("Johannesburg", "Cape Town", "Durban", "Pretoria", "Gqeberha"));
        override(map, "US", null,
                List.of("New York", "Los Angeles", "Chicago", "Houston", "Miami"));
        override(map, "GB", null,
                List.of("London", "Manchester", "Birmingham", "Leeds", "Glasgow"));
        override(map, "IN", null,
                List.of("Mumbai", "Delhi", "Bengaluru", "Hyderabad", "Chennai"));
        override(map, "AE", null,
                List.of("Dubai", "Abu Dhabi", "Sharjah", "Ajman", "Al Ain"));
    }

    private static void override(
            Map<String, RegionProfile> map,
            String code,
            String catalogCode,
            List<String> localities
    ) {
        RegionProfile base = map.get(code);
        if (base == null) {
            return;
        }
        map.put(code, new RegionProfile(
                base.countryCode(),
                base.label(),
                base.currency(),
                base.timezone(),
                base.defaultVatPercent(),
                catalogCode,
                base.dialCode(),
                List.copyOf(localities)
        ));
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
