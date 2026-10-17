package zelisline.ub.integrations.easytax.api.dto;

/**
 * Platform-wide EasyTax configuration. Tenants never set these URLs — they choose an
 * environment and the platform supplies the endpoint.
 */
public record PlatformEasyTaxSettingsResponse(
        boolean enabled,
        String sandboxBaseUrl,
        String productionBaseUrl
) {
}
