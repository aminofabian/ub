package zelisline.ub.billing.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.billing.domain.PlatformSubscriptionBillingSettings;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.billing.domain.SubscriptionPreExpiryNotification;
import zelisline.ub.billing.domain.SubscriptionPreExpiryNotificationStatus;
import zelisline.ub.billing.repository.SubscriptionPreExpiryNotificationRepository;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Email reminders N days before {@code current_period_end} (SUBSCRIPTION_BILLING_SCOPE.md Phase 4).
 */
@Service
@RequiredArgsConstructor
public class SubscriptionPreExpiryReminderService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPreExpiryReminderService.class);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final String PERMISSION_MANAGE_SUBSCRIPTION = "business.manage_subscription";

    private final SubscriptionBillingSettingsService billingSettingsService;
    private final BusinessRepository businessRepository;
    private final SubscriptionPreExpiryNotificationRepository notificationRepository;
    private final SubscriptionPreExpiryEmailRenderer emailRenderer;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional
    public int processDueReminders(Instant now) {
        if (!billingSettingsService.isBillingEnabled()) {
            return 0;
        }
        PlatformSubscriptionBillingSettings settings = billingSettingsService.loadSingleton();
        int reminderDays = Math.max(1, settings.getPreExpiryReminderDays());
        LocalDate targetDate = now.atZone(NAIROBI).toLocalDate().plusDays(reminderDays);
        Instant windowStart = targetDate.atStartOfDay(NAIROBI).toInstant();
        Instant windowEnd = targetDate.plusDays(1).atStartOfDay(NAIROBI).toInstant();

        List<Business> due = businessRepository.findDueForPreExpiryReminder(windowStart, windowEnd);
        int sent = 0;
        for (Business business : due) {
            try {
                if (sendReminder(business, reminderDays, settings.getRenewalBaseUrl())) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                log.error("Pre-expiry reminder failed business={} error={}", business.getId(), ex.getMessage());
            }
        }
        return sent;
    }

    private boolean sendReminder(Business business, int daysUntilExpiry, String renewalUrl) {
        Instant periodEnd = business.getCurrentPeriodEnd();
        if (periodEnd == null) {
            return false;
        }
        if (notificationRepository.existsByBusinessIdAndPeriodEndAt(business.getId(), periodEnd)) {
            return false;
        }
        PlatformSubscriptionPlan plan = billingSettingsService.planOrNull(business.getSubscriptionTier());
        Set<String> emails = resolveRecipientEmails(business.getId());
        if (emails.isEmpty()) {
            recordNotification(
                    business.getId(),
                    periodEnd,
                    SubscriptionPreExpiryNotificationStatus.SKIPPED,
                    "no recipients");
            return false;
        }
        SubscriptionPreExpiryEmailRenderer.RenderedEmail mail = emailRenderer.render(
                business, plan, periodEnd, daysUntilExpiry, renewalUrl);
        int delivered = 0;
        String lastError = null;
        for (String email : emails) {
            try {
                notificationService.sendNotificationEmail(email, mail.subject(), mail.text(), mail.html());
                delivered++;
            } catch (RuntimeException ex) {
                lastError = ex.getMessage();
                log.warn("Pre-expiry email failed business={} email={}: {}",
                        business.getId(), email, ex.getMessage());
            }
        }
        if (delivered > 0) {
            recordNotification(
                    business.getId(),
                    periodEnd,
                    SubscriptionPreExpiryNotificationStatus.SENT,
                    delivered + " recipient(s)");
            publishAudit(business.getId(), daysUntilExpiry);
            return true;
        }
        recordNotification(
                business.getId(),
                periodEnd,
                SubscriptionPreExpiryNotificationStatus.FAILED,
                lastError);
        return false;
    }

    private void recordNotification(
            String businessId,
            Instant periodEndAt,
            SubscriptionPreExpiryNotificationStatus status,
            String detail
    ) {
        if (notificationRepository.existsByBusinessIdAndPeriodEndAt(businessId, periodEndAt)) {
            return;
        }
        SubscriptionPreExpiryNotification row = new SubscriptionPreExpiryNotification();
        row.setBusinessId(businessId);
        row.setPeriodEndAt(periodEndAt);
        row.setStatus(status);
        row.setDetail(truncate(detail, 500));
        if (status == SubscriptionPreExpiryNotificationStatus.SENT) {
            row.setSentAt(Instant.now());
        }
        notificationRepository.save(row);
    }

    private Set<String> resolveRecipientEmails(String businessId) {
        Set<String> emails = new LinkedHashSet<>();
        for (String userId : userRepository.findIdsWithPermission(
                businessId, PERMISSION_MANAGE_SUBSCRIPTION)) {
            userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                    .map(User::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .ifPresent(e -> emails.add(e.trim().toLowerCase(Locale.ROOT)));
        }
        roleRepository.findSystemRoleByKey("owner").ifPresent(ownerRole -> {
            for (String userId : userRepository.findBuyerUserIdsByBusinessIdAndRoleId(
                    businessId, ownerRole.getId())) {
                userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                        .map(User::getEmail)
                        .filter(e -> e != null && !e.isBlank())
                        .ifPresent(e -> emails.add(e.trim().toLowerCase(Locale.ROOT)));
            }
        });
        return emails;
    }

    private void publishAudit(String businessId, int daysUntilExpiry) {
        try {
            auditEventPublisher.publish(auditEventBuilder.builder(
                            AuditEventCategory.SYSTEM,
                            AuditEventTypes.SUBSCRIPTION_PRE_EXPIRY_SENT,
                            AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(null, AuditEventActorType.SYSTEM)
                    .target("subscription_pre_expiry", businessId)
                    .source("subscription_billing")
                    .metadata("{\"daysUntilExpiry\":" + daysUntilExpiry + "}")
                    .build());
        } catch (RuntimeException ignored) {
            // Audit must never break delivery.
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
