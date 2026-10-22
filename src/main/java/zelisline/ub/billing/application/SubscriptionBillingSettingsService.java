package zelisline.ub.billing.application;

import java.net.URI;
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
import zelisline.ub.identity.application.FrontendAuthLinkBuilder;

@Service
@RequiredArgsConstructor
public class SubscriptionBillingSettingsService {

    private static final String DEFAULT_RENEWAL_PATH = "/business/billing/renew";

    private final PlatformSubscriptionBillingSettingsRepository settingsRepository;
    private final PlatformSubscriptionPlanRepository planRepository;
    private final FrontendAuthLinkBuilder frontendAuthLinkBuilder;

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
    public boolean isAutoBlockEnabled() {
        return loadSingleton().isAutoBlockEnabled();
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
        if (body.autoBlockEnabled() != null) {
            row.setAutoBlockEnabled(body.autoBlockEnabled());
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
            // -1 clears the cap back to unlimited (the admin editor sends it
            // when the field is blank).
            row.setProductLimit(body.productLimit() < 0 ? null : body.productLimit());
        }
        if (body.cashierLimit() != null) {
            row.setCashierLimit(body.cashierLimit() < 0 ? null : body.cashierLimit());
        }
        if (body.dailyRevenueLimitKes() != null) {
            if (body.dailyRevenueLimitKes().signum() < 0) {
                row.setDailyRevenueLimitKes(null);
            } else {
                row.setDailyRevenueLimitKes(body.dailyRevenueLimitKes());
            }
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

    /** Current plan row even if Super Admin has unpublished it; used for downgrade guards. */
    @Transactional(readOnly = true)
    public PlatformSubscriptionPlan planEvenIfInactive(String tierCode) {
        if (tierCode == null || tierCode.isBlank()) {
            return null;
        }
        return planRepository.findById(tierCode.trim().toLowerCase()).orElse(null);
    }

    public int resolveGraceDays(PlatformSubscriptionPlan plan) {
        if (plan != null && plan.getGraceDays() > 0) {
            return plan.getGraceDays();
        }
        return defaultGraceDays();
    }

    /**
     * Shop origin for a tenant ({@code https://{slug}.kiosk.ke}). Used as the
     * email chrome "open shop" link so campaigns never point every tenant at
     * a single hardcoded host.
     */
    @Transactional(readOnly = true)
    public String resolveShopOrigin(String businessId) {
        return frontendAuthLinkBuilder.tenantOrigin(businessId);
    }

    /**
     * Per-tenant renew link. Host is the shop's slug subdomain; path comes from
     * platform {@code renewalBaseUrl} (default {@code /business/billing/renew}).
     * The stored absolute URL is only a path template / last-resort fallback —
     * never send every tenant to {@code palmart.co.ke}.
     */
    @Transactional(readOnly = true)
    public String resolveRenewalUrl(String businessId) {
        String origin = resolveShopOrigin(businessId);
        String path = renewalPath(loadSingleton().getRenewalBaseUrl());
        if (origin == null || origin.isBlank()) {
            String fallback = loadSingleton().getRenewalBaseUrl();
            return fallback == null || fallback.isBlank()
                    ? "https://kiosk.ke" + DEFAULT_RENEWAL_PATH
                    : fallback.trim();
        }
        return trimTrailingSlash(origin.trim()) + path;
    }

    private static String renewalPath(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_RENEWAL_PATH;
        }
        String trimmed = configured.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getHost() != null) {
                String path = uri.getPath();
                if (path == null || path.isBlank() || "/".equals(path)) {
                    return DEFAULT_RENEWAL_PATH;
                }
                return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            }
        } catch (RuntimeException ignored) {
            // Treat as a relative path below.
        }
        return trimmed.startsWith("/") ? trimTrailingSlash(trimmed) : "/" + trimTrailingSlash(trimmed);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private SubscriptionBillingDtos.SettingsResponse toSettingsResponse(PlatformSubscriptionBillingSettings row) {
        return new SubscriptionBillingDtos.SettingsResponse(
                row.isBillingEnabled(),
                row.isAutoBlockEnabled(),
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
                row.getDailyRevenueLimitKes(),
                row.isActive(),
                row.getSortOrder());
    }
}
