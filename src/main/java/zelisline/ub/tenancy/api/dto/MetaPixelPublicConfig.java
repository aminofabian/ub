package zelisline.ub.tenancy.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Public, secret-free subset of the tenant's Meta pixel configuration carried
 * on the unauthenticated host-resolve payload so the storefront can load the
 * pixel snippet and gate {@code fbq} calls.
 *
 * <p>{@code enabled} is only true when the tenant enabled the integration
 * <em>and</em> a pixel id is configured — the frontend must never init {@code fbq}
 * without a concrete pixel id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetaPixelPublicConfig(
		boolean enabled,
		String pixelId
) {
	public static MetaPixelPublicConfig disabled() {
		return new MetaPixelPublicConfig(false, null);
	}
}
