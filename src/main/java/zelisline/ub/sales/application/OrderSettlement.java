package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Marks a sale as the settlement of a restaurant bill (ADR 0020). The order already exists and
 * its lines were priced when they were sent to the kitchen, so the sale must take those prices
 * and that wording as they stand rather than pricing the dishes again from today's menu, and
 * must not write a second order. Bound to the calling thread for the length of one call only,
 * as {@code EarningsApproval} is.
 */
public final class OrderSettlement {

    /**
     * A dish exactly as it was ordered.
     *
     * @param taxRateId the dish's tax rate now, or null; tax is worked out at the time of sale
     */
    public record FrozenDish(
            String menuItemId, String name, String label, String modifiersJson, String taxRateId,
            BigDecimal unitPrice, String note) {
    }

    /**
     * @param dishesByLineIndex the dish behind each {@code DISH} line of the sale request, by its position
     * @param tipRecipients staff profiles a tip on this sale may be for (whoever served the order)
     */
    public record Context(String orderId, Map<Integer, FrozenDish> dishesByLineIndex, Set<String> tipRecipients) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private OrderSettlement() {
    }

    public static <T> T settling(Context context, Supplier<T> sale) {
        CURRENT.set(context);
        try {
            return sale.get();
        } finally {
            CURRENT.remove();
        }
    }

    public static Context current() {
        return CURRENT.get();
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }
}
