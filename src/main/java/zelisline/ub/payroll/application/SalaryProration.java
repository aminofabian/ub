package zelisline.ub.payroll.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Calendar-day proration of a monthly salary for mid-cycle joins.
 *
 * <p>Pay periods run {@link PayrollPeriod#CYCLE_START_DAY} of the previous month
 * through {@link PayrollPeriod#CYCLE_END_DAY} of the labeled month. Payable days
 * run from {@code max(periodStart, joinDate)} through period end.
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
     * @param year           pay period year (labeled month)
     * @param month          pay period month (1–12)
     * @param joinDate       first day employed / salary starts; {@code null} means full period
     * @param prorateEnabled when false, pay full period unless join is after period end
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
        PayrollPeriod.Bounds period = PayrollPeriod.bounds(year, month);

        if (joinDate != null && joinDate.isAfter(period.end())) {
            return new Result(
                    monthly,
                    BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING),
                    null,
                    0,
                    period.dayCount()
            );
        }
        if (!prorateEnabled) {
            return Result.full(monthly, period.dayCount());
        }
        return prorate(monthly, period, joinDate);
    }

    /**
     * @param monthlyAmount contractual monthly salary (must already be money-scaled)
     * @param year          pay period year (labeled month)
     * @param month         pay period month (1–12)
     * @param joinDate      first day employed / salary starts; {@code null} means full period
     */
    public static Result prorate(BigDecimal monthlyAmount, int year, int month, LocalDate joinDate) {
        if (monthlyAmount == null || monthlyAmount.signum() <= 0) {
            return Result.none();
        }
        BigDecimal monthly = monthlyAmount.setScale(MONEY_SCALE, MONEY_ROUNDING);
        return prorate(monthly, PayrollPeriod.bounds(year, month), joinDate);
    }

    private static Result prorate(BigDecimal monthly, PayrollPeriod.Bounds period, LocalDate joinDate) {
        int daysInPeriod = period.dayCount();
        LocalDate periodStart = period.start();
        LocalDate periodEnd = period.end();

        if (joinDate == null || !joinDate.isAfter(periodStart)) {
            return Result.full(monthly, daysInPeriod);
        }
        if (joinDate.isAfter(periodEnd)) {
            return new Result(
                    monthly,
                    BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING),
                    null,
                    0,
                    daysInPeriod
            );
        }

        int payableDays = (int) ChronoUnit.DAYS.between(joinDate, periodEnd) + 1;
        BigDecimal factor = BigDecimal.valueOf(payableDays)
                .divide(BigDecimal.valueOf(daysInPeriod), 8, MONEY_ROUNDING);
        BigDecimal payable = monthly
                .multiply(BigDecimal.valueOf(payableDays))
                .divide(BigDecimal.valueOf(daysInPeriod), MONEY_SCALE, MONEY_ROUNDING);
        return new Result(monthly, payable, factor, payableDays, daysInPeriod);
    }

    public record Result(
            BigDecimal monthlyAmount,
            BigDecimal payableAmount,
            /** Ratio payableDays/daysInPeriod; null when no salary or full period. */
            BigDecimal prorationFactor,
            int payableDays,
            /** Length of the 25th→24th pay cycle in days. */
            int daysInMonth
    ) {
        static Result none() {
            BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
            return new Result(zero, zero, null, 0, 0);
        }

        static Result full(BigDecimal monthly, int daysInPeriod) {
            return new Result(monthly, monthly, null, daysInPeriod, daysInPeriod);
        }

        public boolean isProrated() {
            return prorationFactor != null;
        }
    }
}
