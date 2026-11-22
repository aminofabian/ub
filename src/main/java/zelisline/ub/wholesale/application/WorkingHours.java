package zelisline.ub.wholesale.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * Working hours for a supplier's answer times (SUPPLIER_ENGAGEMENT_DESIGN.md §4.2, D4): Monday to Saturday,
 * 08:00 to 18:00, Nairobi time. Sundays and Kenyan public holidays do not count. A supplier's own closed days
 * are not yet subtracted; that is a known gap, and it makes answer times look slower, never faster.
 */
public final class WorkingHours {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final LocalTime OPEN = LocalTime.of(8, 0);
    static final LocalTime CLOSE = LocalTime.of(18, 0);

    private WorkingHours() {
    }

    /** The working hours between two instants, with public holidays closed. */
    public static double between(Instant from, Instant to) {
        return between(from, to, Set.of());
    }

    /**
     * The working hours between two instants, with public holidays and the given closed dates (a supplier's own
     * closed days) not counting. Zero when {@code to} is not after {@code from} or either is missing.
     */
    public static double between(Instant from, Instant to, Set<LocalDate> ownClosedDays) {
        if (from == null || to == null || !to.isAfter(from)) {
            return 0;
        }
        LocalDateTime start = from.atZone(NAIROBI).toLocalDateTime();
        LocalDateTime end = to.atZone(NAIROBI).toLocalDateTime();
        Set<LocalDate> holidays = new java.util.HashSet<>(
                KenyaPublicHolidays.between(start.toLocalDate(), end.toLocalDate()));
        holidays.addAll(ownClosedDays);
        Duration total = Duration.ZERO;
        for (LocalDate day = start.toLocalDate(); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(day)) {
                continue;
            }
            LocalDateTime open = day.atTime(OPEN);
            LocalDateTime close = day.atTime(CLOSE);
            LocalDateTime from0 = start.isAfter(open) ? start : open;
            LocalDateTime to0 = end.isBefore(close) ? end : close;
            if (to0.isAfter(from0)) {
                total = total.plus(Duration.between(from0, to0));
            }
        }
        return total.toSeconds() / 3600.0;
    }

    /** The median of the hours, to one decimal. Null for an empty list. */
    public static BigDecimal median(List<Double> hours) {
        if (hours == null || hours.isEmpty()) {
            return null;
        }
        List<Double> sorted = hours.stream().sorted().toList();
        int n = sorted.size();
        double mid = n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        return BigDecimal.valueOf(mid).setScale(1, RoundingMode.HALF_UP);
    }
}
