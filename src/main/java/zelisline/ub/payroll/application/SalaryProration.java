package zelisline.ub.payroll.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import zelisline.ub.payroll.domain.JoinPayMode;

/**
 * Join-month pay for a calendar month: full, half, or day-prorated.
 *
 * <p>Callers must also gate on {@link PayrollPeriod#isReleased} — before the 25th
 * of the labeled month, payable salary stays zero for everyone.
 */
public final class SalaryProration {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private SalaryProration() {
    }

    public static LocalDate resolveJoinDate(LocalDate startDate, LocalDate salaryEffectiveFrom) {
        return startDate != null ? startDate : salaryEffectiveFrom;
    }

    /**
     * @param monthlyAmount contractual monthly salary
     * @param year          labeled pay month year
     * @param month         labeled pay month (1–12)
     * @param joinDate      employment / salary start; null → treat as full month
     * @param joinPayMode   {@link JoinPayMode#FULL}, {@link JoinPayMode#HALF}, or {@link JoinPayMode#PRORATE}
     */
    public static Result apply(
            BigDecimal monthlyAmount,
            int year,
            int month,
            LocalDate joinDate,
            String joinPayMode
    ) {
        if (monthlyAmount == null || monthlyAmount.signum() <= 0) {
            return Result.none();
        }
        BigDecimal monthly = monthlyAmount.setScale(MONEY_SCALE, MONEY_ROUNDING);
        PayrollPeriod.Bounds period = PayrollPeriod.bounds(year, month);
        String mode = JoinPayMode.normalize(joinPayMode);

        if (joinDate != null && joinDate.isAfter(period.end())) {
            return new Result(monthly, zero(), null, 0, period.dayCount(), mode);
        }

        boolean midMonth = joinDate != null && joinDate.isAfter(period.start());
        if (!midMonth) {
            return Result.full(monthly, period.dayCount(), mode);
        }

        return switch (mode) {
            case JoinPayMode.FULL -> Result.full(monthly, period.dayCount(), mode);
            case JoinPayMode.HALF -> {
                BigDecimal half = monthly.divide(BigDecimal.valueOf(2), MONEY_SCALE, MONEY_ROUNDING);
                yield new Result(
                        monthly,
                        half,
                        new BigDecimal("0.50000000"),
                        (period.dayCount() + 1) / 2,
                        period.dayCount(),
                        mode
                );
            }
            case JoinPayMode.PRORATE -> prorate(monthly, period, joinDate, mode);
            default -> Result.full(monthly, period.dayCount(), mode);
        };
    }

    /** @deprecated use {@link #apply(BigDecimal, int, int, LocalDate, String)} */
    public static Result apply(
            BigDecimal monthlyAmount,
            int year,
            int month,
            LocalDate joinDate,
            boolean prorateEnabled
    ) {
        return apply(
                monthlyAmount,
                year,
                month,
                joinDate,
                JoinPayMode.fromLegacyProrateFlag(prorateEnabled)
        );
    }

    public static Result prorate(BigDecimal monthlyAmount, int year, int month, LocalDate joinDate) {
        return apply(monthlyAmount, year, month, joinDate, JoinPayMode.PRORATE);
    }

    private static Result prorate(
            BigDecimal monthly,
            PayrollPeriod.Bounds period,
            LocalDate joinDate,
            String mode
    ) {
        int daysInPeriod = period.dayCount();
        if (joinDate.isAfter(period.end())) {
            return new Result(monthly, zero(), null, 0, daysInPeriod, mode);
        }
        int payableDays = (int) ChronoUnit.DAYS.between(joinDate, period.end()) + 1;
        BigDecimal factor = BigDecimal.valueOf(payableDays)
                .divide(BigDecimal.valueOf(daysInPeriod), 8, MONEY_ROUNDING);
        BigDecimal payable = monthly
                .multiply(BigDecimal.valueOf(payableDays))
                .divide(BigDecimal.valueOf(daysInPeriod), MONEY_SCALE, MONEY_ROUNDING);
        return new Result(monthly, payable, factor, payableDays, daysInPeriod, mode);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    public record Result(
            BigDecimal monthlyAmount,
            BigDecimal payableAmount,
            BigDecimal prorationFactor,
            int payableDays,
            int daysInMonth,
            String joinPayMode
    ) {
        static Result none() {
            return new Result(zero(), zero(), null, 0, 0, JoinPayMode.HALF);
        }

        static Result full(BigDecimal monthly, int daysInPeriod, String mode) {
            return new Result(monthly, monthly, null, daysInPeriod, daysInPeriod, mode);
        }

        public boolean isProrated() {
            return prorationFactor != null;
        }

        /** Zero payable while the month has not unlocked on the 25th. */
        public Result locked() {
            return new Result(monthlyAmount, zero(), null, 0, daysInMonth, joinPayMode);
        }
    }
}
