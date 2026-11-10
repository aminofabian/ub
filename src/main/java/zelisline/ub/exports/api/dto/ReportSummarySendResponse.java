package zelisline.ub.exports.api.dto;

/** Delivery outcome of a report-summary send. */
public record ReportSummarySendResponse(
        String channel,
        String outcome,
        String phoneMasked
) {
}
