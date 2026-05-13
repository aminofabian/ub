package zelisline.ub.tenancy.application;

import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Phase 9: Lightweight feature-flag reader backed by {@code businesses.settings}
 * JSON. Flags are stored under the {@code featureFlags} key as a flat
 * {@code { "flagName": true|false }} map.
 *
 * <p>Flags are tenant-scoped (per business). Default behaviour when a flag is
 * absent is conservative: multi-branch features default to {@code false}
 * (single-branch mode); other flags default to {@code false} unless documented
 * otherwise.</p>
 */
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final BusinessRepository businessRepository;
    private final StorefrontSettingsService storefrontSettingsService;

    /** Multi-branch / branch switcher (Phase 9 Slice 1). */
    public static final String FLAG_MULTI_BRANCH = "multi_branch";

    /** Enhanced offline POS with conflict resolution (Phase 9 Slice 4). */
    public static final String FLAG_OFFLINE_POS_ENHANCED = "offline_pos_enhanced";

    /** Expiry date tracking on inventory batches. */
    public static final String FLAG_EXPIRY_TRACKING = "expiry_tracking";

    /** Loyalty programme. */
    public static final String FLAG_LOYALTY = "loyalty";

    /** M-Pesa STK push payments. */
    public static final String FLAG_MPESA_STK = "mpesa_stk";

    /**
     * Read all feature flags for a business. Returns an empty map when the
     * business or its settings are absent.
     */
    public Map<String, Boolean> allFlags(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getSettings)
                .map(s -> storefrontSettingsService.readTenantConfig(s, ""))
                .map(c -> c.featureFlags())
                .orElse(Map.of());
    }

    /**
     * Check whether a specific flag is enabled for the business. Returns
     * {@code false} when the business, its settings, or the flag are absent.
     */
    public boolean isEnabled(String businessId, String flag) {
        Boolean v = allFlags(businessId).get(flag);
        return Boolean.TRUE.equals(v);
    }

    /** Shortcut for Phase 9 multi-branch check. */
    public boolean isMultiBranchEnabled(String businessId) {
        return isEnabled(businessId, FLAG_MULTI_BRANCH);
    }
}
