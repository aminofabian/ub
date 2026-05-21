package zelisline.ub.notifications;

/** Outbox event types processed by {@link zelisline.ub.notifications.application.NotificationEventProcessor}. */
public final class NotificationEventTypes {

    public static final String WEB_ORDER_PLACED = "WEB_ORDER_PLACED";
    public static final String WEB_ORDER_PAID = "WEB_ORDER_PAID";
    public static final String WEB_ORDER_CONFIRMED = "WEB_ORDER_CONFIRMED";
    public static final String WEB_ORDER_DISPATCHED = "WEB_ORDER_DISPATCHED";
    public static final String WEB_ORDER_DELIVERED = "WEB_ORDER_DELIVERED";
    public static final String PROMO_CAMPAIGN_RUN = "PROMO_CAMPAIGN_RUN";
    public static final String STOCK_LOW = "STOCK_LOW";
    public static final String STOCK_LOW_BATCH_FLUSH = "STOCK_LOW_BATCH_FLUSH";
    public static final String DAILY_SALES_DIGEST = "DAILY_SALES_DIGEST";
    public static final String WIN_BACK = "WIN_BACK";
    public static final String ABANDONED_CART_DIGEST = "ABANDONED_CART_DIGEST";
    public static final String PEAK_HOURS_DIGEST = "PEAK_HOURS_DIGEST";
    public static final String TOP_PRODUCTS_DIGEST = "TOP_PRODUCTS_DIGEST";
    public static final String PRICE_DROP = "PRICE_DROP";
    public static final String BACK_IN_STOCK = "BACK_IN_STOCK";

    private NotificationEventTypes() {
    }
}
