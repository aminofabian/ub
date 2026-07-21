package zelisline.ub.credits.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import zelisline.ub.payments.application.StkPhoneNormalizer;

/**
 * Kenyan MSISDN display + lookup helpers. Phones in {@code customer_phones} are stored as
 * digit-stripped values that may be either {@code 07…} or {@code 2547…}.
 */
public final class KenyanPhoneForms {

    private KenyanPhoneForms() {
    }

    /** Returns {@code 2547XXXXXXXX} or null if not a plausible Kenyan mobile. */
    public static String toMsisdn254(String raw) {
        return StkPhoneNormalizer.normalize(raw);
    }

    /** Returns local display form {@code 07XXXXXXXX} from any plausible Kenyan mobile input. */
    public static String toLocal07(String raw) {
        String msisdn = toMsisdn254(raw);
        if (msisdn == null || msisdn.length() < 12 || !msisdn.startsWith("254")) {
            return null;
        }
        return "0" + msisdn.substring(3);
    }

    /**
     * Candidate digit strings to match against {@code customer_phones.phone}
     * (both {@code 07…} and {@code 2547…} forms, plus raw digits).
     */
    public static List<String> lookupCandidates(String raw) {
        Set<String> out = new LinkedHashSet<>();
        String digits = CustomerPhoneNormalizer.normalize(raw);
        if (!digits.isEmpty()) {
            out.add(digits);
        }
        String msisdn = toMsisdn254(raw);
        if (msisdn != null) {
            out.add(msisdn);
            String local = toLocal07(msisdn);
            if (local != null) {
                out.add(local);
            }
        }
        return new ArrayList<>(out);
    }

    /** True when the path segment looks like a Kenyan mobile for the public tab portal. */
    public static boolean looksLikeKenyanMobile(String raw) {
        return toMsisdn254(raw) != null;
    }
}
