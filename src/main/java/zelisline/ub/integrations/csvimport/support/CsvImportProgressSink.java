package zelisline.ub.integrations.csvimport.support;

/**
 * Optional observer for long-running CSV imports (async worker publishes row counts between commits).
 */
public interface CsvImportProgressSink {

    CsvImportProgressSink NONE = new CsvImportProgressSink() {};

    /** Called once parsing completes (before validation / per-row work). */
    default void onRowsParsed(int totalRowCount) {
    }

    /**
     * Called after each successfully persisted logical row during commit (1 … total),
     * or once after validation completes for dry-run paths.
     */
    default void onRowCommitted(int rowsFinishedOneBased) {
    }
}
