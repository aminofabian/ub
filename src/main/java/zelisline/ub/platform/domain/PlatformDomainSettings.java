package zelisline.ub.platform.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "platform_domain_settings")
@Getter
@Setter
public class PlatformDomainSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "hostafrica_api_key_enc", columnDefinition = "TEXT")
    private String hostafricaApiKeyEnc;

    @Column(name = "hostafrica_api_base_url", length = 512)
    private String hostafricaApiBaseUrl;

    @Column(name = "hostafrica_currency", length = 16)
    private String hostafricaCurrency;

    @Column(name = "hostafrica_kenyan_tlds", length = 255)
    private String hostafricaKenyanTlds;

    @Column(name = "hostafrica_billing_stub_enabled", nullable = false)
    private boolean hostafricaBillingStubEnabled = true;

    /**
     * JSON object of HostAfrica additionalFields name → default value
     * (used by save-domain-required-data after purchase).
     */
    @Column(name = "hostafrica_registrant_defaults_json", columnDefinition = "TEXT")
    private String hostafricaRegistrantDefaultsJson;

    /** DomainsReseller login email (username header). */
    @Column(name = "hostafrica_reseller_email", length = 255)
    private String hostafricaResellerEmail;

    /** Encrypted DomainsReseller API key (HMAC secret). */
    @Column(name = "hostafrica_reseller_api_key_enc", columnDefinition = "TEXT")
    private String hostafricaResellerApiKeyEnc;

    @Column(name = "hostafrica_reseller_api_base_url", length = 512)
    private String hostafricaResellerApiBaseUrl;

    /**
     * JSON WHOIS contact used for Registrant/Admin/Tech/Billing on RegisterDomain:
     * firstname, lastname, companyname, email, address1, address2, city, state, postcode, country, phonenumber.
     */
    @Column(name = "hostafrica_reseller_whois_json", columnDefinition = "TEXT")
    private String hostafricaResellerWhoisJson;

    /** Encrypted JSON: clientId, clientSecret, apiKey, tillNumber, environment. */
    @Column(name = "palmart_stk_credentials_enc", columnDefinition = "TEXT")
    private String palmartStkCredentialsEnc;

    /** Denormalized till for SA display (not secret). */
    @Column(name = "palmart_stk_till_number", length = 32)
    private String palmartStkTillNumber;

    @Column(name = "vercel_token_enc", columnDefinition = "TEXT")
    private String vercelTokenEnc;

    @Column(name = "vercel_team_id", length = 128)
    private String vercelTeamId;

    @Column(name = "vercel_project_id", length = 128)
    private String vercelProjectId;

    @Column(name = "vercel_api_base_url", length = 512)
    private String vercelApiBaseUrl;

    @Column(name = "domain_order_sync_enabled", nullable = false)
    private boolean domainOrderSyncEnabled = false;

    @Column(name = "domain_order_sync_fixed_delay_ms", nullable = false)
    private int domainOrderSyncFixedDelayMs = 60_000;

    @Column(name = "domain_order_sync_initial_delay_ms", nullable = false)
    private int domainOrderSyncInitialDelayMs = 20_000;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
