package zelisline.ub.credits.domain;

public final class PayerNameNormalizer {

    private PayerNameNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ").toUpperCase();
    }

    public static String displayName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        if (f.isEmpty()) {
            return l;
        }
        if (l.isEmpty()) {
            return f;
        }
        return f + " " + l;
    }

    public static String identityKey(String firstNorm, String lastNorm, String fingerprint) {
        if (firstNorm == null || firstNorm.isBlank()
                || fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        String last = lastNorm == null || lastNorm.isBlank() ? "_" : lastNorm;
        return firstNorm + "|" + last + "|" + fingerprint;
    }

    /** First token / remainder. Single-token names have an empty last name. */
    public static String[] splitDisplayName(String fullName) {
        String n = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) {
            return new String[] {"", ""};
        }
        int space = n.indexOf(' ');
        if (space < 0) {
            return new String[] {n, ""};
        }
        return new String[] {n.substring(0, space), n.substring(space + 1).trim()};
    }
}
