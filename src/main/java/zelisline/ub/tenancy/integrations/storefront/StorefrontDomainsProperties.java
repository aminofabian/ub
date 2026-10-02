package zelisline.ub.tenancy.integrations.storefront;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Storefront custom-domain hosting: which provider attaches hostnames, where
 * tenants should point DNS, and whether domain purchase is open.
 *
 * <p>Pre-cutover default is {@code vercel}. After the Coolify DNS flip, set
 * {@code APP_DOMAINS_PROVIDER=coolify} and clear the Vercel token.
 */
@ConfigurationProperties(prefix = "app.domains")
public class StorefrontDomainsProperties {

    /** {@code vercel} or {@code coolify}. */
    private String provider = "vercel";

    /**
     * Apex A-record target shown to tenants (and checked by {@link DnsTargetChecker}).
     * Empty keeps the provider's built-in default (Vercel IP or Coolify server).
     */
    private String storefrontATarget = "";

    /**
     * {@code www} (and subdomain) CNAME target. Empty keeps the provider default.
     */
    private String storefrontCnameTarget = "";

    /** Kill-switch for Buy a .ke name until Cloudflare zone provisioning ships. */
    private boolean buyEnabled = false;

    /** Minutes a Coolify domain may stay VERIFYING before FAILED. */
    private int verifyTimeoutMinutes = 30;

    private final Coolify coolify = new Coolify();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getStorefrontATarget() {
        return storefrontATarget;
    }

    public void setStorefrontATarget(String storefrontATarget) {
        this.storefrontATarget = storefrontATarget;
    }

    public String getStorefrontCnameTarget() {
        return storefrontCnameTarget;
    }

    public void setStorefrontCnameTarget(String storefrontCnameTarget) {
        this.storefrontCnameTarget = storefrontCnameTarget;
    }

    public boolean isBuyEnabled() {
        return buyEnabled;
    }

    public void setBuyEnabled(boolean buyEnabled) {
        this.buyEnabled = buyEnabled;
    }

    public int getVerifyTimeoutMinutes() {
        return verifyTimeoutMinutes;
    }

    public void setVerifyTimeoutMinutes(int verifyTimeoutMinutes) {
        this.verifyTimeoutMinutes = verifyTimeoutMinutes;
    }

    public Coolify getCoolify() {
        return coolify;
    }

    public boolean isCoolifyProvider() {
        return "coolify".equalsIgnoreCase(trim(provider));
    }

    public boolean isVercelProvider() {
        return !isCoolifyProvider();
    }

    public String resolvedATarget() {
        String configured = trim(storefrontATarget);
        if (!configured.isEmpty()) {
            return configured;
        }
        return isCoolifyProvider() ? "148.113.255.170" : "76.76.21.21";
    }

    public String resolvedCnameTarget() {
        String configured = trim(storefrontCnameTarget);
        if (!configured.isEmpty()) {
            return configured;
        }
        return isCoolifyProvider() ? "connect.kiosk.ke" : "cname.vercel-dns.com";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static class Coolify {
        private String apiUrl = "";
        private String apiToken = "";
        private String appUuid = "";

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getApiToken() {
            return apiToken;
        }

        public void setApiToken(String apiToken) {
            this.apiToken = apiToken;
        }

        public String getAppUuid() {
            return appUuid;
        }

        public void setAppUuid(String appUuid) {
            this.appUuid = appUuid;
        }

        public boolean configured() {
            return !trim(apiUrl).isEmpty()
                    && !trim(apiToken).isEmpty()
                    && !trim(appUuid).isEmpty();
        }
    }
}
