package zelisline.ub.sales.application;

import java.math.BigDecimal;

/**
 * Same rule as Shoppers {@code isWholesaleShaped}: at least two tills and a
 * basket at least 5× the shop median, with a KSh 2,000 floor.
 */
public final class WholesaleShapedMath {

    public static final BigDecimal BASKET_FLOOR = new BigDecimal("2000");
    public static final int MIN_SALES = 2;

    private WholesaleShapedMath() {
    }

    public static boolean matches(BigDecimal avgBasket, long saleCount, BigDecimal medianBasket) {
        if (saleCount < MIN_SALES || avgBasket == null) {
            return false;
        }
        BigDecimal median = medianBasket == null ? BigDecimal.ZERO : medianBasket;
        BigDecimal fiveX = median.multiply(new BigDecimal("5"));
        BigDecimal floor = fiveX.compareTo(BASKET_FLOOR) > 0 ? fiveX : BASKET_FLOOR;
        return avgBasket.compareTo(floor) >= 0;
    }

    public static BigDecimal avgBasket(BigDecimal totalSpend, long saleCount) {
        if (saleCount <= 0 || totalSpend == null) {
            return BigDecimal.ZERO;
        }
        return totalSpend.divide(BigDecimal.valueOf(saleCount), 2, java.math.RoundingMode.HALF_UP);
    }
}
