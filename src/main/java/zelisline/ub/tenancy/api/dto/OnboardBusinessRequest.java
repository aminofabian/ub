package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public self-service business onboarding request. Allows a visitor on an
 * unmapped domain (e.g. kiosk.ke) to create a business that auto-maps to a
 * subdomain like {@code {slug}.kiosk.ke}, then proceed to login or signup.
 *
 * <p>{@code countryCode} is optional; omit or blank → Kenya defaults.
 * Must be in the self-serve allow-list when provided.
 */
public record OnboardBusinessRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String host,
        @Pattern(regexp = "|[A-Za-z]{2}")
        @Size(max = 2)
        String countryCode
) {
}
