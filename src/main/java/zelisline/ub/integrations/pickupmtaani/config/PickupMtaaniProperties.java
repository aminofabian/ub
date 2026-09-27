package zelisline.ub.integrations.pickupmtaani.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-level Pickup Mtaani settings. The tenant API key lives encrypted in
 * {@code businesses.settings.pickupMtaani}; these are the global knobs (base URL
 * and HTTP timeouts).
 *
 * <p>Timeouts follow the scope: reads are cheap and fail fast, package create is
 * allowed a longer socket budget.
 */
@ConfigurationProperties(prefix = "app.pickup-mtaani")
public record PickupMtaaniProperties(
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        int createTimeoutMs
) {

    public static final String DEFAULT_BASE_URL = "https://api.pickupmtaani.com/api/v1";

    public PickupMtaaniProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        // Trim any trailing slash so path concatenation stays predictable.
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 5_000;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = 10_000;
        }
        if (createTimeoutMs <= 0) {
            createTimeoutMs = 20_000;
        }
    }
}
