package zelisline.ub.opsalerts.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.opsalerts.api.dto.OpsAlertTestSendResponse;
import zelisline.ub.opsalerts.domain.BusinessOpsAlertSettings;
import zelisline.ub.opsalerts.domain.OpsAlertType;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class TenantOpsAlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TenantOpsAlertDispatcher.class);

    private final BusinessOpsAlertSettingsService settingsService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;

    public void dispatch(String businessId, OpsAlertType type, String message) {
        if (businessId == null || businessId.isBlank() || message == null || message.isBlank()) {
            return;
        }
        if (!settingsService.shouldAlert(businessId, type)) {
            log.info(
                    "Skip ops alert {} business={} reason={}",
                    type,
                    businessId,
                    settingsService.skipReason(businessId, type));
            return;
        }
        BusinessOpsAlertSettings settings = settingsService.resolveForBusiness(businessId);
        String phone = toSmsMsisdn(settings.getPhone());
        if (phone == null || phone.isBlank()) {
            log.info("Skip ops alert {} — blank phone business={}", type, businessId);
            return;
        }

        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(
                businessId, SmsSendReason.OPS_ALERT);
        if (!messaging.secretsReadable()) {
            log.info("Skip ops alert {} — messaging secrets unreadable business={}", type, businessId);
            return;
        }
        if (!messaging.smsConfigured() && !messaging.metaWhatsAppConfigured()) {
            log.info("Skip ops alert {} — no WhatsApp/SMS configured business={}", type, businessId);
            return;
        }

        CustomerMessageDispatcher.DeliveryResult delivery = deliverOpsAlert(messaging, phone, message);
        log.info(
                "ops_alert type={} business={} phone={} channel={} outcome={} detail={}",
                type,
                businessId,
                BusinessOpsAlertSettingsService.maskPhone(phone),
                delivery.channel(),
                delivery.outcome(),
                delivery.detail());
    }

    public OpsAlertTestSendResponse sendTest(String businessId, String rawPhone, String message) {
        BusinessOpsAlertSettings settings = settingsService.resolveForBusiness(businessId);
        String phone = rawPhone != null && !rawPhone.isBlank()
                ? toSmsMsisdn(rawPhone)
                : toSmsMsisdn(settings.getPhone());
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Verify a phone number first, or pass a phone to test");
        }

        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(
                businessId, SmsSendReason.OPS_ALERT);
        if (!messaging.secretsReadable()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    messaging.secretsReadError() != null
                            ? messaging.secretsReadError()
                            : "Messaging credentials are not readable");
        }
        if (!messaging.smsConfigured() && !messaging.metaWhatsAppConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "WhatsApp or SMS must be configured");
        }

        String body = message != null && !message.isBlank()
                ? message.trim()
                : "Palmart ops alert test — if you received this, WhatsApp/SMS alerts are working.";
        CustomerMessageDispatcher.DeliveryResult delivery = deliverOpsAlert(messaging, phone, body);
        if ("failed".equals(delivery.outcome()) || "skipped".equals(delivery.outcome())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    delivery.detail() != null && !delivery.detail().isBlank()
                            ? delivery.detail()
                            : "Ops alert test failed");
        }
        return new OpsAlertTestSendResponse(
                delivery.channel(),
                delivery.outcome(),
                delivery.detail(),
                BusinessOpsAlertSettingsService.maskPhone(phone));
    }

    /**
     * Owner alerts must arrive even when Meta's 24h free-form window is closed.
     *
     * <p>SMS-first when configured (same reliability model as OTP). Free-form WhatsApp alone
     * often returns HTTP 200 outside the session window without the owner ever seeing it —
     * which looked like "shift open/close messages aren't coming" while tests/templates still
     * worked (or failed for a separate Meta-token reason).
     */
    private CustomerMessageDispatcher.DeliveryResult deliverOpsAlert(
            TenantMessagingConfig messaging, String phone, String message) {
        if (messaging.smsConfigured()) {
            return customerMessageDispatcher.deliverBothChannels(messaging, phone, message);
        }
        return customerMessageDispatcher.deliver(messaging, phone, message);
    }

    /** Prefer {@code 2547…} so providers receive {@code +2547…}, not {@code +07…}. */
    private static String toSmsMsisdn(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String msisdn = StkPhoneNormalizer.normalize(raw);
        return msisdn != null ? msisdn : raw.replaceAll("\\D", "");
    }

    public String shopName(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("your shop");
    }

    public String currency(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getCurrency)
                .filter(c -> c != null && !c.isBlank())
                .orElse("KES");
    }

    public String branchName(String businessId, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return "branch";
        }
        return branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .map(Branch::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(branchId.substring(0, Math.min(8, branchId.length())));
    }

    public static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.UK);
        nf.setMinimumFractionDigits(
                scaled.scale() > 0 && scaled.remainder(BigDecimal.ONE).signum() != 0 ? 2 : 0);
        nf.setMaximumFractionDigits(2);
        return currency + " " + nf.format(scaled);
    }
}
