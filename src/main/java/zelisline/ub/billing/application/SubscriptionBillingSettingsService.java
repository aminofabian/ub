package zelisline.ub.billing.application;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.domain.PlatformSubscriptionBillingSettings;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.billing.repository.PlatformSubscriptionBillingSettingsRepository;
import zelisline.ub.billing.repository.PlatformSubscriptionPlanRepository;

@Service
@RequiredArgsConstructor
public class SubscriptionBillingSettingsService {

    private final PlatformSubscriptionBillingSettingsRepository settingsRepository;
    private final PlatformSubscriptionPlanRepository planRepository;

    @Transactional
    public PlatformSubscriptionBillingSettings loadSingleton() {
        return settingsRepository.findFirstByOrderById()
                .orElseGet(() -> {
                    PlatformSubscriptionBillingSettings row = new PlatformSubscriptionBillingSettings();
                    row.setId(PlatformSubscriptionBillingSettings.SINGLETON_ID);
                    return settingsRepository.save(row);
                });
    }

    @Transactional(readOnly = true)
    public boolean isBillingEnabled() {
        return loadSingleton().isBillingEnabled();
    }

    @Transactional(readOnly = true)
    public int defaultGraceDays() {
        return loadSingleton().getDefaultGraceDays();
    }

    @Transactional(readOnly = true)
    public SubscriptionBillingDtos.SettingsResponse getSettings() {
        return toSettingsResponse(loadSingleton());
    }

    @Transactional
    public SubscriptionBillingDtos.SettingsResponse updateSettings(
            SubscriptionBillingDtos.UpdateSettingsRequest body
    ) {
        PlatformSubscriptionBillingSettings row = loadSingleton();
        if (body.billingEnabled() != null) {
            row.setBillingEnabled(body.billingEnabled());
        }
        if (body.defaultGraceDays() != null) {
            if (body.defaultGraceDays() < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "grace days must be at least 1");
            }
            row.setDefaultGraceDays(body.defaultGraceDays());
        }
        if (body.renewalBaseUrl() != null && !body.renewalBaseUrl().isBlank()) {
            row.setRenewalBaseUrl(body.renewalBaseUrl().trim());
        }
        if (body.notificationCadenceDays() != null && !body.notificationCadenceDays().isBlank()) {
            validateCadence(body.notificationCadenceDays());
            row.setNotificationCadenceDays(body.notificationCadenceDays().trim());
        }
        if (body.preExpiryReminderDays() != null) {
            if (body.preExpiryReminderDays() < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pre-expiry days must be at least 1");
            }
            row.setPreExpiryReminderDays(body.preExpiryReminderDays());
        }
        row.setUpdatedAt(Instant.now());
        return toSettingsResponse(settingsRepository.save(row));
    }

    @Transactional(readOnly = true)
    public SubscriptionBillingDtos.PlansResponse getPlans() {
        List<SubscriptionBillingDtos.PlanResponse> plans = planRepository.findAllByOrderBySortOrderAsc().stream()
                .map(this::toPlanResponse)
                .toList();
        return new SubscriptionBillingDtos.PlansResponse(plans);
    }

    @Transactional
    public SubscriptionBillingDtos.PlansResponse upsertPlan(
            String tierCode,
            SubscriptionBillingDtos.UpdatePlanRequest body
    ) {
        if (tierCode == null || tierCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tier code is required");
        }
        PlatformSubscriptionPlan row = planRepository.findById(tierCode.trim().toLowerCase())
                .orElseGet(() -> {
                    PlatformSubscriptionPlan created = new PlatformSubscriptionPlan();
                    created.setTierCode(tierCode.trim().toLowerCase());
                    return created;
                });
        if (body.displayName() != null && !body.displayName().isBlank()) {
            row.setDisplayName(body.displayName().trim());
        }
        if (body.monthlyPriceKes() != null) {
            if (body.monthlyPriceKes().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "price cannot be negative");
            }
            row.setMonthlyPriceKes(body.monthlyPriceKes());
        }
        if (body.annualPriceKes() != null) {
            if (body.annualPriceKes().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "annual price cannot be negative");
            }
            row.setAnnualPriceKes(body.annualPriceKes());
        }
        if (body.graceDays() != null) {
            if (body.graceDays() < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "grace days must be at least 1");
            }
            row.setGraceDays(body.graceDays());
        }
        if (body.productLimit() != null) {
            row.setProductLimit(body.productLimit());
        }
        if (body.cashierLimit() != null) {
            row.setCashierLimit(body.cashierLimit());
        }
        if (body.active() != null) {
            row.setActive(body.active());
        }
        if (body.sortOrder() != null) {
            row.setSortOrder(body.sortOrder());
        }
        row.setUpdatedAt(Instant.now());
        planRepository.save(row);
        return getPlans();
    }

    @Transactional(readOnly = true)
    public PlatformSubscriptionPlan requireActivePlan(String tierCode) {
        if (tierCode == null || tierCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tier is required");
        }
        return planRepository.findByTierCodeAndActiveTrue(tierCode.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
    }

    @Transactional(readOnly = true)
    public PlatformSubscriptionPlan planOrNull(String tierCode) {
        if (tierCode == null || tierCode.isBlank()) {
            return null;
        }
        return planRepository.findByTierCodeAndActiveTrue(tierCode.trim().toLowerCase()).orElse(null);
    }

    public int resolveGraceDays(PlatformSubscriptionPlan plan) {
        if (plan != null && plan.getGraceDays() > 0) {
            return plan.getGraceDays();
        }
        return defaultGraceDays();
    }

    private SubscriptionBillingDtos.SettingsResponse toSettingsResponse(PlatformSubscriptionBillingSettings row) {
        return new SubscriptionBillingDtos.SettingsResponse(
                row.isBillingEnabled(),
                row.getDefaultGraceDays(),
                row.getRenewalBaseUrl(),
                row.getNotificationCadenceDays(),
                row.getPreExpiryReminderDays(),
                row.getUpdatedAt());
    }

    private static void validateCadence(String raw) {
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            try {
                int day = Integer.parseInt(trimmed);
                if (day < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cadence days must be non-negative");
                }
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cadence day: " + trimmed);
            }
        }
    }

    private SubscriptionBillingDtos.PlanResponse toPlanResponse(PlatformSubscriptionPlan row) {
        return new SubscriptionBillingDtos.PlanResponse(
                row.getTierCode(),
                row.getDisplayName(),
                row.getMonthlyPriceKes(),
                row.getAnnualPriceKes(),
                row.getGraceDays(),
                row.getProductLimit(),
                row.getCashierLimit(),
                row.isActive(),
                row.getSortOrder());
    }
}
