package zelisline.ub.serviceops.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.payroll.application.StaffCommissionPlan;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.serviceops.api.dto.CommissionDtos.AdjustmentRequest;
import zelisline.ub.serviceops.api.dto.CommissionDtos.CommissionPlanResponse;
import zelisline.ub.serviceops.api.dto.CommissionDtos.LedgerEntryResponse;
import zelisline.ub.serviceops.api.dto.CommissionDtos.PayoutRequest;
import zelisline.ub.serviceops.api.dto.CommissionDtos.SetCommissionPlanRequest;
import zelisline.ub.serviceops.api.dto.CommissionDtos.StatementResponse;
import zelisline.ub.serviceops.application.StaffEarningsService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Staff commission, tips and payouts (ADR 0014). Earnings are personal financial data: the
 * all-staff views need {@code commissions.read}; a staff member with {@code commissions.read_own}
 * sees only their own entries.
 */
@Validated
@RestController
@RequestMapping("/api/v1/commissions")
@RequiredArgsConstructor
public class CommissionsController {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final String TILL_DEVICE_HEADER = zelisline.ub.till.application.TillDeviceService.TILL_DEVICE_HEADER;

    private final StaffEarningsService staffEarningsService;
    private final zelisline.ub.serviceops.application.ServiceInsightsService serviceInsightsService;
    private final StaffProfileService staffProfileService;
    private final RequestPermissionService permissionService;
    private final zelisline.ub.tenancy.application.BranchResolutionService branchResolutionService;

    @GetMapping("/plans")
    @PreAuthorize("hasPermission(null, 'commissions.plans.manage') or hasPermission(null, 'commissions.read')")
    public List<CommissionPlanResponse> plans(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return staffProfileService.commissionPlans(TenantRequestIds.resolveBusinessId(request)).stream()
                .map(CommissionsController::toResponse)
                .toList();
    }

    @PutMapping("/plans/{staffProfileId}")
    @PreAuthorize("hasPermission(null, 'commissions.plans.manage')")
    public CommissionPlanResponse setPlan(
            @PathVariable String staffProfileId,
            @Valid @RequestBody SetCommissionPlanRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return toResponse(staffProfileService.setCommissionPlan(
                TenantRequestIds.resolveBusinessId(request), staffProfileId, body.type(), body.rate(),
                body.productRate()));
    }

    @GetMapping("/statement")
    @PreAuthorize("hasPermission(null, 'commissions.read')")
    public StatementResponse statement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        LocalDate today = LocalDate.now(NAIROBI);
        LocalDate start = from != null ? from : today;
        LocalDate end = to != null ? to : start;
        requireSanePeriod(start, end);
        return staffEarningsService.statement(TenantRequestIds.resolveBusinessId(request), start, end);
    }

    /** What each service and each staff member brought in, less commission. */
    @GetMapping("/reports/services")
    @PreAuthorize("hasPermission(null, 'commissions.read')")
    public zelisline.ub.serviceops.api.dto.CommissionDtos.ServiceReportResponse serviceReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        var caller = CurrentTenantUser.requireHuman(request);
        LocalDate today = LocalDate.now(NAIROBI);
        LocalDate start = from != null ? from : today;
        LocalDate end = to != null ? to : start;
        requireSanePeriod(start, end);
        // A role locked to one branch sees that branch only, whatever it asks for.
        String branch = branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(),
                branchId == null || branchId.isBlank() ? caller.branchId() : branchId);
        return serviceInsightsService.report(TenantRequestIds.resolveBusinessId(request), start, end, branch);
    }

    /** Texts a staff member what they earned today and what they are owed. */
    @PostMapping("/staff/{staffProfileId}/earnings-sms")
    @PreAuthorize("hasPermission(null, 'commissions.payout')")
    public zelisline.ub.serviceops.api.dto.CommissionDtos.EarningsSmsResponse earningsSms(
            @PathVariable String staffProfileId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return serviceInsightsService.sendEarningsSms(TenantRequestIds.resolveBusinessId(request), staffProfileId);
    }

    /** One staff member's entries. Open to {@code commissions.read}, or to the staff member themself. */
    @GetMapping("/staff/{staffProfileId}/entries")
    @PreAuthorize("hasPermission(null, 'commissions.read') or hasPermission(null, 'commissions.read_own')")
    public List<LedgerEntryResponse> entries(
            @PathVariable String staffProfileId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        if (!permissionService.hasPermission(caller.roleId(), "commissions.read")) {
            boolean own = staffProfileService.commissionPlanForUser(businessId, caller.userId())
                    .map(StaffCommissionPlan::staffProfileId)
                    .filter(staffProfileId::equals)
                    .isPresent();
            if (!own) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only see your own earnings");
            }
        }
        LocalDate today = LocalDate.now(NAIROBI);
        LocalDate start = from != null ? from : today;
        LocalDate end = to != null ? to : start;
        requireSanePeriod(start, end);
        return staffEarningsService.entries(businessId, staffProfileId, start, end);
    }

    @PostMapping("/staff/{staffProfileId}/adjustments")
    @PreAuthorize("hasPermission(null, 'commissions.adjust')")
    public LedgerEntryResponse adjust(
            @PathVariable String staffProfileId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AdjustmentRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return staffEarningsService.adjust(TenantRequestIds.resolveBusinessId(request), staffProfileId,
                body.amount(), body.reason(), idempotencyKey, caller.userId());
    }

    @PostMapping("/staff/{staffProfileId}/payouts")
    @PreAuthorize("hasPermission(null, 'commissions.payout')")
    public List<LedgerEntryResponse> payout(
            @PathVariable String staffProfileId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = TILL_DEVICE_HEADER, required = false) String tillDeviceKey,
            @Valid @RequestBody PayoutRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        // A branch-locked role may only take cash from its own branch's till, as with sales.
        String branchId = body.branchId() == null || body.branchId().isBlank()
                ? null
                : branchResolutionService.requireBranchForLockedRole(
                        caller.roleId(), caller.branchId(), body.branchId());
        return staffEarningsService.payout(TenantRequestIds.resolveBusinessId(request), branchId,
                tillDeviceKey, staffProfileId, body.amount(), body.method(), body.reference(),
                Boolean.TRUE.equals(body.allowOverpay()), idempotencyKey, caller.userId());
    }

    @PostMapping("/payouts/{entryId}/reverse")
    @PreAuthorize("hasPermission(null, 'commissions.payout')")
    public List<LedgerEntryResponse> reversePayout(@PathVariable String entryId, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return staffEarningsService.reversePayout(
                TenantRequestIds.resolveBusinessId(request), entryId, caller.userId());
    }

    private static void requireSanePeriod(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The period must end on or after it starts");
        }
        if (start.plusDays(400).isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a period of 400 days or less");
        }
    }

    private static CommissionPlanResponse toResponse(StaffCommissionPlan plan) {
        return new CommissionPlanResponse(plan.staffProfileId(), plan.displayName(), plan.bookable(),
                plan.current(), plan.type(), plan.rate(), plan.productRate());
    }
}
