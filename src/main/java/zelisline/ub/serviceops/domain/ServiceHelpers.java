package zelisline.ub.serviceops.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The staff who share a service line with its performer, and each one's share of it. Stored on
 * the line as {@code staffProfileId:percent;staffProfileId:percent}.
 */
public final class ServiceHelpers {

    public static final int MAX_HELPERS = 5;

    private ServiceHelpers() {
    }

    /** One helper: a staff profile and the percentage of the line that is theirs (above 0). */
    public record Helper(
            String staffProfileId,
            BigDecimal sharePercent,
            /** What they are paid for this service, set at the till; null means by their plan. */
            BigDecimal amount
    ) {
        public Helper(String staffProfileId, BigDecimal sharePercent) {
            this(staffProfileId, sharePercent, null);
        }
    }

    public static String encode(List<Helper> helpers) {
        if (helpers == null || helpers.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Helper helper : helpers) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(helper.staffProfileId()).append(':')
                    .append(helper.sharePercent().setScale(2, RoundingMode.HALF_UP).toPlainString());
            if (helper.amount() != null) {
                out.append(':').append(helper.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
        }
        return out.toString();
    }

    /** Unreadable text decodes to no helpers rather than failing a sale or a report. */
    public static List<Helper> decode(String stored) {
        List<Helper> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        for (String part : stored.split(";")) {
            String[] bits = part.split(":");
            if (bits.length < 2 || bits[0].isBlank()) {
                continue;
            }
            try {
                out.add(new Helper(bits[0].trim(), new BigDecimal(bits[1].trim()),
                        bits.length > 2 && !bits[2].isBlank() ? new BigDecimal(bits[2].trim()) : null));
            } catch (NumberFormatException ex) {
                // Skip a damaged entry.
            }
        }
        return out;
    }

    /** The share left for the performer once every helper has theirs. */
    public static BigDecimal totalPercent(List<Helper> helpers) {
        BigDecimal total = BigDecimal.ZERO;
        for (Helper helper : helpers) {
            total = total.add(helper.sharePercent());
        }
        return total;
    }
}
