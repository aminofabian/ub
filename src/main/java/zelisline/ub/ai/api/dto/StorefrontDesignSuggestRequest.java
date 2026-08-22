package zelisline.ub.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Merchant request to redesign their storefront with AI ("Make my store look better").
 * The {@code designJson} is the merchant's current (possibly unsaved) design draft —
 * the AI responds to that state rather than the last saved one.
 */
public record StorefrontDesignSuggestRequest(
        @NotBlank @Size(max = 600) String prompt,
        @Size(max = 40_000) String designJson
) {
}
