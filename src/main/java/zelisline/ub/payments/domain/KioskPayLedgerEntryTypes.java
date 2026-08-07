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

    public static final String CREDIT = "CREDIT";
    public static final String DEBIT = "DEBIT";

    private KioskPayLedgerEntryTypes() {
    }
}
