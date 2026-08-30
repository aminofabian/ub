package zelisline.ub.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.billing.domain.SubscriptionRenewalOrder;
import zelisline.ub.billing.domain.SuspensionReason;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Subscription billing lifecycle — ACTIVE → GRACE → SUSPENDED and renewal
 * (SUBSCRIPTION_BILLING_SCOPE.md §3).
 */
@Service
@RequiredArgsConstructor
public class SubscriptionBillingService {

    private final BusinessRepository businessRepository;
    private final SubscriptionBillingSettingsService settingsService;
    private final UserSessionRepository userSessionRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final org.springframework.beans.factory.ObjectProvider<SubscriptionExpiryCampaignService> expiryCampaignService;
    private final org.springframework.beans.factory.ObjectProvider<SubscriptionPlanFitService> planFitService;

    @Transactional(readOnly = true)
    public SubscriptionBillingDtos.BillingStatusResponse getBillingStatusView(String businessId) {
        Business business = requireBusiness(businessId);
        PlatformSubscriptionPlan plan = settingsService.planOrNull(business.getSubscriptionTier());
        return buildStatusView(business, plan);
    }

    @Transactional(readOnly = true)
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot adminSnapshot(String businessId) {
        Business business = requireBusiness(businessId);
        PlatformSubscriptionPlan plan = settingsService.planOrNull(business.getSubscriptionTier());
        return new SubscriptionBillingDtos.AdminSubscriptionSnapshot(
                business.getId(),
                business.getSubscriptionTier(),
                plan != null ? plan.getDisplayName() : business.getSubscriptionTier(),
                business.getSubscriptionBillingStatus(),
                business.getCurrentPeriodEnd(),
                business.getGraceStartedAt(),
                business.getGraceEndsAt(),
                business.getBillingSuspendedAt(),
                business.getSuspensionReason() != null ? business.getSuspensionReason().name() : null,
                amountDue(plan));
    }

    /**
     * Hourly job: ACTIVE tenants past period end enter grace. Skipped when billing
     * is disabled or tier is free.
     */
    @Transactional
    public boolean enterGraceIfDue(Business business, Instant now) {
        if (!settingsService.isBillingEnabled()) {
            return false;
        }
        if (business.getDeletedAt() != null) {
            return false;
        }
        if (business.getSubscriptionBillingStatus() != SubscriptionBillingStatus.ACTIVE) {
            return false;
        }
        if (isFreeTier(business.getSubscriptionTier())) {
            return false;
        }
        if (business.getCurrentPeriodEnd() == null || now.isBefore(business.getCurrentPeriodEnd())) {
            return false;
        }

        PlatformSubscriptionPlan plan = settingsService.planOrNull(business.getSubscriptionTier());
        int graceDays = settingsService.resolveGraceDays(plan);
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.GRACE);
        business.setGraceStartedAt(now);
        business.setGraceEndsAt(now.plus(graceDays, ChronoUnit.DAYS));
        businessRepository.save(business);
        publishAudit(business.getId(), AuditEventTypes.SUBSCRIPTION_ENTERED_GRACE, null);
        expiryCampaignService.ifAvailable(c -> c.startCampaign(business.getId(), business.getGraceStartedAt()));
        return true;
    }

    /**
     * Daily job: GRACE tenants past grace end are suspended and logged out.
     */
    @Transactional
    public boolean suspendIfGraceEnded(Business business, Instant now) {
        if (!settingsService.isBillingEnabled()) {
            return false;
        }
        if (business.getDeletedAt() != null) {
            return false;
        }
        if (business.getSubscriptionBillingStatus() != SubscriptionBillingStatus.GRACE) {
            return false;
        }
        if (business.getGraceEndsAt() == null || now.isBefore(business.getGraceEndsAt())) {
            return false;
        }

        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.SUSPENDED);
        business.setBillingSuspendedAt(now);
        business.setSuspensionReason(SuspensionReason.BILLING_UNPAID);
        business.setTenantStatus(TenantStatus.SUSPENDED);
        businessRepository.save(business);
        userSessionRepository.revokeAllActiveForBusiness(business.getId(), now);
        publishAudit(business.getId(), AuditEventTypes.SUBSCRIPTION_SUSPENDED, null);
        return true;
    }

    /**
     * SA manual extension — clears grace and pushes period end forward.
     */
    @Transactional
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot extendPeriod(
            String businessId,
            int months,
            String note,
            String actorUserId
    ) {
        if (months < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "months must be at least 1");
        }
        Business business = requireBusiness(businessId);
        Instant now = Instant.now();
        Instant base = business.getCurrentPeriodEnd() != null && business.getCurrentPeriodEnd().isAfter(now)
                ? business.getCurrentPeriodEnd()
                : now;
        business.setCurrentPeriodEnd(base.plus(months * 30L, ChronoUnit.DAYS));
        clearGraceFields(business);
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.ACTIVE);
        if (business.getSuspensionReason() == SuspensionReason.BILLING_UNPAID) {
            business.setSuspensionReason(null);
            business.setBillingSuspendedAt(null);
            business.setTenantStatus(TenantStatus.ACTIVE);
        }
        businessRepository.save(business);
        cancelExpiryCampaigns(businessId);
        publishAudit(business.getId(), AuditEventTypes.SUBSCRIPTION_EXTENDED, actorUserId);
        return adminSnapshot(businessId);
    }

    /**
     * SA: push the lock date by {@code days}. Unsuspends billing-locked shops
     * into GRACE. Paid periods that have not ended are left alone — use
     * {@link #extendPeriod} for those.
     */
    @Transactional
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot extendGrace(
            String businessId,
            int days,
            String note,
            String actorUserId
    ) {
        if (days < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be at least 1");
        }
        Business business = requireBusiness(businessId);
        Instant now = Instant.now();
        Instant periodEnd = business.getCurrentPeriodEnd();
        boolean periodStillRunning = periodEnd != null && periodEnd.isAfter(now);
        if (business.getSubscriptionBillingStatus() == SubscriptionBillingStatus.ACTIVE
                && periodStillRunning) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Paid period has not ended. Extend paid months instead.");
        }

        Instant base = business.getGraceEndsAt() != null && business.getGraceEndsAt().isAfter(now)
                ? business.getGraceEndsAt()
                : now;
        if (business.getGraceStartedAt() == null) {
            business.setGraceStartedAt(now);
        }
        business.setGraceEndsAt(base.plus(days, ChronoUnit.DAYS));
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.GRACE);
        if (business.getSuspensionReason() == SuspensionReason.BILLING_UNPAID) {
            business.setSuspensionReason(null);
            business.setBillingSuspendedAt(null);
            business.setTenantStatus(TenantStatus.ACTIVE);
        } else if (business.getTenantStatus() == TenantStatus.SUSPENDED) {
            business.setTenantStatus(TenantStatus.ACTIVE);
        }
        businessRepository.save(business);
        publishAudit(business.getId(), AuditEventTypes.SUBSCRIPTION_GRACE_EXTENDED, actorUserId);
        return adminSnapshot(businessId);
    }

    @Transactional
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot assignPlan(
            String businessId,
            String tierCode,
            String note,
            String actorUserId
    ) {
        if (tierCode == null || tierCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tierCode is required");
        }
        String code = tierCode.trim().toLowerCase();
        if (!isFreeTierStatic(code) && settingsService.planOrNull(code) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown subscription plan");
        }
        Business business = requireBusiness(businessId);
        business.setSubscriptionTier(code);
        businessRepository.save(business);
        publishAudit(business.getId(), AuditEventTypes.SUBSCRIPTION_PLAN_ASSIGNED, actorUserId);
        return adminSnapshot(businessId);
    }

    /**
     * SA full override: plan, billing (payment) status, and dates.
     */
    @Transactional
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot override(
            String businessId,
            SubscriptionBillingDtos.OverrideSubscriptionRequest body,
            String actorUserId
    ) {
        Business business = requireBusiness(businessId);
        Instant now = Instant.now();

        if (body.tierCode() != null && !body.tierCode().isBlank()) {
            String code = body.tierCode().trim().toLowerCase();
            if (!isFreeTierStatic(code) && settingsService.planOrNull(code) == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown subscription plan");
            }
            business.setSubscriptionTier(code);
        }

        if (body.billingStatus() != null
                && body.billingStatus() != business.getSubscriptionBillingStatus()) {
            applyBillingStatus(business, body.billingStatus(), now);
        }

        if (body.currentPeriodEnd() != null) {
            business.setCurrentPeriodEnd(body.currentPeriodEnd());
        }
        if (body.billingStatus() == SubscriptionBillingStatus.ACTIVE) {
            clearGraceFields(business);
        } else if (body.graceEndsAt() != null) {
            business.setGraceEndsAt(body.graceEndsAt());
            if (business.getGraceStartedAt() == null) {
                business.setGraceStartedAt(now);
            }
        }

        businessRepository.save(business);
        if (body.billingStatus() == SubscriptionBillingStatus.ACTIVE) {
            cancelExpiryCampaigns(businessId);
        }
        publishAudit(business.getId(), AuditEventTypes.SUBSCRIPTION_OVERRIDDEN, actorUserId);
        return adminSnapshot(businessId);
    }

    private void applyBillingStatus(Business business, SubscriptionBillingStatus status, Instant now) {
        switch (status) {
            case ACTIVE -> {
                clearGraceFields(business);
                business.setSubscriptionBillingStatus(SubscriptionBillingStatus.ACTIVE);
                business.setSuspensionReason(null);
                business.setBillingSuspendedAt(null);
                business.setTenantStatus(TenantStatus.ACTIVE);
                if (business.getCurrentPeriodEnd() == null || !business.getCurrentPeriodEnd().isAfter(now)) {
                    business.setCurrentPeriodEnd(now.plus(30, ChronoUnit.DAYS));
                }
            }
            case GRACE -> {
                business.setSubscriptionBillingStatus(SubscriptionBillingStatus.GRACE);
                if (business.getGraceStartedAt() == null) {
                    business.setGraceStartedAt(now);
                }
                if (business.getGraceEndsAt() == null || !business.getGraceEndsAt().isAfter(now)) {
                    PlatformSubscriptionPlan plan = settingsService.planOrNull(business.getSubscriptionTier());
                    int graceDays = settingsService.resolveGraceDays(plan);
                    business.setGraceEndsAt(now.plus(graceDays, ChronoUnit.DAYS));
                }
                if (business.getSuspensionReason() == SuspensionReason.BILLING_UNPAID) {
                    business.setSuspensionReason(null);
                    business.setBillingSuspendedAt(null);
                }
                business.setTenantStatus(TenantStatus.ACTIVE);
            }
            case SUSPENDED -> {
                business.setSubscriptionBillingStatus(SubscriptionBillingStatus.SUSPENDED);
                business.setBillingSuspendedAt(now);
                business.setSuspensionReason(SuspensionReason.BILLING_UNPAID);
                business.setTenantStatus(TenantStatus.SUSPENDED);
            }
        }
    }

    @Transactional
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot reactivate(String businessId, String actorUserId) {
        Business business = requireBusiness(businessId);
        if (business.getSubscriptionBillingStatus() != SubscriptionBillingStatus.SUSPENDED
                || business.getSuspensionReason() != SuspensionReason.BILLING_UNPAID) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Business is not suspended for billing");
        }
        clearGraceFields(business);
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.ACTIVE);
        business.setSuspensionReason(null);
        business.setBillingSuspendedAt(null);
        business.setTenantStatus(TenantStatus.ACTIVE);
        if (business.getCurrentPeriodEnd() == null || !business.getCurrentPeriodEnd().isAfter(Instant.now())) {
            business.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        }
        businessRepository.save(business);
        cancelExpiryCampaigns(businessId);
        publishAudit(business.getId(), AuditEventTypes.SUBSCRIPTION_RENEWED, actorUserId);
        return adminSnapshot(businessId);
    }

    /**
     * STK settlement — extend period, reactivate tenant, clear grace.
     */
    @Transactional
    public void activateRenewal(SubscriptionRenewalOrder order) {
        Business business = requireBusiness(order.getBusinessId());
        if (order.getTierCode() != null && !order.getTierCode().isBlank()) {
            business.setSubscriptionTier(order.getTierCode().trim().toLowerCase());
        }
        Instant now = Instant.now();
        Instant base = business.getCurrentPeriodEnd() != null && business.getCurrentPeriodEnd().isAfter(now)
                ? business.getCurrentPeriodEnd()
                : now;
        int months = Math.max(1, order.getPeriodMonths());
        business.setCurrentPeriodEnd(base.plus(months * 30L, ChronoUnit.DAYS));
        clearGraceFields(business);
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.ACTIVE);
        business.setSuspensionReason(null);
        business.setBillingSuspendedAt(null);
        business.setTenantStatus(TenantStatus.ACTIVE);
        businessRepository.save(business);
        cancelExpiryCampaigns(business.getId());
        publishAudit(business.getId(), AuditEventTypes.SUBSCRIPTION_RENEWED, null);
    }

    private void cancelExpiryCampaigns(String businessId) {
        expiryCampaignService.ifAvailable(c -> c.cancelActiveCampaigns(businessId));
    }

    public static Instant initialPeriodEndForTier(String tier, Instant createdAt) {
        if (isFreeTierStatic(tier)) {
            return null;
        }
        return createdAt.plus(30, ChronoUnit.DAYS);
    }

    private SubscriptionBillingDtos.BillingStatusResponse buildStatusView(
            Business business,
            PlatformSubscriptionPlan plan
    ) {
        Instant now = Instant.now();
        int daysSinceExpiry = 0;
        int daysRemaining = 0;
        if (business.getSubscriptionBillingStatus() == SubscriptionBillingStatus.GRACE
                && business.getGraceStartedAt() != null) {
            daysSinceExpiry = (int) ChronoUnit.DAYS.between(
                    business.getGraceStartedAt().truncatedTo(ChronoUnit.DAYS),
                    now.truncatedTo(ChronoUnit.DAYS)) + 1;
            daysSinceExpiry = Math.max(1, daysSinceExpiry);
        }
        if (business.getSubscriptionBillingStatus() == SubscriptionBillingStatus.GRACE
                && business.getGraceEndsAt() != null) {
            long remaining = ChronoUnit.DAYS.between(
                    now.truncatedTo(ChronoUnit.DAYS),
                    business.getGraceEndsAt().truncatedTo(ChronoUnit.DAYS));
            daysRemaining = (int) Math.max(0, remaining);
            if (daysRemaining == 0 && now.isBefore(business.getGraceEndsAt())) {
                daysRemaining = 1;
            }
        }

        var settings = settingsService.loadSingleton();
        SubscriptionBillingDtos.PlanFitView planFit = null;
        SubscriptionPlanFitService fit = planFitService.getIfAvailable();
        if (fit != null) {
            try {
                planFit = fit.toView(fit.evaluate(business));
            } catch (RuntimeException ignored) {
                planFit = null;
            }
        }
        return new SubscriptionBillingDtos.BillingStatusResponse(
                business.getSubscriptionBillingStatus(),
                business.getSubscriptionTier(),
                plan != null ? plan.getDisplayName() : business.getSubscriptionTier(),
                amountDue(plan),
                business.getCurrency(),
                business.getCurrentPeriodEnd(),
                business.getGraceStartedAt(),
                business.getGraceEndsAt(),
                daysSinceExpiry,
                daysRemaining,
                settings.getRenewalBaseUrl(),
                settings.isBillingEnabled(),
                planFit);
    }

    private static BigDecimal amountDue(PlatformSubscriptionPlan plan) {
        return plan != null ? plan.getMonthlyPriceKes() : BigDecimal.ZERO;
    }

    private static void clearGraceFields(Business business) {
        business.setGraceStartedAt(null);
        business.setGraceEndsAt(null);
    }

    private static boolean isFreeTier(String tier) {
        return isFreeTierStatic(tier);
    }

    private static boolean isFreeTierStatic(String tier) {
        return tier != null && "free".equalsIgnoreCase(tier.trim());
    }

    private Business requireBusiness(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
    }

    private void publishAudit(String businessId, String eventType, String actorUserId) {
        try {
            auditEventPublisher.publish(auditEventBuilder.builder(
                            AuditEventCategory.SYSTEM,
                            eventType,
                            AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(actorUserId,
                            actorUserId != null && !actorUserId.isBlank()
                                    ? AuditEventActorType.USER
                                    : AuditEventActorType.SYSTEM)
                    .target("subscription", businessId)
                    .source("subscription_billing")
                    .build());
        } catch (RuntimeException ignored) {
            // Audit must never break billing transitions.
        }
    }
}
