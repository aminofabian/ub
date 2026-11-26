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

    /**
     * Restaurant menu, orders and kitchen (ADR 0020). Absent defaults to enabled, so it is a
     * switch to turn the vertical off for a business, not something every restaurant must set.
     * Independent of the held-cart flags above.
     */
    public static final String FLAG_RESTAURANT_ENABLED = "restaurant.enabled";

    /** Allow cashiers (sales.sell) to override shelf prices on POS lines. */
    public static final String FLAG_POS_CASHIER_PRICE_EDIT = "pos.cashier_price_edit";

    /** Allow cashiers (sales.sell) to quick-create products from the POS. */
    public static final String FLAG_POS_CASHIER_CREATE_PRODUCT = "pos.cashier_create_product";

    /** Allow cashiers (sales.sell) to mark cart lines as sold by weight from the POS.
     * Absent / unset defaults to enabled; set {@code false} to disable for cashiers. */
    public static final String FLAG_POS_CASHIER_WEIGHED_TOGGLE = "pos.cashier_weighed_toggle";

    /** Allow owners/admins to upload product photos from the cashier shelf. */
    public static final String FLAG_POS_CASHIER_ADD_PHOTO = "pos.cashier_add_photo";

    /**
     * Show the Order pad button on the cashier till. Absent defaults to enabled;
     * set {@code false} to hide even when the user has order-pad permission.
     */
    public static final String FLAG_POS_CASHIER_ORDER_PAD = "pos.cashier_order_pad";

    /**
     * Show the Confirm orders button on the cashier till. Absent defaults to enabled;
     * set {@code false} to hide even when the user has Path A purchasing permission.
     */
    public static final String FLAG_POS_CASHIER_ORDER_CONFIRM = "pos.cashier_order_confirm";

    /**
     * Allow cashiers (and butcher cashiers) to record cash drawouts from an open till.
     * Absent / false keeps drawout for owners, admins, and managers only.
     */
    public static final String FLAG_POS_CASHIER_DRAWOUT = "pos.cashier_drawout";

    /**
     * Show Clear sale beside Checkout / Pay on the till. Absent defaults to enabled;
     * set {@code false} to hide the one-tap abandon control.
     */
    public static final String FLAG_POS_CASHIER_CLEAR_SALE = "pos.cashier_clear_sale";

    /**
     * When Clear sale / keypad Clear runs, void every open till tab (and its pending
     * draft) in one action. Absent / false keeps single-tab clear so unfinished sales
     * stay accountable via Sales → Unfinished sales.
     */
    public static final String FLAG_POS_CASHIER_CLEAR_ALL_SALES = "pos.cashier_clear_all_sales";

    /**
     * Cashiers may type an M-Pesa transaction code from the customer's message.
     * Absent / false keeps checkout on the prompt only.
     */
    public static final String FLAG_POS_CASHIER_MPESA_CODE = "pos.cashier_mpesa_code";

    /** Auto-add scanned barcodes straight to cart when the barcode resolves to
     * exactly one sellable product. When off, scanned barcodes fill the search
     * box (cashier must tap the hit). Independent of pos_drafts.enabled so
     * admins can allow scan-to-cart even without live draft monitoring. */
    public static final String FLAG_POS_SCAN_TO_CART = "pos.scan_to_cart";

    /**
     * Hybrid POS catalog: search-first list results + frequent chips instead of
     * a picture grid. Absent / false keeps the classic visual grid (default).
     */
    public static final String FLAG_POS_CATALOG_HYBRID = "pos.catalog_hybrid";

    /**
     * Listen for Buy Goods till payments when the cashier opens checkout / pay.
     * Absent defaults to enabled.
     */
    public static final String FLAG_POS_TILL_LISTEN_CHECKOUT = "pos.till_listen_checkout";

    /**
     * Listen whenever an open cart tab has a positive total (before checkout).
     * Absent defaults to disabled.
     */
    public static final String FLAG_POS_TILL_LISTEN_OPEN_CART = "pos.till_listen_open_cart";

    /**
     * Listen when the M-Pesa tender is selected on the pay drawer.
     * Absent defaults to disabled.
     */
    public static final String FLAG_POS_TILL_LISTEN_MPESA = "pos.till_listen_mpesa";

    /**
     * Listen for Buy Goods on the public storefront (cart preview + checkout).
     * Absent defaults to enabled.
     */
    public static final String FLAG_STOREFRONT_TILL_LISTEN = "storefront.till_listen";

    /**
     * Beep on the business hub when a POS sale completes.
     * Absent defaults to enabled.
     */
    public static final String FLAG_HUB_ALERTS_BEEP_ON_SALE = "hub.alerts.beep_on_sale";

    /**
     * Beep on the business hub when a supply bill is posted.
     * Absent defaults to enabled.
     */
    public static final String FLAG_HUB_ALERTS_BEEP_ON_SUPPLY = "hub.alerts.beep_on_supply";

    /**
     * Show the Kiosk "Opening the shop" screen while pages load.
     * Absent defaults to enabled; set {@code false} to hide it for this tenant.
     */
    public static final String FLAG_UI_OPENING_LOADER = "ui.opening_loader";

    /**
     * When enabled, opening a shift prefills denomination quantities from the
     * most recently closed shift at that branch (cashier can still edit).
     */
    public static final String FLAG_SHIFTS_PREFILL_OPENING_FROM_LAST_CLOSE =
            "shifts.prefill_opening_from_last_close";

    /**
     * The shop does not use cash-drawer shifts: cashiers are never asked to open one, and sales
     * ride on a hidden system-managed shift. Absent / false keeps shifts required (the default,
     * so every existing shop behaves as before).
     */
    public static final String FLAG_SHIFTS_DISABLED = "shifts.disabled";

    /**
     * Email the owner a stock sheet (buying, selling, on-hand) and today's sales
     * when a shift closes. Absent defaults to on; {@code false} is the opt-out.
     */
    public static final String FLAG_SHIFTS_EMAIL_CLOSE_REPORTS = "shifts.email_close_reports";

    /**
     * Only registered (trusted) tills may PIN-login, open a shift, or sell when the branch
     * already has at least one active till. Absent / false keeps selling open for any
     * signed-in till worker (owners still get access-request notifications).
     */
    public static final String FLAG_TILL_REQUIRE_TRUSTED = "till.require_trusted";

    /** Grocery counter draft cart persistence (master kill-switch). */
    public static final String FLAG_GROCERY_DRAFTS_ENABLED = "grocery_drafts.enabled";

    /** Shadow-write grocery drafts without changing counter UI. */
    public static final String FLAG_GROCERY_DRAFTS_SHADOW_WRITES = "grocery_drafts.shadow_writes";

    /** Show Counter #, sync status, and pending panel on grocery counter. */
    public static final String FLAG_GROCERY_DRAFTS_UI_VISIBLE = "grocery_drafts.ui_visible";

    /** Local IndexedDB mirror and offline mutation replay for grocery drafts. */
    public static final String FLAG_GROCERY_DRAFTS_OFFLINE_MIRROR = "grocery_drafts.offline_mirror";

    /** Supplier engagement, master switch (SUPPLIER_ENGAGEMENT_DESIGN.md). Off by default. */
    public static final String FLAG_SUPPLIER_ENGAGEMENT = "supplier_engagement";

    /** The supplier readiness checklist. Needs {@link #FLAG_SUPPLIER_ENGAGEMENT} as well. Off by default. */
    public static final String FLAG_SUPPLIER_ENGAGEMENT_READINESS = "supplier_engagement.readiness";

    /** Supplier milestones (the first order supplied). Needs {@link #FLAG_SUPPLIER_ENGAGEMENT} as well. Off by default. */
    public static final String FLAG_SUPPLIER_ENGAGEMENT_MILESTONES = "supplier_engagement.milestones";

    /** Supplier standing over 90 days (supplier-only). Needs {@link #FLAG_SUPPLIER_ENGAGEMENT} as well. Off by default. */
    public static final String FLAG_SUPPLIER_ENGAGEMENT_STANDING = "supplier_engagement.standing";

    /** Trust lines on a supplier's public page: true lines only, from verified buyers, sampled. Off by default. */
    public static final String FLAG_SUPPLIER_ENGAGEMENT_BUYER_LINES = "supplier_engagement.buyer_lines";

    /** A person's own contribution, shown only to them. Needs {@link #FLAG_SUPPLIER_ENGAGEMENT}. Off by default. */
    public static final String FLAG_SUPPLIER_ENGAGEMENT_CONTRIBUTION = "supplier_engagement.contribution";

    /**
     * The one note engagement sends: orders that waited more than a working day, at most once a day, to the
     * owner. Needs {@link #FLAG_SUPPLIER_ENGAGEMENT}. Off by default.
     */
    public static final String FLAG_SUPPLIER_ENGAGEMENT_NOTIFICATIONS = "supplier_engagement.notifications";

    /**
     * A supplier shop that also sells over the counter: a clerk takes the order and prints the invoice, and the
     * cashier takes the payment. The supplier turns it on itself. Off by default.
     */
    public static final String FLAG_SUPPLIER_COUNTER_ORDERS = "supplier.counter_orders";

    /**
     * Desktop till: mirror a supplier's trade orders, buyer accounts and invoices onto the till and push what
     * the supplier does there (answer, dispatch, …) back to the online shop. Off by default; needs a till build
     * that carries the trade-sync streams. See DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md.
     */
    public static final String FLAG_DESKTOP_TRADE_SYNC = "desktop.trade_sync";

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

    /**
     * Flags that default ON when absent (till listen at checkout / storefront).
     */
    public boolean isEnabledDefaultTrue(String businessId, String flag) {
        Boolean v = allFlags(businessId).get(flag);
        return v == null || Boolean.TRUE.equals(v);
    }

    public boolean isPosTillListenCheckout(String businessId) {
        return isEnabledDefaultTrue(businessId, FLAG_POS_TILL_LISTEN_CHECKOUT);
    }

    public boolean isPosTillListenOpenCart(String businessId) {
        return isEnabled(businessId, FLAG_POS_TILL_LISTEN_OPEN_CART);
    }

    public boolean isPosTillListenMpesa(String businessId) {
        return isEnabled(businessId, FLAG_POS_TILL_LISTEN_MPESA);
    }

    public boolean isStorefrontTillListen(String businessId) {
        return isEnabledDefaultTrue(businessId, FLAG_STOREFRONT_TILL_LISTEN);
    }

    public boolean isHubAlertsBeepOnSale(String businessId) {
        return isEnabledDefaultTrue(businessId, FLAG_HUB_ALERTS_BEEP_ON_SALE);
    }

    public boolean isHubAlertsBeepOnSupply(String businessId) {
        return isEnabledDefaultTrue(businessId, FLAG_HUB_ALERTS_BEEP_ON_SUPPLY);
    }

    /** Shortcut for Phase 9 multi-branch check. */
    public boolean isMultiBranchEnabled(String businessId) {
        return isEnabled(businessId, FLAG_MULTI_BRANCH);
    }

    /** True when the shop turned shifts off; sales then ride on a hidden system-managed shift. */
    public boolean isShiftsDisabled(String businessId) {
        return isEnabled(businessId, FLAG_SHIFTS_DISABLED);
    }

    /**
     * True when the shop opted into trusted-till enforcement. Absent defaults to off so
     * signed-in cashiers can sell until an owner or super admin turns the lock on.
     */
    public boolean isTillRequireTrusted(String businessId) {
        return isEnabled(businessId, FLAG_TILL_REQUIRE_TRUSTED);
    }

    /**
     * Owners/admins/managers always may. Till cashiers need {@code pos.cashier_drawout}
     * and, when scope is {@code selected}, to be on the allow list.
     */
    public boolean cashierMayRecordDrawout(String businessId, String userId, String roleKey) {
        String key = roleKey == null ? "" : roleKey.trim().toLowerCase();
        if (!"cashier".equals(key) && !"butcher_cashier".equals(key)) {
            return true;
        }
        return businessRepository.findById(businessId)
                .map(business -> {
                    if (!Boolean.TRUE.equals(
                            storefrontSettingsService
                                    .readTenantConfig(business.getSettings(), "")
                                    .featureFlags()
                                    .get(FLAG_POS_CASHIER_DRAWOUT))) {
                        return false;
                    }
                    return storefrontSettingsService
                            .readCashierDrawoutAccess(business.getSettings())
                            .allowsUser(userId);
                })
                .orElse(false);
    }
}
