package zelisline.ub.notifications;

/** Stable notification type keys (inbox + realtime). */
public final class NotificationTypes {

    public static final String ORDER_RECEIVED = "order.received";
    public static final String ORDER_PAYMENT_RECEIVED = "order.payment_received";
    public static final String ORDER_CONFIRMED = "order.confirmed";
    public static final String ORDER_DISPATCHED = "order.dispatched";
    public static final String ORDER_DELIVERED = "order.delivered";
    public static final String STOREFRONT_ORDER_PLACED = "storefront.order.placed";
    public static final String STOREFRONT_ORDER_PAID = "storefront.order.paid";
    public static final String CREDIT_SALE_REMINDER = "credit_sale.reminder";
    public static final String BACK_IN_STOCK = "catalog.back_in_stock";
    public static final String PRICE_DROP = "promo.price_drop";
    public static final String FLASH_SALE = "promo.flash_sale";
    public static final String WEEKLY_DEALS = "promo.weekly_deals";
    public static final String WIN_BACK = "engagement.win_back";
    public static final String ABANDONED_CART = "insights.abandoned_cart";
    public static final String PEAK_HOURS = "insights.peak_hours";
    public static final String TOP_PRODUCTS = "insights.top_products";

    private NotificationTypes() {
    }
}
