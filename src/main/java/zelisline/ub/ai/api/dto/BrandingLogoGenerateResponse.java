package zelisline.ub.ai.api.dto;

import java.util.List;

/** Brand kit as Base64 so the client can preview, save, and download each file. */
public record BrandingLogoGenerateResponse(
        String requestId,
        List<BrandingLogoVariantDto> logos
) {}
