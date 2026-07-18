package zelisline.ub.inventory.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zelisline.ub.inventory.api.dto.DailyStockAuditDtos.DailyStockAuditInvestigationResponse;
import zelisline.ub.inventory.api.dto.DailyStockAuditDtos.DailyStockAuditReviewResponse;
import zelisline.ub.inventory.api.dto.DailyStockAuditDtos.DailyStockAuditSessionResponse;
import zelisline.ub.inventory.api.dto.DailyStockAuditDtos.DailyStockAuditTodayResponse;
import zelisline.ub.inventory.api.dto.DailyStockAuditRequests.DailyAuditBulkApproveRequest;
import zelisline.ub.inventory.api.dto.DailyStockAuditRequests.DailyAuditReviewActionRequest;
import zelisline.ub.inventory.api.dto.DailyStockAuditRequests.PatchDailyAuditLineRequest;
import zelisline.ub.inventory.api.dto.DailyStockAuditRequests.PatchDailyAuditProgressRequest;
import zelisline.ub.inventory.api.dto.DailyStockAuditRequests.PostDailyAuditSessionRequest;
import zelisline.ub.inventory.application.DailyStockAuditService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/inventory/stock-take/daily-audits")
@RequiredArgsConstructor
public class DailyStockAuditController {

    private static final String PERMISSION_STOCKTAKE_APPROVE = "stocktake.approve";

    private final DailyStockAuditService dailyStockAuditService;
    private final BranchResolutionService branchResolutionService;
    private final RequestPermissionService permissionService;

    @GetMapping("/today")
    @PreAuthorize("hasPermission(null, 'stocktake.read')")
    public DailyStockAuditTodayResponse getToday(
            @RequestParam @NotBlank String branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate auditDate,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = resolveBranch(principal, branchId);
        LocalDate date = dailyStockAuditService.resolveAuditDate(auditDate);
        return dailyStockAuditService.getToday(businessId, effectiveBranch, date);
    }

    @PostMapping("/sessions")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    @ResponseStatus(HttpStatus.CREATED)
    public DailyStockAuditSessionResponse startSession(
            @Valid @RequestBody PostDailyAuditSessionRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = resolveBranch(principal, body.branchId());
        LocalDate date = dailyStockAuditService.resolveAuditDate(body.auditDate());
        return dailyStockAuditService.startOrResumeSession(
                businessId,
                effectiveBranch,
                body.sessionType().trim(),
                date,
                principal.userId(),
                principal.roleId(),
                canApprove(principal)
        );
    }

    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public DailyStockAuditSessionResponse getSession(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return dailyStockAuditService.getSession(
                businessId,
                sessionId,
                principal.roleId(),
                canApprove(principal)
        );
    }

    @PatchMapping("/sessions/{sessionId}/lines/{lineId}")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public DailyStockAuditSessionResponse applyLineCount(
            @PathVariable String sessionId,
            @PathVariable String lineId,
            @Valid @RequestBody PatchDailyAuditLineRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return dailyStockAuditService.applyLineCount(
                businessId,
                sessionId,
                lineId,
                body.countedQty(),
                body.note(),
                principal.userId(),
                principal.roleId(),
                canApprove(principal)
        );
    }

    @PatchMapping("/sessions/{sessionId}/progress")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public DailyStockAuditSessionResponse updateProgress(
            @PathVariable String sessionId,
            @Valid @RequestBody PatchDailyAuditProgressRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return dailyStockAuditService.updateProgress(
                businessId,
                sessionId,
                body.currentLineIndex(),
                principal.roleId(),
                canApprove(principal)
        );
    }

    @PostMapping("/sessions/{sessionId}/complete")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public DailyStockAuditSessionResponse completeSession(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return dailyStockAuditService.completeSession(
                businessId,
                sessionId,
                principal.userId(),
                principal.roleId(),
                canApprove(principal)
        );
    }

    @GetMapping("/review")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public DailyStockAuditReviewResponse getReview(
            @RequestParam @NotBlank String branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate auditDate,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = resolveBranch(principal, branchId);
        LocalDate date = dailyStockAuditService.resolveAuditDate(auditDate);
        return dailyStockAuditService.getReview(businessId, effectiveBranch, date);
    }

    @PostMapping("/{auditId}/items/{itemId}/approve")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public DailyStockAuditReviewResponse approveItem(
            @PathVariable String auditId,
            @PathVariable String itemId,
            @Valid @RequestBody(required = false) DailyAuditReviewActionRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String notes = body != null ? body.notes() : null;
        return dailyStockAuditService.approveItem(
                businessId,
                auditId,
                itemId,
                notes,
                principal.userId()
        );
    }

    @PostMapping("/{auditId}/approve-bulk")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public DailyStockAuditReviewResponse approveItemsBulk(
            @PathVariable String auditId,
            @Valid @RequestBody DailyAuditBulkApproveRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return dailyStockAuditService.approveItems(
                businessId,
                auditId,
                body.itemIds(),
                body.notes(),
                principal.userId()
        );
    }

    @PostMapping("/{auditId}/items/{itemId}/escalate")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public DailyStockAuditReviewResponse escalateItem(
            @PathVariable String auditId,
            @PathVariable String itemId,
            @Valid @RequestBody(required = false) DailyAuditReviewActionRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String notes = body != null ? body.notes() : null;
        return dailyStockAuditService.escalateItem(
                businessId,
                auditId,
                itemId,
                notes,
                principal.userId()
        );
    }

    @GetMapping("/investigations")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public List<DailyStockAuditInvestigationResponse> listInvestigations(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = branchId == null || branchId.isBlank()
                ? null
                : resolveBranch(principal, branchId);
        return dailyStockAuditService.listInvestigations(
                businessId,
                effectiveBranch,
                from,
                to
        );
    }

    private String resolveBranch(TenantPrincipal principal, String branchId) {
        return branchResolutionService.requireBranchForLockedRole(
                principal.roleId(),
                principal.branchId(),
                branchId
        );
    }

    private boolean canApprove(TenantPrincipal principal) {
        return permissionService.hasPermission(
                principal.roleId(),
                PERMISSION_STOCKTAKE_APPROVE
        );
    }
}
