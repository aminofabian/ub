package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Write-only patch for the per-tenant Meta Pixel + Conversions API configuration
 * ({@code businesses.settings.metaCapi}).
 *
 * <p>Semantics: {@code null} leaves the value unchanged; blank clears it.
 * {@code accessToken} is never returned by any endpoint — it is encrypted at
 * rest (see {@code MetaCapiSettingsService.merge}).
 */
public record MetaCapiPatchRequest(
		Boolean enabled,
		/** Meta pixel id (digits only). Blank clears; null leaves unchanged. */
		@Size(max = 64)
		@Pattern(regexp = "\\d{0,64}")
		String pixelId,
		/** Write-only CAPI access token. Blank clears; null leaves unchanged. */
		@Size(max = 512)
		String accessToken,
		/** Test event code for staging verification. Blank clears; null leaves unchanged. */
		@Size(max = 64)
		String testEventCode,
		/** When true the frontend defers pixel firing until the visitor consents. */
		Boolean consentRequired
) {
}
