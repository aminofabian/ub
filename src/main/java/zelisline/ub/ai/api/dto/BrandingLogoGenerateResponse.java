package zelisline.ub.ai.api.dto;

import java.util.List;

/** Light and dark PNG (or JPEG) variants as Base64 so the client can preview both. */
public record BrandingLogoGenerateResponse(
        String requestId,
        List<BrandingLogoVariantDto> logos
) {}
