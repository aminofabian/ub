package zelisline.ub.pharmacy.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.pharmacy.api.dto.CreateDispensingApprovalRequest;
import zelisline.ub.pharmacy.api.dto.DispensingApprovalResponse;
import zelisline.ub.pharmacy.api.dto.DispensingApproverResponse;
import zelisline.ub.pharmacy.api.dto.DispensingCheckRequest;
import zelisline.ub.pharmacy.api.dto.DispensingCheckResponse;
import zelisline.ub.pharmacy.api.dto.DispensingRecordPageResponse;
import zelisline.ub.pharmacy.api.dto.DispensingRecordResponse;
import zelisline.ub.pharmacy.application.DispensingApprovalService;
import zelisline.ub.pharmacy.application.DispensingGate;
import zelisline.ub.pharmacy.application.DispensingRecordQueryService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Till-side dispensing helpers (check a cart, list approvers, approve by PIN) and the dispensing
 * log. The business always comes from the request.
 */
@Validated
@RestController
@RequestMapping("/api/v1/pharmacy/dispensing")
@RequiredArgsConstructor
public class DispensingController {

    private final DispensingGate dispensingGate;
    private final DispensingApprovalService approvalService;
    private final DispensingRecordQueryService recordQueryService;
    private final zelisline.ub.pharmacy.application.DispensingRecordAmendService amendService;

    /** The second check: someone who may dispense, other than the dispenser, confirms the record. */
    @PostMapping("/records/{recordId}/verify")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense')")
    public DispensingRecordResponse verify(@PathVariable String recordId, HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String locked = lockedBranch(user, businessId);
        amendService.verify(businessId, recordId, user.userId(), locked);
        return recordQueryService.get(businessId, recordId, user.userId(), locked);
    }

    /** Adds a note saying what on the record was written wrongly. The record is not edited. */
    @PostMapping("/records/{recordId}/corrections")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense')")
    public DispensingRecordResponse correct(
            @PathVariable String recordId,
            @Valid @RequestBody CorrectionRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String locked = lockedBranch(user, businessId);
        amendService.addCorrection(businessId, recordId, body.note(), user.userId(), locked);
        return recordQueryService.get(businessId, recordId, user.userId(), locked);
    }

    public record CorrectionRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000) String note) {
    }
    private final zelisline.ub.tenancy.application.BranchResolutionService branchResolutionService;

    /** Where the medicines in a cart stand, so the till can ask for details before Charge. */
    @PostMapping("/check")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public DispensingCheckResponse check(
            @Valid @RequestBody DispensingCheckRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        List<DispensingCheckResponse.Line> lines = new ArrayList<>();
        boolean needsRecord = false;
        boolean blocked = false;
        for (DispensingGate.ItemStanding s : dispensingGate.standings(businessId, body.itemIds())) {
            lines.add(new DispensingCheckResponse.Line(
                    s.itemId(), s.name(), s.dispensingClass().name(), s.status()));
            needsRecord = needsRecord || DispensingGate.ItemStanding.NEEDS_RECORD.equals(s.status());
            blocked = blocked
                    || DispensingGate.ItemStanding.UNCLASSIFIED.equals(s.status())
                    || DispensingGate.ItemStanding.CONTROLLED_BLOCKED.equals(s.status());
        }
        return new DispensingCheckResponse(
                lines, needsRecord, blocked, dispensingGate.mayDispense(businessId, user.userId(), user.roleId()));
    }

    /**
     * Item ids that need the pharmacist (anything but an over-the-counter medicine). The till
     * keeps this list so it can refuse to queue such a sale while it is offline.
     */
    @GetMapping("/counter-only-item-ids")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public List<String> counterOnlyItemIds(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return dispensingGate.counterOnlyItemIds(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/approvers")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public List<DispensingApproverResponse> approvers(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return approvalService.approvers(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/approvals")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    @ResponseStatus(HttpStatus.CREATED)
    public DispensingApprovalResponse approve(
            @Valid @RequestBody CreateDispensingApprovalRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return approvalService.approve(
                TenantRequestIds.resolveBusinessId(request),
                user.userId(),
                body.approverUserId(),
                body.pin(),
                body.quantitiesByItemId());
    }

    @GetMapping("/records")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public DispensingRecordPageResponse records(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String locked = lockedBranch(user, businessId);
        return recordQueryService.list(
                businessId, locked != null ? locked : branchId, from, to, page, size, user.userId());
    }

    @GetMapping("/records/{recordId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public DispensingRecordResponse record(@PathVariable String recordId, HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return recordQueryService.get(businessId, recordId, user.userId(), lockedBranch(user, businessId));
    }

    @GetMapping("/records/by-sale/{saleId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public List<DispensingRecordResponse> recordsForSale(@PathVariable String saleId, HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return recordQueryService.forSale(businessId, saleId, user.userId(), lockedBranch(user, businessId));
    }

    /** The one branch a branch-tied role may read records for, or null for roles that work across branches. */
    private String lockedBranch(zelisline.ub.platform.security.TenantPrincipal user, String businessId) {
        if (!branchResolutionService.isBranchLockedRole(user.roleId())) {
            return null;
        }
        return branchResolutionService.resolveBranchForReport(businessId, user.roleId(), user.branchId(), null);
    }
}
