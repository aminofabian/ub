package zelisline.ub.credits.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** One-line till copy from the last linked basket. Never blocks checkout. */
public final class LastSaleSummaryFormat {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private LastSaleSummaryFormat() {
    }

    public static String hint(List<String> itemNames, Instant soldAt, ZoneId zone) {
        if (itemNames == null || itemNames.isEmpty() || soldAt == null || zone == null) {
            return null;
        }
        String first = itemNames.get(0);
        if (first == null || first.isBlank()) {
            return null;
        }
        return "Usually " + first.trim() + " · last " + DAY.withZone(zone).format(soldAt);
    }
}
