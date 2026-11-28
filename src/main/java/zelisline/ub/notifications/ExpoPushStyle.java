package zelisline.ub.notifications;

/**
 * How an owner-phone notice is dressed: which Android channel, which tint, and the
 * kind the admin app uses to open the right tab. Channels must exist on the phone
 * before the notice arrives.
 */
public record ExpoPushStyle(String kind, String channelId, String color) {

    public static final String CHANNEL_SHIFT = "shop-shift";
    public static final String CHANNEL_MONEY = "shop-money";
    public static final String CHANNEL_SUPPLY = "shop-supply";
    public static final String CHANNEL_ORDER = "shop-order";
    public static final String CHANNEL_ALERT = "shop-alert";
    public static final String CHANNEL_DEFAULT = "shop";

    public static ExpoPushStyle of(String type) {
        if (type == null) {
            return new ExpoPushStyle("shop", CHANNEL_DEFAULT, "#F08A24");
        }
        return switch (type) {
            case NotificationTypes.SHIFT_OPENED, NotificationTypes.SHIFT_CLOSED ->
                    new ExpoPushStyle(type, CHANNEL_SHIFT, "#2f7a21");
            case NotificationTypes.SALES_MILESTONE, NotificationTypes.CREDIT_PAYMENT ->
                    new ExpoPushStyle(type, CHANNEL_MONEY, "#F08A24");
            case NotificationTypes.SUPPLY_POSTED ->
                    new ExpoPushStyle(type, CHANNEL_SUPPLY, "#0b5cab");
            case NotificationTypes.STOREFRONT_ORDER_PLACED, NotificationTypes.STOREFRONT_ORDER_PAID,
                    NotificationTypes.ORDER_RECEIVED, NotificationTypes.ORDER_PAYMENT_RECEIVED ->
                    new ExpoPushStyle(type, CHANNEL_ORDER, "#0b5cab");
            case NotificationTypes.SALE_VOIDED, NotificationTypes.DRAWOUT_RECORDED,
                    NotificationTypes.DRAWOUT_APPROVAL_REQUESTED ->
                    new ExpoPushStyle(type, CHANNEL_ALERT, "#b42318");
            default -> new ExpoPushStyle(type, CHANNEL_DEFAULT, "#F08A24");
        };
    }
}
