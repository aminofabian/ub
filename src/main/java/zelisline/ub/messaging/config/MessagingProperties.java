package zelisline.ub.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
        CreditSaleReminder creditSaleReminder,
        RapidApiWhatsApp rapidApiWhatsApp,
        MetaWhatsApp metaWhatsApp,
        Sms sms
) {

    public MessagingProperties {
        if (creditSaleReminder == null) {
            creditSaleReminder = new CreditSaleReminder("https://palmart.co.ke/shop/account");
        }
        if (rapidApiWhatsApp == null) {
            rapidApiWhatsApp = new RapidApiWhatsApp("", "whatsapp-osint.p.rapidapi.com", "https://whatsapp-osint.p.rapidapi.com/bizos");
        }
        if (metaWhatsApp == null) {
            metaWhatsApp = new MetaWhatsApp("", "", "v25.0", "", "");
        }
        if (sms == null) {
            sms = new Sms("none", "", "");
        }
    }

    /** {@code paymentAccountUrl} is a platform default when the tenant has not set one in admin. */
    public record CreditSaleReminder(String paymentAccountUrl) {
    }

    public record RapidApiWhatsApp(String apiKey, String host, String lookupUrl) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record MetaWhatsApp(
            String accessToken,
            String phoneNumberId,
            String graphVersion,
            String webhookVerifyToken,
            String appSecret
    ) {
        public boolean configured() {
            return accessToken != null && !accessToken.isBlank()
                    && phoneNumberId != null && !phoneNumberId.isBlank();
        }

        public boolean webhookVerifyConfigured() {
            return webhookVerifyToken != null && !webhookVerifyToken.isBlank();
        }
    }

    public record Sms(String provider, String africasTalkingUsername, String africasTalkingApiKey) {
        public boolean africasTalkingConfigured() {
            return "africas_talking".equalsIgnoreCase(provider)
                    && africasTalkingUsername != null && !africasTalkingUsername.isBlank()
                    && africasTalkingApiKey != null && !africasTalkingApiKey.isBlank();
        }
    }
}
