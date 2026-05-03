package zelisline.ub.finance;

public final class LedgerAccountCodes {

    public static final String INVENTORY = "1200";
    public static final String SALES_REVENUE = "4000";
    public static final String COST_OF_GOODS_SOLD = "5000";
    /** Operating expenses root (Phase 6). */
    public static final String OPERATING_EXPENSES = "6000";
    public static final String OPERATING_CASH = "1010";
    /** Generic bank account (Phase 6). */
    public static final String BANK_ACCOUNT = "1030";
    /** Customer tab / accounts receivable — POS credit tender (Phase 5). */
    public static final String ACCOUNTS_RECEIVABLE_CUSTOMERS = "1100";
    /** Customer prepaid wallet (liability) — Phase 5. */
    public static final String CUSTOMER_WALLET_LIABILITY = "1175";
    /** Till-side M-Pesa / mobile money clearing (manual reference in Phase 4). */
    public static final String MPESA_CLEARING = "1020";
    /** Points redeemed at POS — contra-revenue via liability bucket (Phase 5 MVP). */
    public static final String LOYALTY_REDEMPTION_LIABILITY = "2196";

    public static final String ACCOUNTS_PAYABLE = "2100";
    public static final String GOODS_RECEIVED_NOT_INVOICED = "2150";
    public static final String SUPPLIER_ADVANCES = "1160";
    public static final String INVENTORY_SHRINKAGE = "5210";
    public static final String PURCHASE_PRICE_VARIANCE = "5220";
    /** Credit offset for opening balances and count gains (no POS sale yet). */
    public static final String OPENING_BALANCE_EQUITY = "3980";
    /** Drawer count vs expected; net over/short at shift close (Phase 4). */
    public static final String CASH_OVER_SHORT = "3900";

    private LedgerAccountCodes() {
    }
}
