package zelisline.ub.integrations.metacapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-level Meta Conversions API settings. Tenant pixel/token live in
 * {@code businesses.settings.metaCapi}; these are the global knobs (graph
 * version, staging-only test event code, delivery retry budget, HTTP timeouts).
 */
@ConfigurationProperties(prefix = "app.meta-capi")
public record MetaCapiProperties(
        String graphVersion,
        /** Staging-only global override; takes precedence over per-tenant test event codes. */
        String testEventCode,
        long retryIntervalMs,
        int retryMaxAttempts,
        int connectTimeoutMs,
        int socketTimeoutMs
) {

    public MetaCapiProperties {
        if (graphVersion == null || graphVersion.isBlank()) {
            graphVersion = "v23.0";
        }
        if (testEventCode == null) {
            testEventCode = "";
        }
        if (retryIntervalMs <= 0) {
            retryIntervalMs = 30_000;
        }
        if (retryMaxAttempts <= 0) {
            retryMaxAttempts = 5;
        }
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 5_000;
        }
        if (socketTimeoutMs <= 0) {
            socketTimeoutMs = 12_000;
        }
    }
}
