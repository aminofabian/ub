package zelisline.ub.messaging.application;

import zelisline.ub.messaging.domain.SmsSendReason;

/**
 * Resolved per-tenant messaging credentials for credit tab sale reminders.
 * <p>{@code businessId} is null for platform-scoped sends (super-admin replies,
 * supplier-portal invites, test SMS) which are exempt from SMS credit metering.
 * {@code smsReason} classifies the tenant's sends on the credit ledger.
 */
public record TenantMessagingConfig(
        boolean enabled,
        String paymentAccountUrl,
        String rapidApiKey,
        String rapidApiHost,
        String rapidApiLookupUrl,
        String rapidApiPhoneField,
        boolean rapidApiPhoneDigitsOnly,
        String metaAccessToken,
        String metaPhoneNumberId,
        String metaGraphVersion,
        String metaAccessTokenSource,
        String smsProvider,
        String smsUsername,
        String smsApiKey,
        String smsSozuriProject,
        String smsSozuriApiKey,
        String smsSozuriFrom,
        String smsSozuriType,
        String smsSozuriApiUrl,
        String smsTextsmsPartnerId,
        String smsTextsmsApiKey,
        String smsTextsmsShortcode,
        String smsTextsmsApiUrl,
        boolean secretsReadable,
        String secretsReadError,
        String businessId,
        SmsSendReason smsReason
) {
    public boolean rapidApiConfigured() {
        return rapidApiKey != null && !rapidApiKey.isBlank()
                && rapidApiHost != null && !rapidApiHost.isBlank()
                && rapidApiLookupUrl != null && !rapidApiLookupUrl.isBlank();
    }

    public boolean metaWhatsAppConfigured() {
        return metaAccessToken != null && !metaAccessToken.isBlank()
                && metaPhoneNumberId != null && !metaPhoneNumberId.isBlank();
    }

    public String metaAccessTokenFingerprint() {
        if (metaAccessToken == null || metaAccessToken.isBlank()) {
            return "none";
        }
        String t = metaAccessToken.trim();
        if (t.length() <= 4) {
            return "****";
        }
        return "…" + t.substring(t.length() - 4);
    }

    /**
     * Provider used for send routing. Prefer a declared provider that has credentials;
     * otherwise infer from whichever credential set is complete. This covers cases where
     * TextSMS/Sozuri keys were saved (or come from env) but {@code smsProvider} stayed
     * {@code none}.
     */
    public String effectiveSmsProvider() {
        String declared = smsProvider == null ? "none" : smsProvider.trim().toLowerCase();
        if ("textsms".equals(declared) && textsmsCredentialsReady()) {
            return "textsms";
        }
        if ("sozuri".equals(declared) && sozuriCredentialsReady()) {
            return "sozuri";
        }
        if ("africas_talking".equals(declared) && africasTalkingCredentialsReady()) {
            return "africas_talking";
        }
        if (textsmsCredentialsReady()) {
            return "textsms";
        }
        if (sozuriCredentialsReady()) {
            return "sozuri";
        }
        if (africasTalkingCredentialsReady()) {
            return "africas_talking";
        }
        return declared.isBlank() ? "none" : declared;
    }

    public boolean smsConfigured() {
        String provider = effectiveSmsProvider();
        return switch (provider) {
            case "textsms" -> textsmsCredentialsReady();
            case "sozuri" -> sozuriCredentialsReady();
            case "africas_talking" -> africasTalkingCredentialsReady();
            default -> false;
        };
    }

    public boolean textsmsCredentialsReady() {
        return present(smsTextsmsPartnerId) && present(smsTextsmsApiKey) && present(smsTextsmsShortcode);
    }

    public boolean sozuriCredentialsReady() {
        return present(smsSozuriProject) && present(smsSozuriApiKey);
    }

    public boolean africasTalkingCredentialsReady() {
        return present(smsUsername) && present(smsApiKey);
    }

    /** Operator-facing hint when {@link #smsConfigured()} is false (no secrets). */
    public String smsNotConfiguredHint() {
        String declared = smsProvider == null ? "none" : smsProvider.trim();
        boolean partner = present(smsTextsmsPartnerId);
        boolean shortcode = present(smsTextsmsShortcode);
        boolean textsmsKey = present(smsTextsmsApiKey);
        boolean sozuriProject = present(smsSozuriProject);
        boolean sozuriKey = present(smsSozuriApiKey);
        if ((partner || shortcode) && !textsmsKey) {
            return "TextSMS partner/shortcode are present but the API key is missing or could not be"
                    + " decrypted. Re-save the TextSMS API key under Super Admin → Platform"
                    + " integrations (or Customers → messaging), then retry.";
        }
        if (sozuriProject && !sozuriKey) {
            return "Sozuri project is set but the API key is missing or could not be decrypted."
                    + " Re-save the Sozuri API key under Super Admin → Platform integrations"
                    + " (or Customers → messaging), then retry.";
        }
        if ("none".equalsIgnoreCase(declared) && !partner && !sozuriProject) {
            return "SMS provider is still \"none\". Choose TextSMS or Sozuri under Super Admin →"
                    + " Platform integrations (or Customers → messaging), fill the credentials,"
                    + " save, then retry.";
        }
        return "SMS must be configured to send verification codes. Set Sozuri or TextSMS under"
                + " Super Admin → Platform integrations, or under Customers → messaging"
                + " settings, then retry. (provider=" + declared + ")";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
