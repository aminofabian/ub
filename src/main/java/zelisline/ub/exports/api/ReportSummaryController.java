package zelisline.ub.exports.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.exports.api.dto.ReportSummaryRequest;
import zelisline.ub.exports.api.dto.ReportSummaryResponse;
import zelisline.ub.exports.api.dto.ReportSummarySendRequest;
import zelisline.ub.exports.api.dto.ReportSummarySendResponse;
import zelisline.ub.exports.application.ReportSummary;
import zelisline.ub.exports.application.ReportSummaryService;
import zelisline.ub.opsalerts.api.dto.OpsAlertTestSendResponse;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/reports/summary")
@RequiredArgsConstructor
public class ReportSummaryController {

    private final ReportSummaryService reportSummaryService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'reports.export')")
    public ReportSummaryResponse preview(
            @Valid @RequestBody ReportSummaryRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        ReportSummary summary = reportSummaryService.preview(
                businessId, body.reportKey(), body.from(), body.to(),
                body.branchId(), body.categoryId(), body.itemTypeId());
        return ReportSummaryResponse.of(summary);
    }

    @PostMapping("/send")
    @PreAuthorize("hasPermission(null, 'reports.export')")
    public ReportSummarySendResponse send(
            @Valid @RequestBody ReportSummarySendRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        OpsAlertTestSendResponse result = reportSummaryService.send(
                businessId, body.reportKey(), body.from(), body.to(),
                body.branchId(), body.categoryId(), body.itemTypeId(), body.channel());
        return new ReportSummarySendResponse(result.channel(), result.outcome(), result.phoneMasked());
    }
}
