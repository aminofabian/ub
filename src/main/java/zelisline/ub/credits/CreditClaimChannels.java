package zelisline.ub.credits;

/**
 * Settlement channel a manager attests when approving a public payment claim. Determines which
 * asset account is debited against {@code 1100 Accounts Receivable} (ADR-0010).
 */
public final class CreditClaimChannels {

    public static final String CASH = "cash";
    public static final String MPESA = "mpesa";

    private CreditClaimChannels() {
    }

    public static boolean isValid(String channel) {
        return CASH.equals(channel) || MPESA.equals(channel);
    }
}
