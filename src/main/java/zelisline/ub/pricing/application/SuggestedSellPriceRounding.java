package zelisline.ub.pricing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounds margin-based shelf-price suggestions to shopper-friendly increments:
 * under 100 → nearest 5; 100 and above → nearest 10.
 */
public final class SuggestedSellPriceRounding {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal THRESHOLD = new BigDecimal("100");
    private static final BigDecimal STEP_BELOW_THRESHOLD = new BigDecimal("5");
    private static final BigDecimal STEP_AT_OR_ABOVE_THRESHOLD = new BigDecimal("10");

    private SuggestedSellPriceRounding() {
    }

    public static BigDecimal round(BigDecimal rawSuggested) {
        if (rawSuggested == null) {
            return null;
        }
        BigDecimal value = rawSuggested.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            return value.max(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY));
        }
        BigDecimal step = value.compareTo(THRESHOLD) < 0 ? STEP_BELOW_THRESHOLD : STEP_AT_OR_ABOVE_THRESHOLD;
        return value
                .divide(step, 0, RoundingMode.HALF_UP)
                .multiply(step)
                .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }
}
