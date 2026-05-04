package zelisline.ub.sales;

public final class SalesConstants {

    public static final String SHIFT_STATUS_OPEN = "open";
    public static final String SHIFT_STATUS_CLOSED = "closed";

    public static final String SALE_STATUS_COMPLETED = "completed";
    public static final String SALE_STATUS_VOIDED = "voided";
    public static final String SALE_STATUS_REFUNDED = "refunded";

    public static final String REFUND_STATUS_COMPLETED = "completed";

    public static final String PAYMENT_METHOD_CASH = "cash";
    public static final String PAYMENT_METHOD_MPESA_MANUAL = "mpesa_manual";
    /** Tab / pay-later — posts to customer AR and credit_accounts.balance_owed (Phase 5). */
    public static final String PAYMENT_METHOD_CUSTOMER_CREDIT = "customer_credit";
    /** Prepaid customer wallet debit at checkout (Phase 5). */
    public static final String PAYMENT_METHOD_CUSTOMER_WALLET = "customer_wallet";
    /** Tender representing loyalty monetary value redeemed (Phase 5). */
    public static final String PAYMENT_METHOD_LOYALTY_REDEEM = "loyalty_redeem";

    public static final String STOCK_REFERENCE_TYPE_SALE = "sale";
    public static final String STOCK_REFERENCE_TYPE_SALE_VOID = "sale_void";
    public static final String STOCK_REFERENCE_TYPE_SALE_REFUND = "sale_refund";

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

    private SalesConstants() {
    }
}
