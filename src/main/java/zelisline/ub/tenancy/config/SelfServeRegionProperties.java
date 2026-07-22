package zelisline.ub.tenancy.config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Self-serve country allow-list for cloud onboarding.
 *
 * <pre>
 * app.selfserve.countries=KE,UG
 * app.selfserve.cash-credit-only-countries=UG
 * </pre>
 */
@ConfigurationProperties(prefix = "app.selfserve")
public record SelfServeRegionProperties(
        String countries,
        String cashCreditOnlyCountries
) {

    public static final String DEFAULT_COUNTRIES = "KE";

    public SelfServeRegionProperties {
        if (countries == null || countries.isBlank()) {
            countries = DEFAULT_COUNTRIES;
        }
        if (cashCreditOnlyCountries == null) {
            cashCreditOnlyCountries = "";
        }
    }

    public List<String> enabledCountryCodes() {
        return splitCodes(countries);
    }

    public List<String> cashCreditOnlyCountryCodes() {
        return splitCodes(cashCreditOnlyCountries);
    }

    public boolean isEnabled(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return false;
        }
        return enabledCountryCodes().contains(countryCode.trim().toUpperCase(Locale.ROOT));
    }

    public boolean isCashCreditOnly(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return false;
        }
        return cashCreditOnlyCountryCodes()
                .contains(countryCode.trim().toUpperCase(Locale.ROOT));
    }

    private static List<String> splitCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
