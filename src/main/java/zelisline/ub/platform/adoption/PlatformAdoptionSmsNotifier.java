package zelisline.ub.platform.adoption;

import java.util.List;
import java.util.Locale;

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

    public void notifyDomainPurchased(
            String businessId,
            String businessName,
            String fqdn,
            String payerPhone,
            Long priceCents,
            boolean paymentCollected
    ) {
        String shop = nonBlank(businessName) ? businessName.trim() : "A tenant";
        String domain = nonBlank(fqdn) ? fqdn.trim() : "a custom domain";
        String amount = formatKes(priceCents);
        String phone = StkPhoneNormalizer.normalize(payerPhone);
        if (paymentCollected) {
            sendToAdmins("Domain paid — " + shop + "\n"
                    + domain + " · " + amount
                    + (phone == null ? "" : " from " + phone)
                    + ". Registration started. Check Platform → Domains if it stalls.");
            if (phone != null) {
                sendToPhone(phone, "Kiosk: M-Pesa received " + amount + " for " + domain
                        + ". We're registering the name and connecting your shop. "
                        + "You'll get a text when customers can open it.");
            }
            return;
        }
        sendToAdmins("Domain order (test mode, no M-Pesa) — " + shop + "\n"
                + domain + ". Registration started.");
    }

    public void notifyDomainLive(String businessName, String fqdn, String payerPhone) {
        String shop = nonBlank(businessName) ? businessName.trim() : "A tenant";
        String domain = nonBlank(fqdn) ? fqdn.trim() : "the domain";
        sendToAdmins("Domain live — " + shop + "\n" + domain + " is serving the shop.");
        String phone = StkPhoneNormalizer.normalize(payerPhone);
        if (phone != null) {
            sendToPhone(phone, "Kiosk: " + domain + " is live. Customers can open your shop there.");
        }
    }

    public void notifyDomainHelpRequested(
            String businessName,
            String kindLabel,
            String phone,
            String domain,
            String note,
            Long feeCents
    ) {
        String shop = nonBlank(businessName) ? businessName.trim() : "A tenant";
        String job = nonBlank(kindLabel) ? kindLabel.trim() : "Help with the shop";
        String digits = StkPhoneNormalizer.normalize(phone);
        String fee = feeCents != null && feeCents > 0 ? formatKes(feeCents) : null;
        StringBuilder admin = new StringBuilder();
        admin.append("Hire a developer — ").append(shop).append('\n');
        admin.append(job);
        if (fee != null) {
            admin.append(" · ").append(fee);
        }
        if (digits != null) {
            admin.append(". Call ").append(digits);
        }
        if (nonBlank(domain)) {
            admin.append(". Domain ").append(domain.trim());
        }
        if (nonBlank(note)) {
            admin.append(". Note: ").append(trim(note, 120));
        }
        sendToAdmins(admin.toString());
        if (digits != null) {
            if (fee != null) {
                sendToPhone(digits, "Kiosk: we got your request — " + job + ", " + fee
                        + ". A developer will call this number and start once that fee is confirmed.");
            } else {
                sendToPhone(digits, "Kiosk: we got your request — " + job
                        + ". A developer will call this number. You don't need to change DNS.");
            }
        }
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
                deliver(messaging, digits, message, "admin=" + admin.getId());
            }
            if (!any) {
                log.info("Adoption SMS skipped — no active super admin has a phone number set");
            }
        } catch (Exception ex) {
            log.warn("Adoption SMS failed: {}", ex.getMessage());
        }
    }

    private void sendToPhone(String digits, String message) {
        try {
            TenantMessagingConfig messaging = messagingSettingsService.resolvePlatformForContactReply();
            if (!messaging.smsConfigured()) {
                log.info("Adoption SMS skipped — platform SMS credentials are not configured");
                return;
            }
            deliver(messaging, digits, message, "merchant");
        } catch (Exception ex) {
            log.warn("Adoption SMS to merchant failed: {}", ex.getMessage());
        }
    }

    private void deliver(TenantMessagingConfig messaging, String digits, String message, String who) {
        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliverSmsOnly(messaging, digits, message);
        log.info(
                "adoption_sms who={} to={} channel={} outcome={} detail={}",
                who,
                mask(digits),
                delivery.channel(),
                delivery.outcome(),
                delivery.detail());
    }

    static String formatKes(Long cents) {
        long major = cents == null ? 2_000L : cents / 100;
        return "KES " + String.format(Locale.US, "%,d", major);
    }

    private static String trim(String value, int max) {
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
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
