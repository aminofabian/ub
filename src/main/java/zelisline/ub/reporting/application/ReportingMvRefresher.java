package zelisline.ub.reporting.application;

/**
 * Service Provider Interface — every Phase 7 summary table ("MV") implements this
 * so the {@link ReportingRefreshRunner} can drive every refresh through the same
 * audit + observability path. Implementations are stateless and idempotent: calling
 * {@link #refresh(String)} twice for the same business returns the same row count
 * the second time (modulo new business activity).
 */
public interface ReportingMvRefresher {

    /**
     * Stable identifier persisted in {@code reporting_refresh_runs.mv_name}. Should
     * match the underlying MySQL table name (e.g. {@code mv_sales_daily}) so the
     * runbook lag query is a one-liner.
     */
    String mvName();

    /**
     * Refresh the MV for one tenant.
     *
     * @param businessId the business whose data is being recomputed; never null.
     * @return number of rows inserted or updated by the upsert; {@code 0} if no
     * source data changed.
     */
    long refresh(String businessId);
}
