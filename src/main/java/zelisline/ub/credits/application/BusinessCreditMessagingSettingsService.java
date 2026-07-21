package zelisline.ub.credits.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.CreditSaleReminderSettingsResponse;
import zelisline.ub.credits.api.dto.UpdateCreditSaleReminderSettingsRequest;
import zelisline.ub.credits.domain.BusinessCreditSettings;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.config.MessagingProperties;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.platform.application.ResolvedRapidApiWhatsappConfig;

@Service
@RequiredArgsConstructor
public class BusinessCreditMessagingSettingsService {

    private final BusinessCreditSettingsService businessCreditSettingsService;
    private final CredentialEncryptionService encryptionService;
    private final MessagingProperties messagingProperties;
    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Transactional(readOnly = true)
    public CreditSaleReminderSettingsResponse getForAdmin(String businessId) {
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(businessId);
        SecretRead read = readSecrets(s);
        return toResponse(s, read);
    }

    @Transactional(readOnly = true)
    public TenantMessagingConfig resolveForDispatch(String businessId) {
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(businessId);
        SecretRead read = readSecrets(s);
        if (!read.readable()) {
            return disabledConfig(read.errorMessage());
        }
        if (!s.isCreditSaleReminderEnabled()) {
            return disabledConfig(null);
        }
        return buildConfig(s, true);
    }

    /**
     * Resolves messaging credentials for an admin-triggered test send. Unlike
     * {@link #resolveForDispatch}, this ignores the "reminders enabled" toggle so
     * an admin can verify WhatsApp/SMS delivery before turning reminders on.
     */
    @Transactional(readOnly = true)
    public TenantMessagingConfig resolveForTest(String businessId) {
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(businessId);
        SecretRead read = readSecrets(s);
        if (!read.readable()) {
            return disabledConfig(read.errorMessage());
        }
        return buildConfig(s, true);
    }

    private TenantMessagingConfig buildConfig(BusinessCreditSettings s, boolean enabled) {
        var env = messagingProperties;
        ResolvedRapidApiWhatsappConfig platformWa =
                platformIntegrationSettingsService.resolveRapidApiWhatsapp();
        String paymentUrl = firstNonBlank(
                trimToNull(s.getCreditSaleReminderPaymentUrl()),
                env.creditSaleReminder().paymentAccountUrl(),
                defaultPaymentUrl());
        boolean digitsOnly =
                s.getRapidapiPhoneDigitsOnly() != null
                        ? s.getRapidapiPhoneDigitsOnly()
                        : platformWa.phoneDigitsOnly();
        return new TenantMessagingConfig(
                enabled,
                paymentUrl,
                firstNonBlank(
                        decryptOrNull(s.getRapidapiKeyEnc()),
                        platformWa.apiKey()),
                firstNonBlank(trimToNull(s.getRapidapiHost()), platformWa.host()),
                firstNonBlank(trimToNull(s.getRapidapiLookupUrl()), platformWa.lookupUrl()),
                firstNonBlank(trimToNull(s.getRapidapiPhoneField()), platformWa.phoneField()),
                digitsOnly,
                firstNonBlank(trimToNull(decryptOrNull(s.getWhatsappMetaAccessTokenEnc())), trimToNull(env.metaWhatsApp().accessToken())),
                firstNonBlank(trimToNull(s.getWhatsappMetaPhoneNumberId()), trimToNull(env.metaWhatsApp().phoneNumberId())),
                firstNonBlank(trimToNull(s.getWhatsappMetaGraphVersion()), env.metaWhatsApp().graphVersion()),
                firstNonBlank(trimToNull(s.getSmsProvider()), env.sms().provider()),
                firstNonBlank(trimToNull(s.getSmsAfricasTalkingUsername()), env.sms().africasTalkingUsername()),
                firstNonBlank(decryptOrNull(s.getSmsAfricasTalkingApiKeyEnc()), env.sms().africasTalkingApiKey()),
                true,
                null);
    }

    @Transactional
    public CreditSaleReminderSettingsResponse update(String businessId, UpdateCreditSaleReminderSettingsRequest body) {
        if (body.paymentAccountUrl() == null || body.paymentAccountUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment account URL is required");
        }
        String smsProvider = body.smsProvider() == null ? "none" : body.smsProvider().trim().toLowerCase();
        if (!"none".equals(smsProvider) && !"africas_talking".equals(smsProvider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SMS provider must be none or africas_talking");
        }
        if ("africas_talking".equals(smsProvider)) {
            if (body.smsAfricasTalkingUsername() == null || body.smsAfricasTalkingUsername().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Africa's Talking username required");
            }
        }

        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(businessId);
        s.setCreditSaleReminderEnabled(body.enabled());
        s.setCreditSaleReminderPaymentUrl(body.paymentAccountUrl().trim());
        s.setWhatsappMetaPhoneNumberId(blankToNull(body.whatsappMetaPhoneNumberId()));
        s.setWhatsappMetaGraphVersion(
                body.whatsappMetaGraphVersion() == null || body.whatsappMetaGraphVersion().isBlank()
                        ? "v25.0"
                        : body.whatsappMetaGraphVersion().trim());
        s.setSmsProvider(smsProvider);
        s.setSmsAfricasTalkingUsername(blankToNull(body.smsAfricasTalkingUsername()));

        if (body.rapidApiKey() != null) {
            s.setRapidapiKeyEnc(encryptOrClear(body.rapidApiKey()));
        }
        if (body.rapidApiHost() != null) {
            s.setRapidapiHost(blankToNull(body.rapidApiHost()));
        }
        if (body.rapidApiLookupUrl() != null) {
            s.setRapidapiLookupUrl(blankToNull(body.rapidApiLookupUrl()));
        }
        if (body.rapidApiPhoneField() != null) {
            s.setRapidapiPhoneField(blankToNull(body.rapidApiPhoneField()));
        }
        if (body.rapidApiPhoneDigitsOnly() != null) {
            s.setRapidapiPhoneDigitsOnly(body.rapidApiPhoneDigitsOnly());
        }
        if (body.whatsappMetaAccessToken() != null) {
            s.setWhatsappMetaAccessTokenEnc(encryptOrClear(body.whatsappMetaAccessToken()));
        }
        if (body.smsAfricasTalkingApiKey() != null) {
            s.setSmsAfricasTalkingApiKeyEnc(encryptOrClear(body.smsAfricasTalkingApiKey()));
        }

        BusinessCreditSettings saved = businessCreditSettingsService.saveSettings(s);
        return toResponse(saved, readSecrets(saved));
    }

    private CreditSaleReminderSettingsResponse toResponse(BusinessCreditSettings s, SecretRead read) {
        String defaultUrl = defaultPaymentUrl();
        ResolvedRapidApiWhatsappConfig platformWa =
                platformIntegrationSettingsService.resolveRapidApiWhatsapp();
        boolean digitsOnly =
                s.getRapidapiPhoneDigitsOnly() != null
                        ? s.getRapidapiPhoneDigitsOnly()
                        : platformWa.phoneDigitsOnly();
        return new CreditSaleReminderSettingsResponse(
                s.isCreditSaleReminderEnabled(),
                firstNonBlank(trimToNull(s.getCreditSaleReminderPaymentUrl()), defaultUrl),
                defaultUrl,
                trimToNull(s.getWhatsappMetaPhoneNumberId()),
                firstNonBlank(trimToNull(s.getWhatsappMetaGraphVersion()), "v25.0"),
                firstNonBlank(trimToNull(s.getSmsProvider()), "none"),
                trimToNull(s.getSmsAfricasTalkingUsername()),
                read.hasRapidApiKey,
                firstNonBlank(trimToNull(s.getRapidapiHost()), platformWa.host()),
                firstNonBlank(trimToNull(s.getRapidapiLookupUrl()), platformWa.lookupUrl()),
                firstNonBlank(trimToNull(s.getRapidapiPhoneField()), platformWa.phoneField()),
                digitsOnly,
                read.hasWhatsappToken,
                read.hasSmsApiKey,
                read.readable,
                read.errorMessage);
    }

    private SecretRead readSecrets(BusinessCreditSettings s) {
        String persistenceHint = null;
        if (encryptionService.usesEphemeralKey()) {
            persistenceHint =
                    "Set APP_PAYMENTS_ENCRYPTION_KEY on the server so saved API keys survive a restart. "
                            + "You can still save and use keys until the next deploy.";
        }
        return new SecretRead(
                hasEncrypted(s.getRapidapiKeyEnc()),
                hasEncrypted(s.getWhatsappMetaAccessTokenEnc()),
                hasEncrypted(s.getSmsAfricasTalkingApiKeyEnc()),
                true,
                persistenceHint);
    }

    private static boolean hasEncrypted(String enc) {
        return enc != null && !enc.isBlank();
    }

    private String encryptOrClear(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return encryptionService.encryptSecret(raw.trim());
    }

    private String decryptOrNull(String enc) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(enc);
        } catch (Exception ex) {
            return null;
        }
    }

    private String defaultPaymentUrl() {
        String base = frontendBaseUrl == null ? "http://localhost:3000" : frontendBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/shop/account";
    }

    private static TenantMessagingConfig disabledConfig(String readError) {
        return new TenantMessagingConfig(
                false, "", null, null, null, null, false, null, null, null, "none", null, null,
                readError == null, readError);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String trimToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static String blankToNull(String raw) {
        return trimToNull(raw);
    }

    private record SecretRead(
            boolean hasRapidApiKey,
            boolean hasWhatsappToken,
            boolean hasSmsApiKey,
            boolean readable,
            String errorMessage
    ) {
    }
}
