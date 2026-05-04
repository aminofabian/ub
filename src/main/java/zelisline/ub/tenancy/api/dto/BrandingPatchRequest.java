package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Tenant-self-service branding patch. Every field is optional: nulls are
 * ignored (no-op), empty strings clear the existing value. Colors must be
 * 6-digit hex strings (e.g. {@code #0F766E}); validation is enforced at
 * controller level so an invalid value never reaches the JSON namespace.
 */
public record BrandingPatchRequest(
        @Size(max = 255) String displayName,
        @Size(max = 1024) String logoUrl,
        @Size(max = 255) String logoPublicId,
        @Size(max = 1024) String faviconUrl,
        @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "primaryColor must be #RRGGBB")
        @Size(max = 7) String primaryColor,
        @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "accentColor must be #RRGGBB")
        @Size(max = 7) String accentColor
) {
}
