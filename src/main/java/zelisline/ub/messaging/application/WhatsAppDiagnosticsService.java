package zelisline.ub.messaging.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.WhatsAppDiagnosticsResponse;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.infrastructure.MetaWhatsAppDiagnosticsClient;

/**
 * Answers "why don't cold numbers receive WhatsApp anymore?".
 *
 * <p>Business-initiated messages need an approved, active template. Replies inside Meta's
 * 24h window keep working even when templates are paused or the account is throttled, which
 * is exactly the "only people who replied get messages" symptom.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppDiagnosticsService {

    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final MetaWhatsAppDiagnosticsClient diagnosticsClient;

    public WhatsAppDiagnosticsResponse diagnose(String businessId, String wabaIdOverride) {
        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(businessId);
        List<String> findings = new ArrayList<>();

        if (!messaging.secretsReadable()) {
            findings.add("Stored messaging credentials could not be decrypted on the server"
                    + (messaging.secretsReadError() != null ? ": " + messaging.secretsReadError() : "."));
        }
        if (!messaging.metaWhatsAppConfigured()) {
            findings.add("Meta WhatsApp is not configured (needs phone number ID + access token).");
            return new WhatsAppDiagnosticsResponse(
                    false,
                    messaging.metaPhoneNumberId(),
                    messaging.metaGraphVersion(),
                    messaging.metaAccessTokenSource(),
                    messaging.metaAccessTokenFingerprint(),
                    null, null, null, null, null, null,
                    null, null,
                    List.of(), null,
                    false,
                    List.copyOf(findings));
        }

        var phone = diagnosticsClient.fetchPhoneHealth(messaging);
        if (phone.error() != null) {
            findings.add("Could not read phone number health from Meta: " + phone.error());
        }
        if ("RED".equalsIgnoreCase(phone.qualityRating())) {
            findings.add("Phone quality rating is RED — Meta heavily restricts business-initiated"
                    + " messages until quality recovers. Replies inside the 24h window still work.");
        } else if ("YELLOW".equalsIgnoreCase(phone.qualityRating())) {
            findings.add("Phone quality rating is YELLOW — template sends may be throttled.");
        }
        if (phone.messagingLimitTier() != null
                && phone.messagingLimitTier().toUpperCase(Locale.ROOT).contains("250")) {
            findings.add("Messaging limit is " + phone.messagingLimitTier()
                    + " — only 250 new customers can be messaged per 24h. Once the cap is hit,"
                    + " cold sends fail while existing conversations continue.");
        }

        String wabaId = wabaIdOverride != null && !wabaIdOverride.isBlank()
                ? wabaIdOverride.trim()
                : null;
        String wabaError = null;
        if (wabaId == null) {
            var lookup = diagnosticsClient.fetchWabaId(messaging);
            wabaId = lookup.id();
            wabaError = lookup.error();
        }

        var templateLookup = diagnosticsClient.fetchTemplates(messaging, wabaId);
        List<WhatsAppDiagnosticsResponse.TemplateStatus> relevant = new ArrayList<>();
        Set<String> languagesFound = new LinkedHashSet<>();
        boolean paymentReminderUsable = false;

        for (var template : templateLookup.templates()) {
            boolean isOurs = CustomerMessageDispatcher.PAYMENT_REMINDER_TEMPLATE.equalsIgnoreCase(template.name())
                    || CustomerMessageDispatcher.CREDIT_SALE_RECEIPT_TEMPLATE.equalsIgnoreCase(template.name());
            if (!isOurs) {
                continue;
            }
            relevant.add(new WhatsAppDiagnosticsResponse.TemplateStatus(
                    template.name(),
                    template.language(),
                    template.status(),
                    template.category(),
                    template.rejectedReason(),
                    template.qualityScore()));
            if (CustomerMessageDispatcher.PAYMENT_REMINDER_TEMPLATE.equalsIgnoreCase(template.name())) {
                if (template.language() != null) {
                    languagesFound.add(template.language());
                }
                if ("APPROVED".equalsIgnoreCase(template.status())) {
                    paymentReminderUsable = true;
                } else if ("PAUSED".equalsIgnoreCase(template.status())) {
                    findings.add("Template payment_reminder (" + template.language()
                            + ") is PAUSED by Meta for low quality — this is the usual reason cold"
                            + " sends stop while replies keep working. Unpause it in WhatsApp Manager"
                            + " once the quality warning clears.");
                } else if ("REJECTED".equalsIgnoreCase(template.status())
                        || "DISABLED".equalsIgnoreCase(template.status())) {
                    findings.add("Template payment_reminder (" + template.language() + ") is "
                            + template.status()
                            + (template.rejectedReason() != null ? " — " + template.rejectedReason() : "")
                            + ". Cold sends cannot work until an approved template exists.");
                } else if ("PENDING".equalsIgnoreCase(template.status())) {
                    findings.add("Template payment_reminder (" + template.language()
                            + ") is still PENDING review — Meta rejects sends until approved.");
                }
            }
        }

        if (templateLookup.error() != null) {
            findings.add("Could not list message templates: " + templateLookup.error()
                    + (wabaId == null
                            ? " Pass your WhatsApp Business Account ID to check template status."
                            : ""));
        } else if (relevant.isEmpty()) {
            findings.add("No payment_reminder or credit_sale_receipt template exists on this"
                    + " WhatsApp Business Account. Business-initiated messages require an approved"
                    + " template; only replies within 24h can be free-form.");
        } else if (!languagesFound.isEmpty()) {
            findings.add("payment_reminder languages registered in Meta: " + String.join(", ", languagesFound)
                    + ". We try " + CustomerMessageDispatcher.PAYMENT_REMINDER_LANGUAGE
                    + " first, then en_US, en and en_GB.");
        }

        boolean coldReady = paymentReminderUsable
                && !"RED".equalsIgnoreCase(phone.qualityRating())
                && phone.error() == null;
        if (coldReady && findings.isEmpty()) {
            findings.add("Template and account health look fine — if a specific number still fails,"
                    + " check the send error detail on the WhatsApp test for that number.");
        }

        return new WhatsAppDiagnosticsResponse(
                true,
                messaging.metaPhoneNumberId(),
                messaging.metaGraphVersion(),
                messaging.metaAccessTokenSource(),
                messaging.metaAccessTokenFingerprint(),
                phone.displayPhoneNumber(),
                phone.verifiedName(),
                phone.qualityRating(),
                phone.messagingLimitTier(),
                phone.nameStatus(),
                phone.error(),
                wabaId,
                wabaError,
                List.copyOf(relevant),
                templateLookup.error(),
                coldReady,
                List.copyOf(findings));
    }
}
