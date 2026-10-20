package zelisline.ub.billing.application;

import java.time.Instant;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.TenantStatus;

/**
 * One place that decides whether a subscription problem blocks access.
 *
 * <p>Super Admin can switch automatic blocking off globally. When that happens
 * expiry still moves a shop through grace and raises warnings, but it must not
 * suspend the tenant, revoke sessions, or reject API calls. Manual tenant
 * deactivation ({@link TenantStatus#INACTIVE}) and user-level suspensions are
 * deliberately outside this policy and always remain enforced.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionAccessPolicy {

    private final SubscriptionBillingSettingsService settingsService;

    /**
     * Whether subscription status may block access right now. Billing has to be
     * enabled and the Super Admin auto-block switch has to be on.
     *
     * <p>Fails closed: if settings cannot be read, the previous blocking
     * behaviour is safer than accidentally unlocking every tenant.
     */
    public boolean isAccessBlockingEnabled() {
        try {
            return settingsService.isBillingEnabled() && settingsService.isAutoBlockEnabled();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /**
     * Effective access decision for a billing-suspended tenant. TenantStatus
     * SUSPENDED is only produced by billing; INACTIVE (manual) is never
     * softened here.
     */
    public boolean isSuspendedTenantBlocked(TenantStatus tenantStatus) {
        return tenantStatus == TenantStatus.SUSPENDED && isAccessBlockingEnabled();
    }

    /** Full-entity decision used by billing status responses and the renewal wall. */
    public boolean isAccessBlocked(Business business) {
        return isAccessBlocked(business, Instant.now());
    }

    public boolean isAccessBlocked(Business business, Instant now) {
        if (business == null || business.getDeletedAt() != null) {
            return false;
        }
        if (!isAccessBlockingEnabled()) {
            return false;
        }
        if (business.getSubscriptionBillingStatus() == SubscriptionBillingStatus.SUSPENDED) {
            return true;
        }
        return business.getSubscriptionBillingStatus() == SubscriptionBillingStatus.GRACE
                && business.getGraceEndsAt() != null
                && !business.getGraceEndsAt().isAfter(now);
    }

    /**
     * Auth, billing-status, renewal, and /me must stay reachable while a shop
     * is locked so the owner can pay and restore access.
     */
    public boolean isBillingRecoveryPath(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/subscription/")
                || "/api/v1/me".equals(path);
    }

    public boolean isBillingRecoveryRequest(HttpServletRequest request) {
        return request != null && isBillingRecoveryPath(request.getRequestURI());
    }
}
