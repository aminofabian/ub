package zelisline.ub.tenancy.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Canonical defaults for a country used during cloud onboarding and desktop setup.
 */
public record RegionProfile(
        String countryCode,
        String label,
        String currency,
        String timezone,
        BigDecimal defaultVatPercent,
        String catalogCode,
        String dialCode,
        List<String> localityPlaceholders
) {
}
