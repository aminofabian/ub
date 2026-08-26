package zelisline.ub.integrations.metacapi.domain;

/**
 * Delivery lifecycle of a durable Meta CAPI event row.
 *
 * <p>{@code failed} rows are re-attempted by {@code MetaCapiRetryScheduler} until
 * {@code attemptCount} reaches the configured max. Auth/config failures are
 * marked terminal by exhausting the attempt budget so the retry sweep skips them.
 */
public final class MetaCapiEventStatuses {

    private MetaCapiEventStatuses() {}

    public static final String PENDING = "pending";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    /** Tenant config became invalid (disabled / token cleared) before delivery. */
    public static final String SKIPPED = "skipped";
}
