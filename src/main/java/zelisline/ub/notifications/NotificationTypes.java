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
    public static final String RESTOCK_DIGEST = "inventory.restock_digest";
    public static final String ACCOUNT_WELCOME = "account.welcome";
    public static final String ONBOARDING_FILL_SHELF = "onboarding.fill_shelf";
    public static final String ONBOARDING_SIZES_RIGHT = "onboarding.sizes_right";
    public static final String ONBOARDING_MONEY_LOOP = "onboarding.money_loop";
    public static final String ONBOARDING_FIRST_SALE = "onboarding.first_sale";
    public static final String ONBOARDING_GO_LIVE = "onboarding.go_live";
    public static final String ONBOARDING_TEAM_RHYTHM = "onboarding.team_rhythm";
    public static final String ONBOARDING_WEEK_CHECKIN = "onboarding.week_checkin";
    public static final String ONBOARDING_REENGAGE = "onboarding.reengage";
    public static final String ONBOARDING_LOOKALIKE = "onboarding.lookalike";
    public static final String ONBOARDING_CLOSE_SHIFT = "onboarding.close_shift";
    public static final String ONBOARDING_WEB_ORDER = "onboarding.web_order";
    public static final String DRAWOUT_APPROVAL_REQUESTED = "drawout.approval_requested";
    public static final String DRAWOUT_RECORDED = "drawout.recorded";

    private NotificationTypes() {
    }
}
