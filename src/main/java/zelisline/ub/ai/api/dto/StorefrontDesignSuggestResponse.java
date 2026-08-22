package zelisline.ub.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AI-suggested storefront changes. Every field is optional ("no change") and
 * validated server-side before it reaches the merchant — the model can suggest
 * anything, the platform only forwards what it can safely apply.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StorefrontDesignSuggestResponse(
        String requestId,
        /** 1–2 sentences explaining what changed and why. */
        String summary,
        BrandKitSuggestion brandKit,
        CopySuggestion copy
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrandKitSuggestion(
            String radius,
            String buttons,
            String density,
            String surface
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CopySuggestion(
            String tagline,
            String description,
            String announcement,
            String promoTitle,
            String promoSubtitle,
            String coupon,
            String ctaLabel,
            String heroHeadline,
            String heroSubheadline,
            String aboutHeading,
            String socialHeading,
            String contactHeading
    ) {}
}
