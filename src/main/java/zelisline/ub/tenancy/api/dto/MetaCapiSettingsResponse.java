package zelisline.ub.tenancy.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tenant-visible Meta Pixel/CAPI settings. The access token is never
 * serialized — only whether one is stored.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetaCapiSettingsResponse(
		Boolean enabled,
		String pixelId,
		boolean hasAccessToken,
		Boolean consentRequired
) {
	public static MetaCapiSettingsResponse empty() {
		return new MetaCapiSettingsResponse(null, null, false, null);
	}
}
