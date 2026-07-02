package zelisline.ub.sales;

public final class SalesConstants {

    public static final String SHIFT_STATUS_OPEN = "open";
    public static final String SHIFT_STATUS_SUSPENDED = "suspended";
    public static final String SHIFT_STATUS_CLOSED = "closed";
    public static final String SHIFT_STATUS_RECONCILED = "reconciled";

    public static final String DENOM_COUNT_TYPE_OPENING = "OPENING";
    public static final String DENOM_COUNT_TYPE_CLOSING = "CLOSING";

    public static final String DENOM_TYPE_NOTE = "NOTE";
    public static final String DENOM_TYPE_COIN = "COIN";

    /** All Kenyan KES denominations: [1000, 500, 200, 100, 50, 40, 20, 10, 5, 1] */
    public static final int[] KES_DENOMINATIONS = {1000, 500, 200, 100, 50, 40, 20, 10, 5, 1};

    public static final String SALE_STATUS_COMPLETED = "completed";
    public static final String SALE_STATUS_VOIDED = "voided";
    public static final String SALE_STATUS_REFUNDED = "refunded";

    public static final String REFUND_STATUS_COMPLETED = "completed";

    public static final String PAYMENT_METHOD_CASH = "cash";
    public static final String PAYMENT_METHOD_MPESA_MANUAL = "mpesa_manual";
    /** Card terminal / POS card reader — clears to card clearing, not drawer cash. */
    public static final String PAYMENT_METHOD_CARD = "card";
    /** Tab / pay-later — posts to customer AR and credit_accounts.balance_owed (Phase 5). */
    public static final String PAYMENT_METHOD_CUSTOMER_CREDIT = "customer_credit";
    /** Prepaid customer wallet debit at checkout (Phase 5). */
    public static final String PAYMENT_METHOD_CUSTOMER_WALLET = "customer_wallet";
    /** Tender representing loyalty monetary value redeemed (Phase 5). */
    public static final String PAYMENT_METHOD_LOYALTY_REDEEM = "loyalty_redeem";

    public static final String STOCK_REFERENCE_TYPE_SALE = "sale";
    public static final String STOCK_REFERENCE_TYPE_SALE_VOID = "sale_void";
    public static final String STOCK_REFERENCE_TYPE_SALE_REFUND = "sale_refund";
    /** Guest/public web kiosk checkout — stock movements tie to {@code web_orders.id}. */
    public static final String STOCK_REFERENCE_TYPE_WEB_ORDER = "web_order";

    public static final String JOURNAL_SOURCE_SHIFT_CLOSE = "shift_close";
    public static final String JOURNAL_SOURCE_SALE = "sale";
    public static final String JOURNAL_SOURCE_SALE_VOID = "sale_void";
    public static final String JOURNAL_SOURCE_SALE_REFUND = "sale_refund";
    /** Counter wallet top-up (Phase 5). */
    public static final String JOURNAL_SOURCE_WALLET_TOPUP_CASH = "wallet_topup_cash";
    /** STK-push wallet credit (Phase 5). */
    public static final String JOURNAL_SOURCE_WALLET_TOPUP_MPESA_STK = "wallet_topup_mpesa_stk";
    /** Approved public/customer-submitted inbound payment toward AR (Phase 5). */
    public static final String JOURNAL_SOURCE_PUBLIC_PAYMENT_CLAIM = "public_payment_claim";
    /** Loyalty earn accrual — Dr marketing expense / Cr loyalty liability (ADR-0009). */
    public static final String JOURNAL_SOURCE_LOYALTY_EARN_ACCRUAL = "loyalty_earn_accrual";
    /** Reversal for void/refund of loyalty earn accrual. */
    public static final String JOURNAL_SOURCE_LOYALTY_EARN_REVERSAL = "loyalty_earn_reversal";

    /** Audit event types for shift tracking. */
    public static final String AUDIT_SHIFT_OPENED = "SHIFT_OPENED";
    public static final String AUDIT_SHIFT_SUSPENDED = "SHIFT_SUSPENDED";
    public static final String AUDIT_SHIFT_RESUMED = "SHIFT_RESUMED";
    public static final String AUDIT_PAID_IN = "PAID_IN";
    public static final String AUDIT_PAID_OUT = "PAID_OUT";
    public static final String AUDIT_CASH_DROP = "CASH_DROP";
    public static final String AUDIT_SHIFT_CLOSED = "SHIFT_CLOSED";
    public static final String AUDIT_VARIANCE_APPROVED = "VARIANCE_APPROVED";
    public static final String AUDIT_RECONCILIATION_COMPLETE = "RECONCILIATION_COMPLETE";
    public static final String AUDIT_NOTE_ADDED = "NOTE_ADDED";

    // ========================================================================
    // DRAWOUT STATUSES
    // ========================================================================

    public static final String DRAWOUT_STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String DRAWOUT_STATUS_APPROVED = "APPROVED";
    public static final String DRAWOUT_STATUS_REJECTED = "REJECTED";
    public static final String DRAWOUT_STATUS_VOIDED = "VOIDED";
    public static final String DRAWOUT_STATUS_EXPIRED = "EXPIRED";

    // ========================================================================
    // DRAWOUT CATEGORIES
    // ========================================================================

    public static final String DRAWOUT_CATEGORY_PETTY_CASH = "PETTY_CASH";
    public static final String DRAWOUT_CATEGORY_CASUAL_LABOUR = "CASUAL_LABOUR";
    public static final String DRAWOUT_CATEGORY_SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT";
    public static final String DRAWOUT_CATEGORY_RECURRING = "RECURRING";
    public static final String DRAWOUT_CATEGORY_OTHER = "OTHER";

    // ========================================================================
    // DRAWOUT AUDIT EVENTS
    // ========================================================================

    public static final String AUDIT_DRAWOUT_INITIATED = "DRAWOUT_INITIATED";
    public static final String AUDIT_DRAWOUT_APPROVED = "DRAWOUT_APPROVED";
    public static final String AUDIT_DRAWOUT_REJECTED = "DRAWOUT_REJECTED";
    public static final String AUDIT_DRAWOUT_VOIDED = "DRAWOUT_VOIDED";
    public static final String AUDIT_DRAWOUT_EXPIRED = "DRAWOUT_EXPIRED";

    // ========================================================================
    // RECURRING FREQUENCIES
    // ========================================================================

    public static final String RECURRING_FREQ_DAILY = "DAILY";
    public static final String RECURRING_FREQ_PER_SHIFT = "PER_SHIFT";
    public static final String RECURRING_FREQ_WEEKLY = "WEEKLY";
    public static final String RECURRING_FREQ_MANUAL = "MANUAL";

    // ========================================================================
    // DRAWOUT EXPENSE TYPE
    // ========================================================================

    public static final String EXPENSE_TYPE_DRAWOUT = "DRAWOUT";

    // ========================================================================
    // APPROVAL TIERS
    // ========================================================================

    public static final int APPROVAL_TIER_1 = 1;
    public static final int APPROVAL_TIER_2 = 2;
    public static final int APPROVAL_TIER_3 = 3;
    public static final int APPROVAL_TIER_4 = 4;

    private SalesConstants() {
    }
}
