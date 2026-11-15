package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.restaurant.domain.OrderBill;
import zelisline.ub.restaurant.domain.RestaurantSettings;

/**
 * What a bill adds on top of its lines (ADR 0020, amendment 3): a service charge on a table's
 * order, and — once per order — a packaging charge on a takeaway or delivery and the delivery fee.
 * Worked out the same way when a bill is shown and when it is paid, so the figure on screen is
 * the figure charged.
 */
public final class BillCharges {

    private BillCharges() {
    }

    public record Charges(BigDecimal serviceCharge, BigDecimal packagingCharge, BigDecimal deliveryFee) {

        public static final Charges NONE = new Charges(zero(), zero(), zero());

        public BigDecimal total() {
            return serviceCharge.add(packagingCharge).add(deliveryFee);
        }
    }

    /**
     * @param linesTotal what the bill's lines come to
     * @param bills every bill of the order, this one included
     */
    public static Charges forBill(
            OrderTicketService.Order order, OrderBill bill, List<OrderBill> bills, BigDecimal linesTotal,
            RestaurantSettings settings) {
        if (!OrderBill.OPEN.equals(bill.getStatus())) {
            // A closed bill keeps what it was charged, whatever the settings say now.
            return new Charges(money(bill.getServiceCharge()), money(bill.getPackagingCharge()),
                    money(bill.getDeliveryFee()));
        }
        BigDecimal service = zero();
        if (OrderTicketService.CHANNEL_DINE_IN.equals(order.channel()) && settings.getServiceChargePercent() != null
                && settings.getServiceChargePercent().signum() > 0) {
            // Whole shillings: nobody is handed a bill ending in cents because of a percentage.
            service = linesTotal.multiply(settings.getServiceChargePercent())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
        }
        boolean takenAway = OrderTicketService.CHANNEL_TAKEAWAY.equals(order.channel())
                || OrderTicketService.CHANNEL_DELIVERY.equals(order.channel());
        // The once-per-order charges ride on the first bill that can carry them: none has been
        // charged on a paid bill yet, and this is the lowest-numbered open bill.
        boolean carriesOnce = takenAway && bills.stream().noneMatch(b ->
                b.holdsItsLines() && !b.getId().equals(bill.getId())
                        && (OrderBill.PAID.equals(b.getStatus())
                                || (OrderBill.OPEN.equals(b.getStatus()) && b.getBillNo() < bill.getBillNo())));
        BigDecimal packaging = zero();
        BigDecimal delivery = zero();
        if (carriesOnce) {
            if (settings.getPackagingCharge() != null && settings.getPackagingCharge().signum() > 0) {
                packaging = money(settings.getPackagingCharge());
            }
            if (OrderTicketService.CHANNEL_DELIVERY.equals(order.channel())) {
                BigDecimal fee = order.deliveryFee() != null ? order.deliveryFee() : settings.getDeliveryFee();
                if (fee != null && fee.signum() > 0) {
                    delivery = money(fee);
                }
            }
        }
        return new Charges(service, packaging, delivery);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? zero() : value.setScale(2, RoundingMode.HALF_UP);
    }
}
