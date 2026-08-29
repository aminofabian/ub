package zelisline.ub.payroll.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Allocates a repayment pool across outstanding advances oldest-first, respecting per-advance
 * caps from {@link AdvanceRepaymentPlanner}.
 */
public final class AdvanceRepaymentAllocator {

    private static final int MONEY_SCALE = 2;

    public record AdvanceBalance(String advanceId, BigDecimal balance, BigDecimal capThisRun) {
        public AdvanceBalance(String advanceId, BigDecimal balance) {
            this(advanceId, balance, balance);
        }
    }

    public record Allocation(String advanceId, BigDecimal amount) {
    }

    private AdvanceRepaymentAllocator() {
    }

    public static List<Allocation> allocate(BigDecimal pool, List<AdvanceBalance> advances) {
        BigDecimal remaining = money(pool);
        List<Allocation> allocations = new ArrayList<>();
        if (remaining.signum() <= 0 || advances == null || advances.isEmpty()) {
            return allocations;
        }
        for (AdvanceBalance advance : advances) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal balance = money(advance.balance());
            if (balance.signum() <= 0) {
                continue;
            }
            BigDecimal cap = advance.capThisRun() != null
                    ? money(advance.capThisRun())
                    : balance;
            if (cap.signum() <= 0) {
                continue;
            }
            BigDecimal applicable = balance.min(cap);
            BigDecimal applied = remaining.min(applicable);
            if (applied.signum() <= 0) {
                continue;
            }
            allocations.add(new Allocation(advance.advanceId(), applied));
            remaining = remaining.subtract(applied);
        }
        return allocations;
    }

    public static BigDecimal totalAllocated(List<Allocation> allocations) {
        return allocations.stream()
                .map(Allocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
