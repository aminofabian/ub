package zelisline.ub.payroll.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Calendar-month pay periods unlock on the 25th of that month.
 *
 * <p>Until then, the labeled month shows zero payable salary. From the 25th onward
 * (and for all past months), salaries are released — full, half, or prorated by
 * join-pay mode.
 */
public final class PayrollPeriod {

    /** Day of the labeled month when that month's salaries become payable. */
    public static final int SALARY_UNLOCK_DAY = 25;

    private PayrollPeriod() {
    }

    public record Bounds(LocalDate start, LocalDate end, int dayCount) {
    }

    /** Calendar month bounds for the labeled pay period. */
    public static Bounds bounds(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        return new Bounds(start, end, days);
    }

    /** Salary rate snapshot = last day of the labeled month. */
    public static LocalDate asOf(int year, int month) {
        return bounds(year, month).end();
    }

    public static LocalDate unlockDate(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        return ym.atDay(Math.min(SALARY_UNLOCK_DAY, ym.lengthOfMonth()));
    }

    /** True once today is on/after the 25th of the labeled month (or the month is in the past). */
    public static boolean isReleased(int year, int month, LocalDate today) {
        return !today.isBefore(unlockDate(year, month));
    }
}
