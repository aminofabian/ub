package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import zelisline.ub.sales.domain.SaleItem;

/**
 * Splits a sale's net revenue between goods ({@code SALES_REVENUE}), services
 * ({@code SERVICE_REVENUE}) and rooms ({@code ROOM_REVENUE}) for the sale and void journals
 * (ADR 0014, ADR 0028).
 *
 * <p>Service and room lines carry no tax today, so the sum of each kind's line totals is already
 * net and the goods share is the remainder. A sale with no service and no room lines keeps posting
 * one {@code SALES_REVENUE} line exactly as before, including when that amount is zero.</p>
 */
final class SaleRevenueSplit {

    private static final int MONEY_SCALE = 2;

    private SaleRevenueSplit() {
    }

    record Split(BigDecimal goods, BigDecimal services, BigDecimal rooms) {

        /** Post the goods line unless everything on the sale was a service or a room night. */
        boolean creditGoods() {
            return (services.signum() == 0 && rooms.signum() == 0) || goods.signum() != 0;
        }
    }

    static Split of(BigDecimal netRevenue, BigDecimal serviceTotal, BigDecimal roomTotal) {
        BigDecimal net = netRevenue.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal services = scaleOrZero(serviceTotal);
        BigDecimal rooms = scaleOrZero(roomTotal);
        if (services.signum() < 0 || rooms.signum() < 0) {
            throw new IllegalStateException("Service " + services + " or room " + rooms + " revenue is negative");
        }
        BigDecimal nonGoods = services.add(rooms);
        if (nonGoods.compareTo(net) > 0) {
            throw new IllegalStateException(
                    "Service + room revenue " + nonGoods + " exceeds net revenue " + net);
        }
        return new Split(net.subtract(nonGoods), services, rooms);
    }

    private static BigDecimal scaleOrZero(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal serviceTotal(List<SaleItem> items) {
        return totalWhere(items, SaleItem::isService);
    }

    static BigDecimal roomTotal(List<SaleItem> items) {
        return totalWhere(items, SaleItem::isRoom);
    }

    private static BigDecimal totalWhere(List<SaleItem> items, java.util.function.Predicate<SaleItem> keep) {
        BigDecimal total = BigDecimal.ZERO;
        for (SaleItem item : items) {
            if (keep.test(item) && item.getLineTotal() != null) {
                total = total.add(item.getLineTotal());
            }
        }
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
