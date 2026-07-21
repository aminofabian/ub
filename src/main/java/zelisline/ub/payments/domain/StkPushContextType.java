package zelisline.ub.payments.domain;

public enum StkPushContextType {
    WEB_ORDER,
    POS_PAYMENT,
    WALLET_INTENT,
    /** Customer tab / AR paydown via public phone portal or staff STK. */
    CREDIT_AR
}
