package zelisline.ub.kplc.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class KplcTokenIdentity {

    private KplcTokenIdentity() {
    }

    static String normalizeTokenNo(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.isEmpty() ? null : digits.toString();
    }

    /** Purchase instants match to the second so the same slip is not stored twice. */
    static Instant matchInstant(Instant purchasedAt) {
        if (purchasedAt == null) {
            return null;
        }
        return purchasedAt.truncatedTo(ChronoUnit.SECONDS);
    }
}
