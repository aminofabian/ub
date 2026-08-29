package zelisline.ub.messaging.domain;

/**
 * Kind of {@link SmsCreditLedgerEntry} movement. Negative deltas are spends,
 * positive deltas are credits to the account (SMS_CREDITS_SCOPE.md §9).
 */
public enum SmsCreditLedgerKind {
    INCLUDED_SPEND,
    PURCHASED_SPEND,
    PURCHASE,
    GRANT,
    REFUND,
    CYCLE_RESET
}
