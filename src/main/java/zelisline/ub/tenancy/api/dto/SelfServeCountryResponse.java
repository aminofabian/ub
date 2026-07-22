package zelisline.ub.tenancy.api.dto;

import java.util.List;

/**
 * Self-serve country option for cloud onboarding pickers.
 */
public record SelfServeCountryResponse(
        String countryCode,
        String label,
        String currency,
        String timezone,
        String dialCode,
        List<String> localityPlaceholders,
        boolean cashCreditOnly,
        String paymentHint
) {
}
