package zelisline.ub.opsalerts.domain;

public enum OpsAlertType {
    WEB_ORDER,
    SHIFT_OPENED,
    SHIFT_CLOSED,
    SUPPLY_POSTED,
    CREDIT_PAYMENT,
    RESTOCK_DIGEST,
    /** Merchant onboarding sequence (M4 congrats / re-engage / week check-in). */
    ONBOARDING
}
