package zelisline.ub.messaging.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.messaging.domain.BusinessSmsCreditAccount;
import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;
import zelisline.ub.messaging.repository.BusinessSmsCreditAccountRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Emails the business owner once per cycle when included SMS usage crosses 80%
 * and again at 100% (SMS_CREDITS_SCOPE.md §17). Dedup via
 * {@link BusinessSmsCreditAccount#getLastDigestPct()}, cleared by the cycle reset.
 */
@Service
@RequiredArgsConstructor
public class SmsCreditDigestService {

    private static final Logger log = LoggerFactory.getLogger(SmsCreditDigestService.class);
    private static final String OWNER_ROLE_KEY = "owner";

    private final BusinessSmsCreditAccountRepository accountRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final SmsCreditSettingsService settingsService;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Transactional
    public int processDueDigests() {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        if (!settings.isEnabled()) {
            return 0;
        }
        List<BusinessSmsCreditAccount> accounts = accountRepository.findAll();
        int emailed = 0;
        for (BusinessSmsCreditAccount account : accounts) {
            try {
                if (processAccount(account)) {
                    emailed++;
                }
            } catch (RuntimeException ex) {
                log.error("SMS credit digest failed business={} error={}",
                        account.getBusinessId(), ex.getMessage());
            }
        }
        return emailed;
    }

    private boolean processAccount(BusinessSmsCreditAccount account) {
        String businessId = account.getBusinessId();
        Integer override = account.getIncludedOverride();
        Integer tierAllowance = override != null ? null
                : (businessRepository.findById(businessId)
                        .map(b -> settingsService.resolveAllowance(b.getSubscriptionTier()))
                        .orElse(null));
        int allowance = override != null ? override : (tierAllowance != null ? tierAllowance : 0);
        int used = account.getIncludedUsed();
        if (allowance <= 0 || used <= 0) {
            return false;
        }
        int pct = (int) Math.round(100.0 * used / allowance);
        Integer last = account.getLastDigestPct();
        int threshold;
        if (pct >= 100) {
            threshold = 100;
        } else if (pct >= 80) {
            threshold = 80;
        } else {
            return false;
        }
        if (last != null && last >= threshold) {
            return false; // already emailed at this level this cycle
        }

        Set<String> emails = resolveOwnerEmails(businessId);
        if (emails.isEmpty()) {
            return false; // no recipient — retry on a later run rather than burn the marker
        }

        Business business = businessRepository.findById(businessId).orElse(null);
        String shopName = business != null && business.getName() != null && !business.getName().isBlank()
                ? business.getName().trim()
                : "your shop";
        String subject = threshold >= 100
                ? "SMS credits used up — " + shopName
                : "SMS credits running low — " + shopName;
        int remaining = Math.max(0, allowance - used);
        String text = """
                Your shop %s has used %d of %d included SMS credits this month (%d%%).

                Remaining included: %d
                Purchased balance: %d

                %s

                Buy more credits from the SMS chip in your dashboard header, or contact support.
                """
                .formatted(shopName, used, allowance, pct, remaining,
                        account.getPurchasedBalance(),
                        threshold >= 100
                                ? "Sending will stop until you top up."
                                : "Top up soon so SMS keeps working.");
        String html = """
                <div style="font-family:-apple-system,'Segoe UI',Roboto,sans-serif;max-width:520px;margin:0 auto;padding:24px">
                  <h2 style="margin:0 0 8px">SMS credits %s</h2>
                  <p style="color:#334155">%s</p>
                  <table style="border-collapse:collapse;font-size:14px;color:#334155">
                    <tr><td style="padding:4px 12px 4px 0">Included used</td>
                        <td style="font-weight:600">%d / %d (%d%%)</td></tr>
                    <tr><td style="padding:4px 12px 4px 0">Included remaining</td>
                        <td style="font-weight:600">%d</td></tr>
                    <tr><td style="padding:4px 12px 4px 0">Purchased balance</td>
                        <td style="font-weight:600">%d</td></tr>
                  </table>
                  <p style="margin-top:16px;color:#64748b;font-size:13px">
                    Buy more credits from the SMS chip in your dashboard header.
                  </p>
                </div>
                """
                .formatted(
                        threshold >= 100 ? "used up" : "running low",
                        text,
                        used, allowance, pct, remaining,
                        account.getPurchasedBalance());

        int delivered = 0;
        for (String email : emails) {
            try {
                notificationService.sendNotificationEmail(email, subject, text, html);
                delivered++;
            } catch (RuntimeException ex) {
                log.warn("SMS credit digest email failed business={} email={}: {}",
                        businessId, email, ex.getMessage());
            }
        }
        if (delivered == 0) {
            return false;
        }
        account.setLastDigestPct(threshold);
        accountRepository.save(account);
        log.info("SMS credit digest sent business={} pct={} threshold={} recipients={}",
                businessId, pct, threshold, delivered);
        return true;
    }

    private Set<String> resolveOwnerEmails(String businessId) {
        Set<String> emails = new LinkedHashSet<>();
        for (User user : userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(businessId, OWNER_ROLE_KEY)) {
            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                emails.add(user.getEmail().trim().toLowerCase(Locale.ROOT));
            }
        }
        return emails;
    }
}
