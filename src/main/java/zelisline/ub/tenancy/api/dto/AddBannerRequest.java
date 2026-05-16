package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Size;

/**
 * JSON body for {@code POST /api/v1/businesses/me/branding/banners}.
 * The frontend uploads to Cloudinary first, then registers the resulting
 * URL and public ID with this endpoint.
 */
public record AddBannerRequest(
    @Size(max = 1024) String url,
    @Size(max = 255) String publicId
) {}
