package zelisline.ub.exports.application;

import java.time.LocalDate;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.opsalerts.api.dto.OpsAlertTestSendResponse;
import zelisline.ub.opsalerts.application.TenantOpsAlertDispatcher;

/**
 * Builds a report summary (reusing {@link ExportService}'s report builders) and,
 * optionally, sends it to the shop owner's verified alert phone via WhatsApp/SMS.
 */
@Service
@RequiredArgsConstructor
public class ReportSummaryService {

    private final ExportService exportService;
    private final TenantOpsAlertDispatcher opsAlertDispatcher;

    public ReportSummary preview(
            String businessId, String reportKey, LocalDate from, LocalDate to,
            String branchId, String categoryId, String itemTypeId) {
        ReportTable table = exportService.buildReportTable(
                businessId, normalizeKey(reportKey), params(from, to, branchId, categoryId, itemTypeId));
        return ExportService.summarize(table);
    }

    public OpsAlertTestSendResponse send(
            String businessId, String reportKey, LocalDate from, LocalDate to,
            String branchId, String categoryId, String itemTypeId, String channel) {
        ReportSummary summary = preview(businessId, reportKey, from, to, branchId, categoryId, itemTypeId);
        return opsAlertDispatcher.sendOwnerMessage(businessId, channel, summary.text());
    }

    private static ExportJobParams params(
            LocalDate from, LocalDate to, String branchId, String categoryId, String itemTypeId) {
        return new ExportJobParams(
                from, to, blankToNull(branchId), blankToNull(categoryId), blankToNull(itemTypeId));
    }

    private static String normalizeKey(String reportKey) {
        if (reportKey == null || reportKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reportKey is required");
        }
        return reportKey.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
