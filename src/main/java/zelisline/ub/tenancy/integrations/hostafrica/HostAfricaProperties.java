package zelisline.ub.tenancy.integrations.hostafrica;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * HostAfrica registrar credentials (Coolify secrets). Used for Kenyan TLD
 * availability, ownership poll, and nameserver cutover.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.integrations.hostafrica")
public class HostAfricaProperties {

    /** Bearer token ({@code HOSTAFRICA_API_KEY}). */
    private String apiKey = "";

    /** API base, no trailing slash. */
    private String apiBaseUrl = "https://api.hostafrica.com";

    /** Currency for availability quotes (ISO). Defaults to KES. */
    private String currency = "KES";

    /**
     * When true, {@code POST …/domain-orders} treats merchant confirm as paid
     * without a Palmart wallet charge (billing hook lands later).
     */
    private boolean billingStubEnabled = true;

    /**
     * Comma-separated TLDs offered in search when the merchant types a bare label
     * (e.g. {@code mama-njeri} → {@code mama-njeri.co.ke}, …).
     */
    private String kenyanTlds = "co.ke,or.ke,me.ke,sc.ke,ac.ke,go.ke,ke";

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
