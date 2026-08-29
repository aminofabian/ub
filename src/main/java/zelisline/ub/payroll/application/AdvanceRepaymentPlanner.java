package zelisline.ub.payroll.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.payroll.domain.AdvanceRepaymentMode;
import zelisline.ub.payroll.domain.SalaryAdvance;

/**
 * Computes how much of an outstanding advance may be deducted on a single pay run
 * according to the advance's repayment arrangement.
 */
public final class AdvanceRepaymentPlanner {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private AdvanceRepaymentPlanner() {
    }

    public static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return AdvanceRepaymentMode.FULL_BALANCE;
        }
        return switch (mode.trim().toLowerCase()) {
            case AdvanceRepaymentMode.FULL_BALANCE -> AdvanceRepaymentMode.FULL_BALANCE;
            case AdvanceRepaymentMode.PERCENT_OF_ORIGINAL -> AdvanceRepaymentMode.PERCENT_OF_ORIGINAL;
            case AdvanceRepaymentMode.FIXED_PER_PAY -> AdvanceRepaymentMode.FIXED_PER_PAY;
            case AdvanceRepaymentMode.MANUAL -> AdvanceRepaymentMode.MANUAL;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid repaymentMode: " + mode
            );
        };
    }

    public static void validateValue(String mode, BigDecimal value) {
        if (AdvanceRepaymentMode.FULL_BALANCE.equals(mode) || AdvanceRepaymentMode.MANUAL.equals(mode)) {
            return;
        }
        if (value == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "repaymentValue is required for " + mode
            );
        }
        if (value.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repaymentValue must be positive");
        }
        if (AdvanceRepaymentMode.PERCENT_OF_ORIGINAL.equals(mode) && value.compareTo(HUNDRED) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repaymentValue cannot exceed 100 for percent");
        }
    }

    /**
     * @param manualOverride when true, manual advances participate using full balance (explicit pay confirm)
     */
    public static BigDecimal capForPayRun(SalaryAdvance advance, BigDecimal balance, boolean manualOverride) {
        if (balance == null || balance.signum() <= 0) {
            return ZERO;
        }
        String mode = normalizeMode(advance.getRepaymentMode());
        BigDecimal value = advance.getRepaymentValue();

        return switch (mode) {
            case AdvanceRepaymentMode.MANUAL -> manualOverride ? money(balance) : ZERO;
            case AdvanceRepaymentMode.FULL_BALANCE -> money(balance);
            case AdvanceRepaymentMode.PERCENT_OF_ORIGINAL -> {
                if (value == null) {
                    yield money(balance);
                }
                BigDecimal pct = value.min(HUNDRED).max(ZERO);
                BigDecimal slice = money(
                        advance.getAmount()
                                .multiply(pct)
                                .divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP)
                );
                yield money(balance.min(slice));
            }
            case AdvanceRepaymentMode.FIXED_PER_PAY -> {
                if (value == null) {
                    yield money(balance);
                }
                yield money(balance.min(value));
            }
            default -> money(balance);
        };
    }

    public static BigDecimal capForPayRun(SalaryAdvance advance, BigDecimal balance) {
        return capForPayRun(advance, balance, false);
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
