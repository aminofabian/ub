package zelisline.ub.tenancy.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Self-serve country allow-list for cloud onboarding.
 *
 * <pre>
 * app.selfserve.countries=*
 * app.selfserve.cash-credit-only-countries=UG
 * </pre>
 *
 * Use {@code *} or {@code ALL} to mean every published region profile.
 * For cash-credit-only, {@code *} means every country except Kenya (M-Pesa).
 */
@ConfigurationProperties(prefix = "app.selfserve")
public record SelfServeRegionProperties(
        String countries,
        String cashCreditOnlyCountries
) {

    public static final String DEFAULT_COUNTRIES = "KE";
    public static final String WILDCARD = "*";
    private static final String ALL_TOKEN = "ALL";
    private static final String MPESA_COUNTRY = "KE";

    public SelfServeRegionProperties {
        if (countries == null || countries.isBlank()) {
            countries = DEFAULT_COUNTRIES;
        }
        if (cashCreditOnlyCountries == null) {
            cashCreditOnlyCountries = "";
        }
    }

    public List<String> enabledCountryCodes(Collection<String> knownCountryCodes) {
        List<String> tokens = splitCodes(countries);
        if (isWildcard(tokens)) {
            return sortedKnown(knownCountryCodes);
        }
        LinkedHashSet<String> known = new LinkedHashSet<>();
        for (String code : knownCountryCodes) {
            if (code != null && !code.isBlank()) {
                known.add(code.trim().toUpperCase(Locale.ROOT));
            }
        }
        List<String> out = new ArrayList<>();
        for (String code : tokens) {
            if (known.contains(code)) {
                out.add(code);
            }
        }
        return List.copyOf(out);
    }

    public boolean isEnabled(String countryCode, Collection<String> knownCountryCodes) {
        if (countryCode == null || countryCode.isBlank()) {
            return false;
        }
        String normalized = countryCode.trim().toUpperCase(Locale.ROOT);
        if (isWildcard(splitCodes(countries))) {
            return knownCountryCodes.stream()
                    .anyMatch(c -> c != null && normalized.equals(c.trim().toUpperCase(Locale.ROOT)));
        }
        return enabledCountryCodes(knownCountryCodes).contains(normalized);
    }

    public boolean isCashCreditOnly(String countryCode, Collection<String> knownCountryCodes) {
        if (countryCode == null || countryCode.isBlank()) {
            return false;
        }
        String normalized = countryCode.trim().toUpperCase(Locale.ROOT);
        List<String> tokens = splitCodes(cashCreditOnlyCountries);
        if (isWildcard(tokens)) {
            return !MPESA_COUNTRY.equals(normalized)
                    && knownCountryCodes.stream()
                    .anyMatch(c -> c != null && normalized.equals(c.trim().toUpperCase(Locale.ROOT)));
        }
        return tokens.contains(normalized);
    }

    private static boolean isWildcard(List<String> tokens) {
        return tokens.size() == 1
                && (WILDCARD.equals(tokens.get(0)) || ALL_TOKEN.equals(tokens.get(0)));
    }

    private static List<String> sortedKnown(Collection<String> knownCountryCodes) {
        Set<String> set = new LinkedHashSet<>();
        for (String code : knownCountryCodes) {
            if (code != null && !code.isBlank()) {
                set.add(code.trim().toUpperCase(Locale.ROOT));
            }
        }
        List<String> sorted = new ArrayList<>(set);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static List<String> splitCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (WILDCARD.equals(trimmed) || ALL_TOKEN.equalsIgnoreCase(trimmed)) {
            return List.of(trimmed.equals(WILDCARD) ? WILDCARD : ALL_TOKEN);
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
