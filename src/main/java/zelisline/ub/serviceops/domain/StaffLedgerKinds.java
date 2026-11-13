package zelisline.ub.serviceops.domain;

/** Vocabulary of {@link StaffLedgerEntry}. */
public final class StaffLedgerKinds {

    public static final String COMMISSION = "COMMISSION";
    public static final String TIP = "TIP";
    public static final String REVERSAL = "REVERSAL";
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String PAYOUT = "PAYOUT";

    public static final String BUCKET_COMMISSION = "COMMISSION";
    public static final String BUCKET_TIPS = "TIPS";

    public static final String SOURCE_SERVICE = "SERVICE";
    public static final String SOURCE_PRODUCT = "PRODUCT";

    public static final String METHOD_DRAWER = "DRAWER";
    public static final String METHOD_MPESA = "MPESA";
    public static final String METHOD_OTHER = "OTHER";

    private StaffLedgerKinds() {
    }
}
