package zelisline.ub.exports.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.exports.api.dto.ReportSubscriptionRequest;
import zelisline.ub.exports.api.dto.ReportSubscriptionResponse;
import zelisline.ub.exports.api.dto.ReportSubscriptionSendNowResponse;
import zelisline.ub.exports.application.ReportDeliveryService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/reports/subscriptions")
@RequiredArgsConstructor
public class ReportSubscriptionsController {

    private final ReportDeliveryService reportDeliveryService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'reports.export')")
    public List<ReportSubscriptionResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return reportDeliveryService.list(businessId).stream()
                .map(ReportSubscriptionResponse::of)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'reports.export')")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportSubscriptionResponse create(
            @Valid @RequestBody ReportSubscriptionRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String userId = CurrentTenantUser.auditActorId(request);
        return ReportSubscriptionResponse.of(
                reportDeliveryService.create(businessId, userId, toInput(body)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'reports.export')")
    public ReportSubscriptionResponse update(
            @PathVariable String id, @Valid @RequestBody ReportSubscriptionRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return ReportSubscriptionResponse.of(
                reportDeliveryService.update(businessId, id, toInput(body)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'reports.export')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        reportDeliveryService.delete(businessId, id);
    }

    @PostMapping("/{id}/send-now")
    @PreAuthorize("hasPermission(null, 'reports.export')")
    public ReportSubscriptionSendNowResponse sendNow(
            @PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        ReportDeliveryService.DeliveryOutcome outcome = reportDeliveryService.sendNow(businessId, id);
        return new ReportSubscriptionSendNowResponse(outcome.sent(), outcome.failed());
    }

    private static ReportDeliveryService.ReportSubscriptionInput toInput(ReportSubscriptionRequest b) {
        return new ReportDeliveryService.ReportSubscriptionInput(
                b.reportKey(), b.format(), b.frequency(), b.sendTime(),
                b.dayOfWeek(), b.dayOfMonth(), b.channels(),
                b.recipientEmail(), b.branchId(), b.categoryId(), b.active());
    }
}
