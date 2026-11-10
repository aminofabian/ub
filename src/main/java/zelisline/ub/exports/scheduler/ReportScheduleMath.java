package zelisline.ub.exports.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.WeekFields;

/** Pure scheduling math for recurring report delivery. Business-timezone dates in, keys out. */
public final class ReportScheduleMath {

    public enum Frequency { DAILY, WEEKLY, MONTHLY }

    private ReportScheduleMath() {
    }

    /** True while the local clock sits in [sendTime, sendTime + catchUpMinutes] and the day matches. */
    public static boolean isDue(
            Frequency freq, LocalDateTime now, LocalTime sendTime,
            Integer dayOfWeek, Integer dayOfMonth, int catchUpMinutes) {
        int elapsed = minutesOfDay(now.toLocalTime()) - minutesOfDay(sendTime);
        boolean timeDue = elapsed >= 0 && elapsed <= catchUpMinutes;
        if (!timeDue) {
            return false;
        }
        return switch (freq) {
            case DAILY -> true;
            case WEEKLY -> dayOfWeek != null && now.getDayOfWeek().getValue() == clampDow(dayOfWeek);
            case MONTHLY -> matchesDayOfMonth(now.toLocalDate(), dayOfMonth == null ? 1 : dayOfMonth);
        };
    }

    /** Stable key for the occurrence being delivered; stored as last_sent_period to dedupe. */
    public static String periodKey(Frequency freq, LocalDate today) {
        return switch (freq) {
            case DAILY -> today.minusDays(1).toString();
            case WEEKLY -> {
                LocalDate end = today.minusDays(1);
                WeekFields wf = WeekFields.ISO;
                yield end.get(wf.weekBasedYear()) + "-W"
                        + String.format("%02d", end.get(wf.weekOfWeekBasedYear()));
            }
            case MONTHLY -> YearMonth.from(today).minusMonths(1).toString();
        };
    }

    /** The [from, to] business-date window the report should cover for this occurrence. */
    public static LocalDate[] window(Frequency freq, LocalDate today) {
        return switch (freq) {
            case DAILY -> {
                LocalDate y = today.minusDays(1);
                yield new LocalDate[] {y, y};
            }
            case WEEKLY -> {
                LocalDate to = today.minusDays(1);
                yield new LocalDate[] {to.minusDays(6), to};
            }
            case MONTHLY -> {
                YearMonth ym = YearMonth.from(today).minusMonths(1);
                yield new LocalDate[] {ym.atDay(1), ym.atEndOfMonth()};
            }
        };
    }

    private static boolean matchesDayOfMonth(LocalDate today, int dayOfMonth) {
        int dom = Math.min(Math.max(dayOfMonth, 1), today.lengthOfMonth());
        return today.getDayOfMonth() == dom;
    }

    private static int clampDow(int dow) {
        return Math.min(Math.max(dow, 1), 7);
    }

    private static int minutesOfDay(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
