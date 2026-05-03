package zelisline.ub.credits;

public final class WalletTxnTypes {

    public static final String DEBIT_SALE = "debit_sale";
    public static final String CREDIT_OVERPAY_CHANGE = "credit_overpay_change";
    public static final String CREDIT_COUNTER_TOPUP = "credit_counter_topup";
    public static final String CREDIT_MPESA_STK = "credit_mpesa_stk";
    /** Undo wallet spend on void — increases stored-value balance again. */
    public static final String REVERSAL_VOID_SPEND_RESTORE = "reversal_void_spend_restore";
    /** Undo overpay-to-wallet on void — reduces stored-value balance. */
    public static final String REVERSAL_VOID_OVERPAY_CLAW = "reversal_void_overpay_claw";
    public static final String CREDIT_REFUND = "credit_refund_sale";

    private WalletTxnTypes() {
    }
}
