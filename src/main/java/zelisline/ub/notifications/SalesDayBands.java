package zelisline.ub.notifications;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Today's takings, in steps of KES 5,000. A sale that crosses one step is announced.
 * A sale that leaps several steps is announced once, at the step it landed on, so a
 * large receipt does not fire a stack of notices.
 */
public final class SalesDayBands {

    public static final BigDecimal STEP = new BigDecimal("5000");
    /** How many steps one sale may announce before they collapse into the highest. */
    public static final int MAX_PER_SALE = 4;

    private SalesDayBands() {
    }

    public static List<BigDecimal> crossed(BigDecimal todayAfter, BigDecimal thisSale) {
        if (todayAfter == null || thisSale == null || todayAfter.signum() <= 0) {
            return List.of();
        }
        BigDecimal sale = thisSale.signum() < 0 ? BigDecimal.ZERO : thisSale;
        BigDecimal before = todayAfter.subtract(sale);
        if (before.signum() < 0) {
            before = BigDecimal.ZERO;
        }
        long from = before.divideToIntegralValue(STEP).longValue();
        long to = todayAfter.divideToIntegralValue(STEP).longValue();
        if (to <= from || to < 1) {
            return List.of();
        }
        if (to - from > MAX_PER_SALE) {
            return List.of(STEP.multiply(BigDecimal.valueOf(to)));
        }
        List<BigDecimal> bands = new ArrayList<>();
        for (long step = from + 1; step <= to; step++) {
            bands.add(STEP.multiply(BigDecimal.valueOf(step)));
        }
        return List.copyOf(bands);
    }
}
