package zelisline.ub.serviceops.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Periods when a staff member cannot be booked: a day off, leave, a long break. Stored on the
 * staff profile as {@code fromEpochSecond:toEpochSecond:reason;...}.
 */
public final class StaffTimeOff {

    public static final int MAX_PERIODS = 30;

    private StaffTimeOff() {
    }

    public record Period(Instant from, Instant to, String reason) {
        public boolean overlaps(Instant start, Instant end) {
            return from.isBefore(end) && to.isAfter(start);
        }
    }

    public static List<Period> decode(String stored) {
        List<Period> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        for (String part : stored.split(";")) {
            String[] bits = part.split(":", 3);
            if (bits.length < 2) {
                continue;
            }
            try {
                out.add(new Period(Instant.ofEpochSecond(Long.parseLong(bits[0])),
                        Instant.ofEpochSecond(Long.parseLong(bits[1])), bits.length > 2 ? bits[2] : ""));
            } catch (NumberFormatException ex) {
                // Skip a damaged entry.
            }
        }
        out.sort(Comparator.comparing(Period::from));
        return out;
    }

    public static String encode(List<Period> periods) {
        if (periods == null || periods.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Period period : periods) {
            if (out.length() > 0) {
                out.append(';');
            }
            String reason = period.reason() == null ? "" : period.reason().replaceAll("[;:\\p{Cntrl}]", " ").trim();
            out.append(period.from().getEpochSecond()).append(':').append(period.to().getEpochSecond())
                    .append(':').append(reason.length() > 40 ? reason.substring(0, 40) : reason);
        }
        return out.toString();
    }
}
