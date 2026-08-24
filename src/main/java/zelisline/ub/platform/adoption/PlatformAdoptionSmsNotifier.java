package zelisline.ub.platform.adoption;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.payments.application.StkPhoneNormalizer;

/**
 * Platform-ops SMS alerts for paid tenant adoptions (Kiosk Pay activation,
 * custom-domain purchase). Uses the platform-level SMS credentials and sends to
 * every active super admin that has set a phone number in their profile.
 */
@Service
@RequiredArgsConstructor
public class PlatformAdoptionSmsNotifier {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdoptionSmsNotifier.class);

    private final SuperAdminRepository superAdminRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    public void notifyKioskPayActivated(String businessId, String businessName) {
        String shop = nonBlank(businessName) ? businessName.trim() : "A tenant";
        sendToAdmins("Kiosk Pay activated — " + shop + "\n"
                + "Tenant " + businessId + " turned on Kiosk Pay. "
                + "Check in on them from the super-admin console.");
    }

    public void notifyDomainPurchased(String businessId, String businessName, String fqdn) {
        String shop = nonBlank(businessName) ? businessName.trim() : "A tenant";
        String domain = nonBlank(fqdn) ? fqdn.trim() : "a custom domain";
        sendToAdmins("Custom domain purchased — " + shop + "\n"
                + domain + " (tenant " + businessId + "). "
                + "Provision it and follow up.");
    }

    private void sendToAdmins(String message) {
        try {
            TenantMessagingConfig messaging = messagingSettingsService.resolvePlatformForContactReply();
            if (!messaging.smsConfigured()) {
                log.info("Adoption SMS skipped — platform SMS credentials are not configured");
                return;
            }
            List<SuperAdmin> admins = superAdminRepository.findByActiveTrue();
            boolean any = false;
            for (SuperAdmin admin : admins) {
                String digits = StkPhoneNormalizer.normalize(admin.getPhone());
                if (digits == null) {
                    continue;
                }
                any = true;
                CustomerMessageDispatcher.DeliveryResult delivery =
                        customerMessageDispatcher.deliverSmsOnly(messaging, digits, message);
                log.info(
                        "adoption_sms admin={} to={} channel={} outcome={} detail={}",
                        admin.getId(),
                        mask(admin.getPhone()),
                        delivery.channel(),
                        delivery.outcome(),
                        delivery.detail());
            }
            if (!any) {
                log.info("Adoption SMS skipped — no active super admin has a phone number set");
            }
        } catch (Exception ex) {
            log.warn("Adoption SMS failed: {}", ex.getMessage());
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() <= 6) {
            return "***";
        }
        return phone.substring(0, 4) + "…" + phone.substring(phone.length() - 2);
    }
}
