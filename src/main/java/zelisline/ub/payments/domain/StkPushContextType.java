package zelisline.ub.payments.domain;

public enum StkPushContextType {
    WEB_ORDER,
    POS_PAYMENT,
    /** Public storefront cart / checkout awaiting Buy Goods (no order yet). */
    STOREFRONT_CART,
    WALLET_INTENT,
    /** Customer tab / AR paydown via public phone portal or staff STK. */
    CREDIT_AR,
    /** Remote grocery / delivery invoice awaiting M-Pesa. */
    GROCERY_INVOICE,
    /** Kenyan TLD purchase — Palmart platform till; settles via DomainPurchaseService.markPaid. */
    DOMAIN_ORDER,
    /** Storefront airtime purchase awaiting the shopper's payment before dispatch. */
    AIRTIME_ORDER,
    /** Merchant funding their own Kiosk Pay wallet (airtime float). */
    KIOSK_PAY_TOPUP
}
