package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import zelisline.ub.sales.domain.SaleItem;

/**
 * Splits a sale's net revenue between goods ({@code SALES_REVENUE}) and services
 * ({@code SERVICE_REVENUE}) for the sale, void and refund journals (ADR 0014).
 *
 * <p>Service lines carry no tax today, so the sum of their line totals is already net and the
 * goods share is simply the remainder. A sale with no service lines keeps posting one
 * {@code SALES_REVENUE} line exactly as before, including when that amount is zero.</p>
 */
final class SaleRevenueSplit {

    private static final int MONEY_SCALE = 2;

    private SaleRevenueSplit() {
    }

    record Split(BigDecimal goods, BigDecimal services) {

        /** Post the goods line unless everything on the sale was a service. */
        boolean creditGoods() {
            return services.signum() == 0 || goods.signum() != 0;
        }
    }

    static Split of(BigDecimal netRevenue, BigDecimal serviceTotal) {
        BigDecimal net = netRevenue.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal services = serviceTotal == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : serviceTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (services.signum() < 0 || services.compareTo(net) > 0) {
            throw new IllegalStateException("Service revenue " + services + " exceeds net revenue " + net);
        }
        return new Split(net.subtract(services), services);
    }

    static BigDecimal serviceTotal(List<SaleItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (SaleItem item : items) {
            if (item.isService() && item.getLineTotal() != null) {
                total = total.add(item.getLineTotal());
            }
        }
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
