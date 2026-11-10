package zelisline.ub.exports.application;

import java.util.List;

/** A short, sendable summary of a report: headline tiles, top rows, and a plain-text body. */
public record ReportSummary(
        String title,
        String businessName,
        List<String> metaLines,
        List<Highlight> highlights,
        List<String> topLines,
        String text
) {
    public record Highlight(String label, String value) {
    }
}
