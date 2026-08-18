package zelisline.ub.airtime.domain;

import zelisline.ub.payments.application.StkPhoneNormalizer;

/**
 * Maps a Kenyan MSISDN to its mobile network so the cashier sees which telco
 * they are about to top up before committing the sale. Instalipa does not need
 * the network — this is purely so a mistyped number is caught by eye.
 *
 * <p>Safaricom: 070x, 071x, 072x, 0740–0743, 079x, 0110–0119, 0140–0143, 0180–0182
 * Airtel: 073x, 0750–0756, 0785–0789, 0100–0102
 * Telkom: 0770–0779
 * Equitel: 0763–0766
 * Faiba (JTL): 0747
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
        int ndc;
        try {
            ndc = Integer.parseInt(msisdn.substring(3, 6));
        } catch (NumberFormatException e) {
            return null;
        }
        return fromNdc(ndc);
    }

    static String fromNdc(int ndc) {
        if (ndc == 747) {
            return JTL;
        }
        if (ndc >= 700 && ndc <= 729) {
            return SAFARICOM;
        }
        if (ndc >= 740 && ndc <= 743) {
            return SAFARICOM;
        }
        if (ndc >= 790 && ndc <= 799) {
            return SAFARICOM;
        }
        if (ndc >= 110 && ndc <= 119) {
            return SAFARICOM;
        }
        if (ndc >= 140 && ndc <= 143) {
            return SAFARICOM;
        }
        if (ndc >= 180 && ndc <= 182) {
            return SAFARICOM;
        }
        if (ndc >= 730 && ndc <= 739) {
            return AIRTEL;
        }
        if (ndc >= 750 && ndc <= 756) {
            return AIRTEL;
        }
        if (ndc >= 785 && ndc <= 789) {
            return AIRTEL;
        }
        if (ndc >= 100 && ndc <= 102) {
            return AIRTEL;
        }
        if (ndc >= 770 && ndc <= 779) {
            return TELKOM;
        }
        if (ndc >= 763 && ndc <= 766) {
            return EQUITEL;
        }
        return null;
    }
}
