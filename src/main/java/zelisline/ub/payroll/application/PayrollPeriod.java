package zelisline.ub.payroll.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Pay periods run from the 25th of the previous calendar month through the 24th
 * of the labeled month (inclusive).
 *
 * <p>Example: September 2026 = 2026-08-25 … 2026-09-24. Salary for that run starts
 * counting on the 25th; a join on/after the 25th falls into the next period.
 */
public final class PayrollPeriod {

    /** First day of each pay cycle (previous calendar month). */
    public static final int CYCLE_START_DAY = 25;

    /** Last day of each pay cycle (labeled calendar month). */
    public static final int CYCLE_END_DAY = 24;

    private PayrollPeriod() {
    }

    public record Bounds(LocalDate start, LocalDate end, int dayCount) {
    }

    public static Bounds bounds(int year, int month) {
        YearMonth labeled = YearMonth.of(year, month);
        YearMonth previous = labeled.minusMonths(1);
        LocalDate start = previous.atDay(Math.min(CYCLE_START_DAY, previous.lengthOfMonth()));
        LocalDate end = labeled.atDay(Math.min(CYCLE_END_DAY, labeled.lengthOfMonth()));
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        return new Bounds(start, end, days);
    }

    /** Salary rate snapshot date for the period (cycle end). */
    public static LocalDate asOf(int year, int month) {
        return bounds(year, month).end();
    }
}
