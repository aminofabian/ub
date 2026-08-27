package zelisline.ub.opsalerts.application;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.opsalerts.api.dto.OpsAlertSettingsResponse;
import zelisline.ub.opsalerts.api.dto.UpdateOpsAlertSettingsRequest;
import zelisline.ub.opsalerts.domain.BusinessOpsAlertSettings;
import zelisline.ub.opsalerts.domain.OpsAlertType;
import zelisline.ub.opsalerts.repository.BusinessOpsAlertSettingsRepository;

@Service
@RequiredArgsConstructor
public class BusinessOpsAlertSettingsService {

    private final BusinessOpsAlertSettingsRepository settingsRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;

    @Transactional
    public OpsAlertSettingsResponse getForAdmin(String businessId) {
        BusinessOpsAlertSettings s = resolveForBusiness(businessId);
        return toResponse(s);
    }

    @Transactional
    public OpsAlertSettingsResponse update(String businessId, UpdateOpsAlertSettingsRequest req) {
        BusinessOpsAlertSettings s = resolveForBusiness(businessId);
        if (Boolean.TRUE.equals(req.enabled()) && !s.hasVerifiedPhone()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Verify a WhatsApp number before enabling alerts");
        }
        s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        s.setAlertWebOrder(Boolean.TRUE.equals(req.alertWebOrder()));
        s.setAlertShift(Boolean.TRUE.equals(req.alertShift()));
        s.setAlertSupply(Boolean.TRUE.equals(req.alertSupply()));
        s.setAlertCreditPayment(Boolean.TRUE.equals(req.alertCreditPayment()));
        s.setAlertRestockDigest(Boolean.TRUE.equals(req.alertRestockDigest()));
        return toResponse(settingsRepository.save(s));
    }

    @Transactional
    public OpsAlertSettingsResponse clearPhone(String businessId) {
        BusinessOpsAlertSettings s = resolveForBusiness(businessId);
        s.setPhone(null);
        s.setPhoneVerifiedAt(null);
        s.setEnabled(false);
        return toResponse(settingsRepository.save(s));
    }

    @Transactional
    public BusinessOpsAlertSettings assignVerifiedPhone(String businessId, String phone) {
        BusinessOpsAlertSettings s = resolveForBusiness(businessId);
        s.setPhone(phone);
        s.setPhoneVerifiedAt(Instant.now());
        // Verifying a number means the owner wants alerts — turn the master switch on.
        s.setEnabled(true);
        return settingsRepository.save(s);
    }

    @Transactional
    public BusinessOpsAlertSettings resolveForBusiness(String businessId) {
        return settingsRepository.findById(businessId).orElseGet(() -> insertDefaults(businessId));
    }

    /**
     * Whether this business should receive an alert of the given type right now.
     */
    @Transactional(readOnly = true)
    public boolean shouldAlert(String businessId, OpsAlertType type) {
        BusinessOpsAlertSettings s = settingsRepository.findById(businessId).orElse(null);
        if (s == null) {
            return false;
        }
        if (!s.hasVerifiedPhone()) {
            return false;
        }
        if (!s.isEnabled()) {
            return false;
        }
        return switch (type) {
            case WEB_ORDER -> s.isAlertWebOrder();
            case SHIFT_OPENED, SHIFT_CLOSED -> s.isAlertShift();
            case SUPPLY_POSTED -> s.isAlertSupply();
            case CREDIT_PAYMENT -> s.isAlertCreditPayment();
            case RESTOCK_DIGEST -> s.isAlertRestockDigest();
            case ONBOARDING -> true; // master switch + verified phone only
        };
    }

    /** Diagnostic reason when {@link #shouldAlert} is false (for logs). */
    @Transactional(readOnly = true)
    public String skipReason(String businessId, OpsAlertType type) {
        BusinessOpsAlertSettings s = settingsRepository.findById(businessId).orElse(null);
        if (s == null) {
            return "no_settings_row";
        }
        if (!s.hasVerifiedPhone()) {
            return "phone_not_verified";
        }
        if (!s.isEnabled()) {
            return "alerts_disabled";
        }
        boolean typeOn = switch (type) {
            case WEB_ORDER -> s.isAlertWebOrder();
            case SHIFT_OPENED, SHIFT_CLOSED -> s.isAlertShift();
            case SUPPLY_POSTED -> s.isAlertSupply();
            case CREDIT_PAYMENT -> s.isAlertCreditPayment();
            case RESTOCK_DIGEST -> s.isAlertRestockDigest();
            case ONBOARDING -> true;
        };
        if (!typeOn) {
            return "event_type_disabled:" + type;
        }
        return null;
    }

    private BusinessOpsAlertSettings insertDefaults(String businessId) {
        BusinessOpsAlertSettings s = new BusinessOpsAlertSettings();
        s.setBusinessId(businessId);
        s.setEnabled(false);
        s.setAlertWebOrder(true);
        s.setAlertShift(true);
        s.setAlertSupply(true);
        s.setAlertCreditPayment(true);
        s.setAlertRestockDigest(true);
        try {
            return settingsRepository.save(s);
        } catch (DataIntegrityViolationException e) {
            return settingsRepository.findById(businessId).orElseThrow(() -> e);
        }
    }

    private OpsAlertSettingsResponse toResponse(BusinessOpsAlertSettings s) {
        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(s.getBusinessId());
        boolean messagingReady = messaging.secretsReadable()
                && (messaging.metaWhatsAppConfigured() || messaging.smsConfigured());
        String phone = s.getPhone();
        return new OpsAlertSettingsResponse(
                s.isEnabled(),
                phone,
                maskPhone(phone),
                s.hasVerifiedPhone(),
                s.getPhoneVerifiedAt(),
                s.isAlertWebOrder(),
                s.isAlertShift(),
                s.isAlertSupply(),
                s.isAlertCreditPayment(),
                s.isAlertRestockDigest(),
                messagingReady);
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        if (phone.length() <= 4) {
            return "****";
        }
        return "*".repeat(Math.min(phone.length() - 4, 8)) + phone.substring(phone.length() - 4);
    }
}
