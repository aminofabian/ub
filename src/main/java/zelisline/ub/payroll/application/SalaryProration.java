package zelisline.ub.payroll.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Calendar-day proration of a monthly salary for mid-month joins.
 *
 * <p>Payable days run from {@code max(periodStart, joinDate)} through month-end.
 * Join date is typically {@code staff_profiles.start_date}, falling back to the
 * salary row's {@code effective_from}.
 */
public final class SalaryProration {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private SalaryProration() {
    }

    /**
     * Prefer employment start date; otherwise use salary effective-from.
     */
    public static LocalDate resolveJoinDate(LocalDate startDate, LocalDate salaryEffectiveFrom) {
        return startDate != null ? startDate : salaryEffectiveFrom;
    }

    /**
     * @param monthlyAmount  contractual monthly salary (must already be money-scaled)
     * @param year           pay period year
     * @param month          pay period month (1–12)
     * @param joinDate       first day employed / salary starts; {@code null} means full month
     * @param prorateEnabled when false, pay full month (override) unless join is after month-end
     */
    public static Result apply(
            BigDecimal monthlyAmount,
            int year,
            int month,
            LocalDate joinDate,
            boolean prorateEnabled
    ) {
        if (monthlyAmount == null || monthlyAmount.signum() <= 0) {
            return Result.none();
        }
        BigDecimal monthly = monthlyAmount.setScale(MONEY_SCALE, MONEY_ROUNDING);
        LocalDate periodStart = LocalDate.of(year, month, 1);
        int daysInMonth = periodStart.lengthOfMonth();
        LocalDate periodEnd = periodStart.withDayOfMonth(daysInMonth);

        if (joinDate != null && joinDate.isAfter(periodEnd)) {
            return new Result(monthly, BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING), null, 0, daysInMonth);
        }
        if (!prorateEnabled) {
            return Result.full(monthly, daysInMonth);
        }
        return prorate(monthly, year, month, joinDate);
    }

    /**
     * @param monthlyAmount contractual monthly salary (must already be money-scaled)
     * @param year          pay period year
     * @param month         pay period month (1–12)
     * @param joinDate      first day employed / salary starts; {@code null} means full month
     */
    public static Result prorate(BigDecimal monthlyAmount, int year, int month, LocalDate joinDate) {
        if (monthlyAmount == null || monthlyAmount.signum() <= 0) {
            return Result.none();
        }
        BigDecimal monthly = monthlyAmount.setScale(MONEY_SCALE, MONEY_ROUNDING);
        LocalDate periodStart = LocalDate.of(year, month, 1);
        int daysInMonth = periodStart.lengthOfMonth();
        LocalDate periodEnd = periodStart.withDayOfMonth(daysInMonth);

        if (joinDate == null || !joinDate.isAfter(periodStart)) {
            return Result.full(monthly, daysInMonth);
        }
        if (joinDate.isAfter(periodEnd)) {
            return new Result(monthly, BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING), null, 0, daysInMonth);
        }

        int payableDays = (int) ChronoUnit.DAYS.between(joinDate, periodEnd) + 1;
        BigDecimal factor = BigDecimal.valueOf(payableDays)
                .divide(BigDecimal.valueOf(daysInMonth), 8, MONEY_ROUNDING);
        BigDecimal payable = monthly
                .multiply(BigDecimal.valueOf(payableDays))
                .divide(BigDecimal.valueOf(daysInMonth), MONEY_SCALE, MONEY_ROUNDING);
        return new Result(monthly, payable, factor, payableDays, daysInMonth);
    }

    public record Result(
            BigDecimal monthlyAmount,
            BigDecimal payableAmount,
            /** Ratio payableDays/daysInMonth; null when no salary or full month. */
            BigDecimal prorationFactor,
            int payableDays,
            int daysInMonth
    ) {
        static Result none() {
            BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
            return new Result(zero, zero, null, 0, 0);
        }

        static Result full(BigDecimal monthly, int daysInMonth) {
            return new Result(monthly, monthly, null, daysInMonth, daysInMonth);
        }

        public boolean isProrated() {
            return prorationFactor != null;
        }
    }
}
