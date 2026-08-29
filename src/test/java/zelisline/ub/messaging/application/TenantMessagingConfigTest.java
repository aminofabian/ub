package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TenantMessagingConfigTest {

    @Test
    void smsConfigured_infersTextsmsWhenProviderIsNoneButCredentialsPresent() {
        TenantMessagingConfig cfg = base(
                "none",
                null,
                null,
                null,
                null,
                "12345",
                "secret-key",
                "SENDER");
        assertTrue(cfg.smsConfigured());
        assertEquals("textsms", cfg.effectiveSmsProvider());
    }

    @Test
    void smsConfigured_falseWhenProviderNoneAndNoCredentials() {
        TenantMessagingConfig cfg = base("none", null, null, null, null, null, null, null);
        assertFalse(cfg.smsConfigured());
        assertTrue(cfg.smsNotConfiguredHint().contains("none"));
    }

    @Test
    void smsNotConfiguredHint_detectsMissingTextsmsApiKey() {
        TenantMessagingConfig cfg = base(
                "textsms",
                null,
                null,
                null,
                null,
                "12345",
                null,
                "SENDER");
        assertFalse(cfg.smsConfigured());
        assertTrue(cfg.smsNotConfiguredHint().toLowerCase().contains("api key"));
    }

    private static TenantMessagingConfig base(
            String provider,
            String atUser,
            String atKey,
            String sozuriProject,
            String sozuriKey,
            String textsmsPartner,
            String textsmsKey,
            String textsmsShortcode
    ) {
        return new TenantMessagingConfig(
                true,
                "https://pay.example",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                provider,
                atUser,
                atKey,
                sozuriProject,
                sozuriKey,
                "Sozuri",
                "transactional",
                "https://sozuri.net/api/v1/messaging",
                textsmsPartner,
                textsmsKey,
                textsmsShortcode,
                "https://sms.textsms.co.ke/api/services/sendsms/",
                true,
                null,
                "biz-1",
                null);
    }
}
