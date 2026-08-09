package zelisline.ub.suppliers.domain;

/**
 * Tenant supplier disbursement destination for KopoKopo Send Money (external recipients).
 *
 * @see <a href="https://developers.kopokopo.com/guides/send-money/send-money-to-external-recipients.html">Send Money to external recipients</a>
 */
public final class SupplierPayoutTypes {

    public static final String MANUAL = "manual";
    /** M-Pesa phone ({@code type: mobile_wallet}). */
    public static final String MOBILE_WALLET = "mobile_wallet";
    /** Buy Goods till ({@code type: till}). */
    public static final String TILL = "till";
    /** Paybill + account ({@code type: paybill}). */
    public static final String PAYBILL = "paybill";

    private SupplierPayoutTypes() {
    }

    public static boolean isAutomated(String payoutType) {
        if (payoutType == null || payoutType.isBlank()) {
            return false;
        }
        String t = payoutType.trim().toLowerCase();
        return MOBILE_WALLET.equals(t) || TILL.equals(t) || PAYBILL.equals(t);
    }

    public static boolean isValid(String payoutType) {
        if (payoutType == null || payoutType.isBlank()) {
            return false;
        }
        String t = payoutType.trim().toLowerCase();
        return MANUAL.equals(t) || isAutomated(t);
    }
}
