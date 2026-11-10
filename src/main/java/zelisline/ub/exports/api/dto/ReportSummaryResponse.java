package zelisline.ub.exports.api.dto;

import java.util.List;

import zelisline.ub.exports.application.ReportSummary;

/** Report summary for the UI: headline tiles, top rows, and the sendable text body. */
public record ReportSummaryResponse(
        String title,
        String businessName,
        List<String> metaLines,
        List<Highlight> highlights,
        List<String> topLines,
        String text
) {
    public record Highlight(String label, String value) {
    }

    public static ReportSummaryResponse of(ReportSummary s) {
        List<Highlight> hs = s.highlights().stream()
                .map(h -> new Highlight(h.label(), h.value()))
                .toList();
        return new ReportSummaryResponse(
                s.title(), s.businessName(), s.metaLines(), hs, s.topLines(), s.text());
    }
}
