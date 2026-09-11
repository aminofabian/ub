package zelisline.ub.ai.api.dto;

/**
 * One opaque PNG home-screen icon. The client previews and uploads it.
 */
public record BrandingAppIconGenerateResponse(
        String requestId,
        String mimeType,
        String imageBase64
) {}
