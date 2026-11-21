package zelisline.ub.pharmacy.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.pharmacy.api.dto.PharmacyStandardDtos;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.pharmacy.application.ControlledRegisterService;
import zelisline.ub.pharmacy.application.DispensingFollowUpService;
import zelisline.ub.pharmacy.application.PharmacyComplianceService;

/**
 * Follow-ups, the controlled register, and the compliance logs (ADR 0033, Phase 2). Anything that
 * names a patient needs {@code pharmacy.records.read} and is audited.
 */
@Validated
@RestController
@RequestMapping("/api/v1/pharmacy")
@RequiredArgsConstructor
public class PharmacyStandardController {

    private final DispensingFollowUpService followUpService;
    private final ControlledRegisterService registerService;
    private final PharmacyComplianceService complianceService;
    private final BranchResolutionService branchResolutionService;

    // ── Follow-ups ────────────────────────────────────────────────────

    @GetMapping("/follow-ups/owings")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public List<PharmacyStandardDtos.Owing> owings(HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return followUpService.owings(businessId, lockedBranch(user, businessId), user.userId());
    }

    /** A chosen patient's outstanding balances, for the dispensing dialog at the till. */
    @GetMapping("/follow-ups/owings/for-patient")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public List<PharmacyStandardDtos.Owing> owingsForPatient(
            @RequestParam String patientId, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return followUpService.owingsForPatient(
                TenantRequestIds.resolveBusinessId(request), patientId, user.userId());
    }

    @GetMapping("/follow-ups/refills")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public List<PharmacyStandardDtos.RefillDue> refills(HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return followUpService.refillsDue(businessId, lockedBranch(user, businessId), user.userId());
    }

    public record CloseFollowUpRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String note) {
    }

    /** A balance that will not be collected, or a refill that has been dealt with. */
    @PostMapping("/follow-ups/{lineId}/close")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'pharmacy.manage')")
    public void closeFollowUp(
            @org.springframework.web.bind.annotation.PathVariable String lineId,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody CloseFollowUpRequest body,
            HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        followUpService.close(businessId, lineId, body.note(), user.userId(), lockedBranch(user, businessId));
    }

    // ── Controlled register ───────────────────────────────────────────

    @GetMapping("/controlled-register/items")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public List<PharmacyStandardDtos.ControlledItem> controlledItems(HttpServletRequest request) {
        requireAllBranches(CurrentTenantUser.requireHuman(request));
        return registerService.items(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/controlled-register/{itemId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public PharmacyStandardDtos.ControlledRegister controlledRegister(
            @PathVariable String itemId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        requireAllBranches(user);
        return registerService.register(TenantRequestIds.resolveBusinessId(request), itemId, from, to, user.userId());
    }

    // ── Temperature log ───────────────────────────────────────────────

    @GetMapping("/compliance/temperatures")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'pharmacy.manage')")
    public List<PharmacyStandardDtos.TemperatureResponse> temperatures(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) Integer days,
            HttpServletRequest request
    ) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String locked = lockedBranch(user, businessId);
        return complianceService.temperatures(businessId, locked != null ? locked : branchId, days);
    }

    @PostMapping("/compliance/temperatures")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'pharmacy.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyStandardDtos.TemperatureResponse recordTemperature(
            @Valid @RequestBody PharmacyStandardDtos.TemperatureRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        branchResolutionService.requireBranchForLockedRole(user.roleId(), user.branchId(), body.branchId());
        return complianceService.recordTemperature(businessId, body, user.userId());
    }

    // ── Supplier licences ─────────────────────────────────────────────

    @GetMapping("/compliance/supplier-licences")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'pharmacy.manage')"
            + " or hasPermission(null, 'purchasing.path_a.write')")
    public List<PharmacyStandardDtos.SupplierLicenceResponse> supplierLicences(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return complianceService.supplierLicences(TenantRequestIds.resolveBusinessId(request));
    }

    @PutMapping("/compliance/supplier-licences/{supplierId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public PharmacyStandardDtos.SupplierLicenceResponse saveSupplierLicence(
            @PathVariable String supplierId,
            @Valid @RequestBody PharmacyStandardDtos.SupplierLicenceRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return complianceService.saveSupplierLicence(
                TenantRequestIds.resolveBusinessId(request), supplierId, body, user.userId());
    }

    // ── Adverse events ────────────────────────────────────────────────

    @GetMapping("/compliance/adverse-events")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public List<PharmacyStandardDtos.AdverseEventResponse> adverseEvents(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return complianceService.adverseEvents(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/compliance/adverse-events")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'pharmacy.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyStandardDtos.AdverseEventResponse recordAdverseEvent(
            @Valid @RequestBody PharmacyStandardDtos.AdverseEventRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return complianceService.recordAdverseEvent(TenantRequestIds.resolveBusinessId(request), body, user.userId());
    }

    @PostMapping("/compliance/adverse-events/{eventId}/reported")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public PharmacyStandardDtos.AdverseEventResponse markReported(
            @PathVariable String eventId,
            @Valid @RequestBody PharmacyStandardDtos.AdverseEventReportedRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return complianceService.markReported(TenantRequestIds.resolveBusinessId(request), eventId, body.externalRef());
    }

    // ── Shared ────────────────────────────────────────────────────────

    /** The one branch a branch-tied role may read, or null for roles that work across branches. */
    private String lockedBranch(TenantPrincipal user, String businessId) {
        if (!branchResolutionService.isBranchLockedRole(user.roleId())) {
            return null;
        }
        return branchResolutionService.resolveBranchForReport(businessId, user.roleId(), user.branchId(), null);
    }

    /** The register covers the whole business, so a branch-tied role cannot open it. */
    private void requireAllBranches(TenantPrincipal user) {
        if (branchResolutionService.isBranchLockedRole(user.roleId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "The controlled register covers every branch. Ask the pharmacist to open it.");
        }
    }
}
