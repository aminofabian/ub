package zelisline.ub.sales.domain;

/** Why the till asked the drawer to open, or that a key did it. */
public enum CashDrawerOpenReason {
    KEY,
    CASH_SALE,
    REPRINT,
    UNKNOWN
}
