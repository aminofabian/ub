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

    /** Cashier POS draft cart persistence (master kill-switch). */
    public static final String FLAG_POS_DRAFTS_ENABLED = "pos_drafts.enabled";

    /** Shadow-write drafts without changing cashier UI. */
    public static final String FLAG_POS_DRAFTS_SHADOW_WRITES = "pos_drafts.shadow_writes";

    /** Show ticket numbers, sync status, and pending panel on cashier. */
    public static final String FLAG_POS_DRAFTS_UI_VISIBLE = "pos_drafts.ui_visible";

    /** Local IndexedDB mirror and offline mutation replay. */
    public static final String FLAG_POS_DRAFTS_OFFLINE_MIRROR = "pos_drafts.offline_mirror";

    /** Butcher counter POS workspace and weighed-sale features. */
    public static final String FLAG_BUTCHER_POS_ENABLED = "butcher_pos.enabled";

    /** Allow cashiers (sales.sell) to override shelf prices on POS lines. */
    public static final String FLAG_POS_CASHIER_PRICE_EDIT = "pos.cashier_price_edit";

    /** Allow cashiers (sales.sell) to quick-create products from the POS. */
    public static final String FLAG_POS_CASHIER_CREATE_PRODUCT = "pos.cashier_create_product";

    /** Grocery counter draft cart persistence (master kill-switch). */
    public static final String FLAG_GROCERY_DRAFTS_ENABLED = "grocery_drafts.enabled";

    /** Shadow-write grocery drafts without changing counter UI. */
    public static final String FLAG_GROCERY_DRAFTS_SHADOW_WRITES = "grocery_drafts.shadow_writes";

    /** Show Counter #, sync status, and pending panel on grocery counter. */
    public static final String FLAG_GROCERY_DRAFTS_UI_VISIBLE = "grocery_drafts.ui_visible";

    /** Local IndexedDB mirror and offline mutation replay for grocery drafts. */
    public static final String FLAG_GROCERY_DRAFTS_OFFLINE_MIRROR = "grocery_drafts.offline_mirror";

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
     * Check whether the butcher POS vertical is enabled for the business.
     */
    public boolean isButcherPosEnabled(String businessId) {
        return isEnabled(businessId, FLAG_BUTCHER_POS_ENABLED);
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
