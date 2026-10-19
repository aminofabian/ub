package zelisline.ub.finance.api;

import java.time.LocalDate;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.VatBucket;
import zelisline.ub.finance.api.dto.BalanceSheetResponse;
import zelisline.ub.finance.api.dto.DailyProfitPoint;
import zelisline.ub.finance.api.dto.FinancePulseResponse;
import zelisline.ub.finance.api.dto.ProfitAndLossResponse;
import zelisline.ub.finance.api.dto.VatBucketResponse;
import zelisline.ub.finance.api.dto.VatReportResponse;
import zelisline.ub.finance.application.FinanceReportsService;
import zelisline.ub.finance.application.VatReportCsvService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Phase 7 Slice 0 — Phase 6 close-out gate (PHASE_7_PLAN.md §Slice 0).
 *
 * <p>Three journal-backed read endpoints unblock Phase 7 Slice 5 (dashboard SLO)
 * by giving the owner pulse, simple period P&amp;L, and simple as-of balance sheet
 * surfaces an authoritative source. Phase 7 Slice 2 will accelerate the same numbers
 * with {@code mv_sales_daily} without changing the API contract.</p>
 */
@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
public class FinanceReportsController {

    private final FinanceReportsService financeReportsService;
    private final VatReportCsvService vatReportCsvService;

    @GetMapping("/pulse")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public FinancePulseResponse pulse(
            @RequestParam(value = "date", required = false) LocalDate date,
            @RequestParam(value = "branchId", required = false) String branchId,
            @RequestParam(value = "itemTypeId", required = false) String itemTypeId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return financeReportsService.pulse(
                TenantRequestIds.resolveBusinessId(request), date, branchId, itemTypeId);
    }

    @GetMapping("/pl")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public ProfitAndLossResponse profitAndLoss(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(value = "branchId", required = false) String branchId,
            @RequestParam(value = "itemTypeId", required = false) String itemTypeId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return financeReportsService.profitAndLoss(
                TenantRequestIds.resolveBusinessId(request), from, to, branchId, itemTypeId);
    }

    @GetMapping("/pl/daily")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public List<DailyProfitPoint> dailyProfitAndLoss(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return financeReportsService.dailyProfitAndLoss(
                TenantRequestIds.resolveBusinessId(request), from, to, branchId);
    }

    @GetMapping("/vat")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public VatReportResponse vatReport(
            @RequestParam("from") LocalDate from,
            @RequestParam(value = "to", required = false) LocalDate to,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return financeReportsService.vatReport(
                TenantRequestIds.resolveBusinessId(request), from, to, branchId);
    }

    @GetMapping("/vat/buckets")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public List<VatBucketResponse> vatReportBuckets(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(value = "bucket", defaultValue = "day") String bucket,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return financeReportsService.vatReportBuckets(
                TenantRequestIds.resolveBusinessId(request), from, to, parseBucket(bucket), branchId);
    }

    @GetMapping(value = "/vat/export.csv", produces = "text/csv")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public ResponseEntity<byte[]> vatReportCsv(
            @RequestParam("from") LocalDate from,
            @RequestParam(value = "to", required = false) LocalDate to,
            @RequestParam(value = "bucket", defaultValue = "day") String bucket,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        byte[] body = vatReportCsvService.vatReportCsv(
                TenantRequestIds.resolveBusinessId(request), from, to, parseBucket(bucket), branchId);
        String filename = "vat-report-" + from + (to != null ? "-to-" + to : "") + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    @GetMapping("/balance-sheet")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public BalanceSheetResponse balanceSheet(
            @RequestParam(value = "asOf", required = false) LocalDate asOf,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return financeReportsService.balanceSheet(TenantRequestIds.resolveBusinessId(request), asOf, branchId);
    }

    /** Accept day/DAY/month/MONTH — the UI sends lowercase while the enum is uppercase. */
    private static VatBucket parseBucket(String raw) {
        return VatBucket.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
