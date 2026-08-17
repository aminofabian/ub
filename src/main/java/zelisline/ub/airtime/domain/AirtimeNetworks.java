package zelisline.ub.airtime.domain;

import zelisline.ub.payments.application.StkPhoneNormalizer;

/**
 * Maps a Kenyan MSISDN to its mobile network so the cashier sees which telco
 * they are about to top up before committing the sale. Instalipa does not need
 * the network — this is purely so a mistyped number is caught by eye.
 */
public final class AirtimeNetworks {

    public static final String SAFARICOM = "SAFARICOM";
    public static final String AIRTEL = "AIRTEL";
    public static final String TELKOM = "TELKOM";
    public static final String EQUITEL = "EQUITEL";
    public static final String JTL = "JTL";

    private AirtimeNetworks() {
    }

    /** Returns a network constant, or null when the prefix is unrecognised. */
    public static String detect(String rawPhone) {
        String msisdn = StkPhoneNormalizer.normalize(rawPhone);
        if (msisdn == null || msisdn.length() < 6) {
            return null;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(msisdn.substring(3, 6));
        } catch (NumberFormatException e) {
            return null;
        }

        if (prefix == 747) {
            return JTL;
        }
        if (prefix >= 763 && prefix <= 765) {
            return EQUITEL;
        }
        if (prefix >= 770 && prefix <= 779) {
            return TELKOM;
        }
        if (prefix >= 100 && prefix <= 102) {
            return AIRTEL;
        }
        if (prefix >= 730 && prefix <= 739) {
            return AIRTEL;
        }
        if (prefix >= 750 && prefix <= 756) {
            return AIRTEL;
        }
        if (prefix == 762) {
            return AIRTEL;
        }
        if (prefix >= 780 && prefix <= 789) {
            return AIRTEL;
        }
        if (prefix >= 110 && prefix <= 115) {
            return SAFARICOM;
        }
        if (prefix >= 700 && prefix <= 729) {
            return SAFARICOM;
        }
        if (prefix >= 740 && prefix <= 746) {
            return SAFARICOM;
        }
        if (prefix == 748) {
            return SAFARICOM;
        }
        if (prefix >= 757 && prefix <= 759) {
            return SAFARICOM;
        }
        if (prefix >= 768 && prefix <= 769) {
            return SAFARICOM;
        }
        if (prefix >= 790 && prefix <= 799) {
            return SAFARICOM;
        }
        return null;
    }
}
