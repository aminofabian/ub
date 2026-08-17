package zelisline.ub.payments.domain;

/** Immutable ledger entry kinds for Kiosk Pay. */
public final class KioskPayLedgerEntryTypes {

    public static final String PAYMENT_CAPTURE = "PAYMENT_CAPTURE";
    /** Legacy platform markup (no longer charged; kept for historical ledger rows). */
    public static final String PLATFORM_FEE = "PLATFORM_FEE";
    /** Paystack / KopoKopo processing fee passed through to the tenant. */
    public static final String PROVIDER_FEE = "PROVIDER_FEE";
    public static final String WITHDRAW_HOLD = "WITHDRAW_HOLD";
    public static final String WITHDRAW_SETTLE = "WITHDRAW_SETTLE";
    public static final String WITHDRAW_RELEASE = "WITHDRAW_RELEASE";
    public static final String ADJUSTMENT = "ADJUSTMENT";

    /** Merchant moved their own money into the wallet (M-Pesa STK). */
    public static final String TOPUP = "TOPUP";
    /** Airtime face value reserved while the provider works. */
    public static final String AIRTIME_HOLD = "AIRTIME_HOLD";
    /** Airtime delivered — the hold leaves the wallet for good. */
    public static final String AIRTIME_SETTLE = "AIRTIME_SETTLE";
    /** Airtime failed — the hold returns to available balance. */
    public static final String AIRTIME_RELEASE = "AIRTIME_RELEASE";
    /** Merchant's margin on a delivered airtime sale. */
    public static final String AIRTIME_COMMISSION = "AIRTIME_COMMISSION";

    public static final String CREDIT = "CREDIT";
    public static final String DEBIT = "DEBIT";

    private KioskPayLedgerEntryTypes() {
    }
}
