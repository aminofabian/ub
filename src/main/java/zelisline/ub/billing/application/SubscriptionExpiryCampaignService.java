package zelisline.ub.billing.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
import zelisline.ub.billing.domain.SubscriptionExpiryCampaign;
import zelisline.ub.billing.domain.SubscriptionExpiryCampaignDelivery;
import zelisline.ub.billing.domain.SubscriptionExpiryCampaignStatus;
import zelisline.ub.billing.domain.SubscriptionExpiryDeliveryChannel;
import zelisline.ub.billing.domain.SubscriptionExpiryDeliveryStatus;
import zelisline.ub.billing.repository.SubscriptionExpiryCampaignDeliveryRepository;
import zelisline.ub.billing.repository.SubscriptionExpiryCampaignRepository;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;
import zelisline.ub.opsalerts.application.BusinessOpsAlertSettingsService;
import zelisline.ub.opsalerts.domain.BusinessOpsAlertSettings;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Spaced SMS/email touches during subscription grace (SUBSCRIPTION_BILLING_SCOPE.md §7).
 */
@Service
@RequiredArgsConstructor
public class SubscriptionExpiryCampaignService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryCampaignService.class);
    private static final String PERMISSION_MANAGE_SUBSCRIPTION = "business.manage_subscription";
    private static final List<Integer> DEFAULT_CADENCE = List.of(0, 2, 5, 8, 11, 13, 14, 15);

    private final SubscriptionExpiryCampaignRepository campaignRepository;
    private final SubscriptionExpiryCampaignDeliveryRepository deliveryRepository;
    private final SubscriptionBillingSettingsService billingSettingsService;
    private final BusinessRepository businessRepository;
    private final BusinessOpsAlertSettingsService opsAlertSettingsService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final SmsMessagingClient smsMessagingClient;
    private final SubscriptionExpiryEmailRenderer emailRenderer;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /** Start a grace episode campaign and send Day 0 SMS immediately. */
    @Transactional
    public void startCampaign(String businessId, Instant graceStartedAt) {
        if (!billingSettingsService.isBillingEnabled()) {
            return;
        }
        if (campaignRepository.findFirstByBusinessIdAndStatusOrderByCreatedAtDesc(
                businessId, SubscriptionExpiryCampaignStatus.ACTIVE).isPresent()) {
            return;
        }
        SubscriptionExpiryCampaign campaign = new SubscriptionExpiryCampaign();
        campaign.setBusinessId(businessId);
        campaign.setGraceStartedAt(graceStartedAt);
        campaign.setStatus(SubscriptionExpiryCampaignStatus.ACTIVE);
        SubscriptionExpiryCampaign saved = campaignRepository.save(campaign);
        sendStep(saved, 0);
    }

    @Transactional
    public void cancelActiveCampaigns(String businessId) {
        Instant now = Instant.now();
        for (SubscriptionExpiryCampaign row : campaignRepository.findByBusinessIdAndStatus(
                businessId, SubscriptionExpiryCampaignStatus.ACTIVE)) {
            row.setStatus(SubscriptionExpiryCampaignStatus.CANCELLED);
            row.setCancelledAt(now);
            campaignRepository.save(row);
        }
    }

    /** Daily job: send due cadence steps for all active campaigns. */
    @Transactional
    public int processDueCampaigns(Instant now) {
        if (!billingSettingsService.isBillingEnabled()) {
            return 0;
        }
        List<Integer> cadence = resolveCadenceDays();
        int sent = 0;
        for (SubscriptionExpiryCampaign campaign : campaignRepository.findByStatus(
                SubscriptionExpiryCampaignStatus.ACTIVE)) {
            int graceDay = graceDay(campaign.getGraceStartedAt(), now);
            for (int stepDay : cadence) {
                if (stepDay > graceDay || stepDay == 0) {
                    continue;
                }
                if (sendStep(campaign, stepDay)) {
                    sent++;
                }
            }
            if (graceDay >= cadence.stream().mapToInt(Integer::intValue).max().orElse(15)) {
                campaign.setStatus(SubscriptionExpiryCampaignStatus.COMPLETED);
                campaignRepository.save(campaign);
            }
        }
        return sent;
    }

    private boolean sendStep(SubscriptionExpiryCampaign campaign, int stepDay) {
        boolean anySent = false;
        if (stepDay == 0 || stepDay >= 15) {
            if (sendSms(campaign, stepDay)) {
                anySent = true;
            }
        }
        if (stepDay == 0) {
            return anySent;
        }
        if (sendEmail(campaign, stepDay)) {
            anySent = true;
        }
        if (anySent && stepDay > campaign.getLastStepDay()) {
            campaign.setLastStepDay(stepDay);
            campaignRepository.save(campaign);
        }
        return anySent;
    }

    private boolean sendSms(SubscriptionExpiryCampaign campaign, int stepDay) {
        if (deliveryRepository.existsByCampaignIdAndStepDayAndChannel(
                campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.SMS)) {
            return false;
        }
        Business business = businessRepository.findByIdAndDeletedAtIsNull(campaign.getBusinessId()).orElse(null);
        if (business == null) {
            recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.SMS,
                    SubscriptionExpiryDeliveryStatus.SKIPPED, "business missing");
            return false;
        }
        String phone = resolveAlertPhone(campaign.getBusinessId());
        if (phone == null) {
            recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.SMS,
                    SubscriptionExpiryDeliveryStatus.SKIPPED, "no alert phone");
            return false;
        }
        TenantMessagingConfig messaging = messagingSettingsService.resolvePlatformForContactReply();
        if (!messaging.smsConfigured()) {
            recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.SMS,
                    SubscriptionExpiryDeliveryStatus.SKIPPED, "SMS not configured");
            return false;
        }
        PlatformSubscriptionBillingSettings settings = billingSettingsService.loadSingleton();
        PlatformSubscriptionPlan plan = billingSettingsService.planOrNull(business.getSubscriptionTier());
        int graceDays = billingSettingsService.resolveGraceDays(plan);
        String renewalUrl = settings.getRenewalBaseUrl();
        String body = stepDay >= 15
                ? emailRenderer.renderDayFifteenSms(renewalUrl)
                : emailRenderer.renderDayZeroSms(
                        business.getName() != null ? business.getName() : "Your shop",
                        graceDays,
                        renewalUrl);
        try {
            SmsMessagingClient.SendResult result = smsMessagingClient.sendText(
                    messaging, phone, body, SmsSendReason.OPS_ALERT, "subscription-expiry:" + campaign.getId());
            if (result.sent()) {
                recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.SMS,
                        SubscriptionExpiryDeliveryStatus.SENT, null);
                publishCampaignAudit(campaign.getBusinessId(), stepDay, "sms");
                return true;
            }
            recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.SMS,
                    SubscriptionExpiryDeliveryStatus.FAILED, result.detail());
        } catch (RuntimeException ex) {
            recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.SMS,
                    SubscriptionExpiryDeliveryStatus.FAILED, ex.getMessage());
        }
        return false;
    }

    private boolean sendEmail(SubscriptionExpiryCampaign campaign, int stepDay) {
        if (deliveryRepository.existsByCampaignIdAndStepDayAndChannel(
                campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.EMAIL)) {
            return false;
        }
        Business business = businessRepository.findByIdAndDeletedAtIsNull(campaign.getBusinessId()).orElse(null);
        if (business == null) {
            recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.EMAIL,
                    SubscriptionExpiryDeliveryStatus.SKIPPED, "business missing");
            return false;
        }
        Set<String> emails = resolveRecipientEmails(campaign.getBusinessId());
        if (emails.isEmpty()) {
            recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.EMAIL,
                    SubscriptionExpiryDeliveryStatus.SKIPPED, "no recipients");
            return false;
        }
        PlatformSubscriptionBillingSettings settings = billingSettingsService.loadSingleton();
        PlatformSubscriptionPlan plan = billingSettingsService.planOrNull(business.getSubscriptionTier());
        int graceDays = billingSettingsService.resolveGraceDays(plan);
        SubscriptionExpiryEmailRenderer.RenderedEmail mail = emailRenderer.render(
                business,
                plan,
                stepDay,
                graceDays,
                campaign.getGraceStartedAt(),
                business.getGraceEndsAt(),
                settings.getRenewalBaseUrl());
        int sent = 0;
        String lastError = null;
        for (String email : emails) {
            try {
                notificationService.sendNotificationEmail(email, mail.subject(), mail.text(), mail.html());
                sent++;
            } catch (RuntimeException ex) {
                lastError = ex.getMessage();
                log.warn("Subscription expiry email failed business={} email={}: {}",
                        campaign.getBusinessId(), email, ex.getMessage());
            }
        }
        if (sent > 0) {
            recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.EMAIL,
                    SubscriptionExpiryDeliveryStatus.SENT, sent + " recipient(s)");
            publishCampaignAudit(campaign.getBusinessId(), stepDay, "email");
            return true;
        }
        recordDelivery(campaign.getId(), stepDay, SubscriptionExpiryDeliveryChannel.EMAIL,
                SubscriptionExpiryDeliveryStatus.FAILED, lastError);
        return false;
    }

    private void recordDelivery(
            String campaignId,
            int stepDay,
            SubscriptionExpiryDeliveryChannel channel,
            SubscriptionExpiryDeliveryStatus status,
            String detail
    ) {
        if (deliveryRepository.existsByCampaignIdAndStepDayAndChannel(campaignId, stepDay, channel)) {
            return;
        }
        SubscriptionExpiryCampaignDelivery row = new SubscriptionExpiryCampaignDelivery();
        row.setCampaignId(campaignId);
        row.setStepDay(stepDay);
        row.setChannel(channel);
        row.setStatus(status);
        row.setDetail(truncate(detail, 500));
        if (status == SubscriptionExpiryDeliveryStatus.SENT) {
            row.setSentAt(Instant.now());
        }
        deliveryRepository.save(row);
    }

    private String resolveAlertPhone(String businessId) {
        BusinessOpsAlertSettings settings = opsAlertSettingsService.resolveForBusiness(businessId);
        if (settings.hasVerifiedPhone()) {
            return settings.getPhone();
        }
        return roleRepository.findSystemRoleByKey("owner")
                .map(Role::getId)
                .map(ownerRoleId -> userRepository.findBuyerUserIdsByBusinessIdAndRoleId(businessId, ownerRoleId))
                .orElse(List.of())
                .stream()
                .findFirst()
                .flatMap(userId -> userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId))
                .map(User::getPhone)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
    }

    private Set<String> resolveRecipientEmails(String businessId) {
        Set<String> emails = new LinkedHashSet<>();
        List<String> permitted = userRepository.findIdsWithPermission(
                businessId, PERMISSION_MANAGE_SUBSCRIPTION);
        for (String userId : permitted) {
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

    private List<Integer> resolveCadenceDays() {
        String raw = billingSettingsService.loadSingleton().getNotificationCadenceDays();
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CADENCE;
        }
        try {
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(Integer::parseInt)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (NumberFormatException ex) {
            return DEFAULT_CADENCE;
        }
    }

    private static int graceDay(Instant graceStartedAt, Instant now) {
        if (graceStartedAt == null) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(
                graceStartedAt.truncatedTo(ChronoUnit.DAYS),
                now.truncatedTo(ChronoUnit.DAYS));
    }

    private void publishCampaignAudit(String businessId, int stepDay, String channel) {
        try {
            auditEventPublisher.publish(auditEventBuilder.builder(
                            AuditEventCategory.SYSTEM,
                            AuditEventTypes.SUBSCRIPTION_CAMPAIGN_SENT,
                            AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(null, AuditEventActorType.SYSTEM)
                    .target("subscription_expiry", businessId)
                    .source("subscription_billing")
                    .metadata("{\"stepDay\":" + stepDay + ",\"channel\":\"" + channel + "\"}")
                    .build());
        } catch (RuntimeException ignored) {
            // Audit must never break campaign delivery.
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
