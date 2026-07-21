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
            creditSaleReminder = new CreditSaleReminder("https://palmart.co.ke");
        }
        if (rapidApiWhatsApp == null) {
            rapidApiWhatsApp = new RapidApiWhatsApp("", "whatsapp-osint.p.rapidapi.com", "https://whatsapp-osint.p.rapidapi.com/bizos");
        }
        if (metaWhatsApp == null) {
            metaWhatsApp = new MetaWhatsApp("", "", "v25.0", "", "");
        }
        if (sms == null) {
            sms = new Sms(
                    "none", "", "",
                    "", "", "Sozuri", "transactional", "https://sozuri.net/api/v1/messaging",
                    "", "", "", "https://sms.textsms.co.ke/api/services/sendsms/");
        }
    }

    /** {@code paymentAccountUrl} is a site origin; customer phone path is appended per message. */
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

    public record Sms(
            String provider,
            String africasTalkingUsername,
            String africasTalkingApiKey,
            String sozuriProject,
            String sozuriApiKey,
            String sozuriFrom,
            String sozuriType,
            String sozuriApiUrl,
            String textsmsPartnerId,
            String textsmsApiKey,
            String textsmsShortcode,
            String textsmsApiUrl
    ) {
        public boolean africasTalkingConfigured() {
            return "africas_talking".equalsIgnoreCase(provider)
                    && africasTalkingUsername != null && !africasTalkingUsername.isBlank()
                    && africasTalkingApiKey != null && !africasTalkingApiKey.isBlank();
        }

        public boolean sozuriConfigured() {
            return "sozuri".equalsIgnoreCase(provider)
                    && sozuriProject != null && !sozuriProject.isBlank()
                    && sozuriApiKey != null && !sozuriApiKey.isBlank();
        }

        public boolean textsmsConfigured() {
            return "textsms".equalsIgnoreCase(provider)
                    && textsmsPartnerId != null && !textsmsPartnerId.isBlank()
                    && textsmsApiKey != null && !textsmsApiKey.isBlank()
                    && textsmsShortcode != null && !textsmsShortcode.isBlank();
        }
    }
}
