package zelisline.ub.platform.logs;

/**
 * High-level grouping for the platform request log (Super Admin → Platform →
 * Logs). Derived from the request path so super-admins can see the four
 * transaction families that matter at a glance, plus everything else.
 */
public enum RequestLogCategory {
    /** POS / cashier checkout flows — sales, drafts, web orders, carts, grocery. */
    CASHIER,
    /** Mobile-money payment processing — M-Pesa STK, kiosk-pay, payment rails. */
    MPESA,
    /** Airtime purchase flows (Instalipa + tenant/storefront orders). */
    AIRTIME,
    /** KPLC prepaid token purchases / meter management. */
    KPLC,
    /** Everything else — catalog, inventory, auth, super-admin ops, … */
    OTHER
}
