package zelisline.ub.clinic.api;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import zelisline.ub.clinic.api.dto.ClinicDtos.AddProductRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.AddServiceRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.Appointment;
import zelisline.ub.clinic.api.dto.ClinicDtos.BoardResponse;
import zelisline.ub.clinic.api.dto.ClinicDtos.BookRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.CancelVisitRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.DepositRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.LineRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.MedicineGivenRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.NewPatientRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.OpenVisitRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.PaidVisitResponse;
import zelisline.ub.clinic.api.dto.ClinicDtos.PayVisitRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.PrepareRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.ReceptionPatient;
import zelisline.ub.clinic.api.dto.ClinicDtos.SavePrescriptionRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.SettingsRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.SettingsResponse;
import zelisline.ub.clinic.api.dto.ClinicDtos.VisitClaimRequest;
import zelisline.ub.clinic.api.dto.ClinicDtos.VisitResponse;
import zelisline.ub.clinic.application.ClinicSettingsService;
import zelisline.ub.clinic.application.ClinicVisitService;
import zelisline.ub.clinic.application.ClinicVisitService.Caller;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.ClaimResponse;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.posdraft.application.ServiceTicketService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.till.application.TillDeviceService;

/**
 * The Clinic section (ADR 0034). Every route is scoped to the caller's business, and a
 * branch-tied role only ever reaches visits of its own branch. Each route names the hat it
 * needs; a person can hold several.
 */
@Validated
@RestController
@RequestMapping("/api/v1/clinic")
@RequiredArgsConstructor
public class ClinicController {

    private static final String ANY_HAT = "hasPermission(null, 'clinic.reception') or hasPermission(null, 'clinic.consult')"
            + " or hasPermission(null, 'clinic.prescribe') or hasPermission(null, 'clinic.perform')"
            + " or hasPermission(null, 'clinic.manage') or hasPermission(null, 'pharmacy.dispense')"
            + " or hasPermission(null, 'sales.sell')";
    private static final String CLINICAL = "hasPermission(null, 'clinic.consult') or hasPermission(null, 'clinic.perform')";
    private static final String CLINICAL_OR_RECEPTION = CLINICAL + " or hasPermission(null, 'clinic.reception')";
    private static final String BILL = CLINICAL + " or hasPermission(null, 'sales.sell') or hasPermission(null, 'pharmacy.dispense')";

    private final ClinicSettingsService settingsService;
    private final ClinicVisitService visitService;
    private final zelisline.ub.clinic.application.ClinicTakingsService takingsService;
    private final ServiceTicketService serviceTicketService;
    private final BranchResolutionService branchResolutionService;

    // ── Settings ──────────────────────────────────────────────────────

    @GetMapping("/settings")
    @PreAuthorize(ANY_HAT)
    public SettingsResponse settings(HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return settingsService.get(TenantRequestIds.resolveBusinessId(request), user.userId(), user.roleId());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasPermission(null, 'clinic.manage')")
    public SettingsResponse saveSettings(@Valid @RequestBody SettingsRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return settingsService.save(TenantRequestIds.resolveBusinessId(request), body, user.userId(), user.roleId());
    }

    @PutMapping("/services/{serviceId}/receipt-name")
    @PreAuthorize("hasPermission(null, 'clinic.manage')")
    public List<zelisline.ub.clinic.api.dto.ClinicDtos.ClinicService> receiptName(
            @PathVariable String serviceId,
            @Valid @RequestBody zelisline.ub.clinic.api.dto.ClinicDtos.ReceiptNameRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.setReceiptName(TenantRequestIds.resolveBusinessId(request), serviceId, body.receiptName());
    }

    @GetMapping("/rooms")
    @PreAuthorize(ANY_HAT)
    public List<zelisline.ub.clinic.api.dto.ClinicDtos.Room> rooms(@RequestParam String branchId, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(user.roleId(), user.branchId(), branchId);
        return settingsService.rooms(TenantRequestIds.resolveBusinessId(request), branch);
    }

    @GetMapping("/clinicians")
    @PreAuthorize(ANY_HAT)
    public List<zelisline.ub.clinic.api.dto.ClinicDtos.Clinician> clinicians(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return visitService.clinicians(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/services")
    @PreAuthorize(ANY_HAT)
    public List<zelisline.ub.clinic.api.dto.ClinicDtos.ClinicService> services(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.services(TenantRequestIds.resolveBusinessId(request));
    }

    // ── Patients ──────────────────────────────────────────────────────

    /** Name, phone or patient number. Returns name, number, phone and age only. */
    @GetMapping("/patients")
    @PreAuthorize("hasPermission(null, 'clinic.reception')")
    public List<ReceptionPatient> patients(@RequestParam String q, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return visitService.searchPatients(TenantRequestIds.resolveBusinessId(request), q, user.userId());
    }

    @PostMapping("/patients")
    @PreAuthorize("hasPermission(null, 'clinic.reception')")
    public ReceptionPatient addPatient(@Valid @RequestBody NewPatientRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return visitService.addPatient(TenantRequestIds.resolveBusinessId(request), body, user.userId());
    }

    // ── Visits ────────────────────────────────────────────────────────

    @GetMapping("/visits")
    @PreAuthorize(ANY_HAT)
    public BoardResponse board(@RequestParam String branchId, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(user.roleId(), user.branchId(), branchId);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return visitService.board(businessId, branch, caller(businessId, user));
    }

    @PostMapping("/visits")
    @PreAuthorize("hasPermission(null, 'clinic.reception')")
    public VisitResponse open(@Valid @RequestBody OpenVisitRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        branchResolutionService.requireBranchForLockedRole(user.roleId(), user.branchId(), body.branchId());
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return visitService.open(businessId, body, caller(businessId, user));
    }

    @GetMapping("/visits/{id}")
    @PreAuthorize(ANY_HAT)
    public VisitResponse get(@PathVariable String id, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.get(c.businessId, id, c.caller);
    }

    @PostMapping("/visits/{id}/services")
    @PreAuthorize(CLINICAL_OR_RECEPTION)
    public VisitResponse addService(
            @PathVariable String id, @Valid @RequestBody AddServiceRequest body, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.addService(c.businessId, id, body.serviceId(), c.caller);
    }

    @DeleteMapping("/visits/{id}/services/{lineId}")
    @PreAuthorize(CLINICAL_OR_RECEPTION)
    public VisitResponse removeService(@PathVariable String id, @PathVariable String lineId, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.removeService(c.businessId, id, lineId, c.caller);
    }

    @PostMapping("/visits/{id}/services/{lineId}/start")
    @PreAuthorize(CLINICAL)
    public VisitResponse startLine(
            @PathVariable String id, @PathVariable String lineId,
            @Valid @RequestBody(required = false) LineRequest body, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.startLine(c.businessId, id, lineId, body, c.caller);
    }

    @PostMapping("/visits/{id}/services/{lineId}/done")
    @PreAuthorize(CLINICAL)
    public VisitResponse finishLine(
            @PathVariable String id, @PathVariable String lineId,
            @Valid @RequestBody(required = false) LineRequest body, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.finishLine(c.businessId, id, lineId, body, c.caller);
    }

    /** A medicine given in the clinic. It goes on the bill as a medicine and obeys every pharmacy rule at payment. */
    @PostMapping("/visits/{id}/medicines-given")
    @PreAuthorize(CLINICAL)
    public VisitResponse medicineGiven(
            @PathVariable String id, @Valid @RequestBody MedicineGivenRequest body, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.addProduct(c.businessId, id, body.itemId(), body.quantity(), true, c.caller);
    }

    @PostMapping("/visits/{id}/products")
    @PreAuthorize(BILL)
    public VisitResponse addProduct(
            @PathVariable String id, @Valid @RequestBody AddProductRequest body, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.addProduct(c.businessId, id, body.itemId(), body.quantity(), false, c.caller);
    }

    @DeleteMapping("/visits/{id}/products/{lineId}")
    @PreAuthorize(BILL)
    public VisitResponse removeProduct(@PathVariable String id, @PathVariable String lineId, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.removeProduct(c.businessId, id, lineId, c.caller);
    }

    // ── Prescription ──────────────────────────────────────────────────

    @PutMapping("/visits/{id}/prescription")
    @PreAuthorize("hasPermission(null, 'clinic.prescribe')")
    public VisitResponse savePrescription(
            @PathVariable String id, @Valid @RequestBody SavePrescriptionRequest body, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.savePrescription(c.businessId, id, body, c.caller);
    }

    @PostMapping("/visits/{id}/prescription/prepare")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense')")
    public VisitResponse prepare(
            @PathVariable String id, @Valid @RequestBody PrepareRequest body, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.prepare(c.businessId, id, body, c.caller);
    }

    @PostMapping("/visits/{id}/prescription/send-back")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'clinic.prescribe')")
    public VisitResponse sendBack(@PathVariable String id, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.sendBack(c.businessId, id, c.caller);
    }

    @PostMapping("/visits/{id}/prescription/decline")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'clinic.prescribe') or hasPermission(null, 'sales.sell')")
    public VisitResponse decline(@PathVariable String id, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.declineMedicines(c.businessId, id, c.caller);
    }

    // ── Money ─────────────────────────────────────────────────────────

    @PostMapping("/visits/{id}/deposit")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public VisitResponse deposit(
            @PathVariable String id, @Valid @RequestBody DepositRequest body, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.takeDeposit(c.businessId, id, body, c.caller, request.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
    }

    @PostMapping("/visits/{id}/pay")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public PaidVisitResponse pay(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PayVisitRequest body,
            HttpServletRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        Ctx c = ctx(request, id);
        return visitService.pay(c.businessId, id, body, idempotencyKey, c.caller,
                request.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
    }

    @PostMapping("/visits/{id}/cancel")
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'clinic.reception')")
    public VisitResponse cancel(
            @PathVariable String id, @Valid @RequestBody(required = false) CancelVisitRequest body,
            HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.cancel(c.businessId, id, body, c.caller, request.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
    }

    @PostMapping("/visits/{id}/claims")
    @PreAuthorize("hasPermission(null, 'pharmacy.claims.manage')")
    public ClaimResponse claim(
            @PathVariable String id, @Valid @RequestBody VisitClaimRequest body, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.claim(c.businessId, id, body, c.caller);
    }

    // ── Takings ───────────────────────────────────────────────────────

    /** What the clinic billed in a period, by service and by who did it. Counts and money only. */
    @GetMapping("/takings")
    @PreAuthorize("hasPermission(null, 'clinic.manage')")
    public zelisline.ub.clinic.api.dto.ClinicDtos.TakingsResponse takings(
            @RequestParam String branchId, @RequestParam Instant from, @RequestParam Instant to,
            HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(user.roleId(), user.branchId(), branchId);
        if (!from.isBefore(to) || java.time.Duration.between(from, to).toDays() > 370) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a period of up to a year");
        }
        return takingsService.takings(TenantRequestIds.resolveBusinessId(request), branch, from, to);
    }

    // ── Appointments ──────────────────────────────────────────────────

    @GetMapping("/appointments")
    @PreAuthorize(ANY_HAT)
    public List<Appointment> appointments(
            @RequestParam String branchId, @RequestParam Instant from, @RequestParam Instant to,
            HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(user.roleId(), user.branchId(), branchId);
        if (!from.isBefore(to) || java.time.Duration.between(from, to).toDays() > 62) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a period of up to two months");
        }
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return visitService.appointments(businessId, branch, from, to, caller(businessId, user));
    }

    @PostMapping("/appointments")
    @PreAuthorize("hasPermission(null, 'clinic.reception')")
    public VisitResponse book(@Valid @RequestBody BookRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        branchResolutionService.requireBranchForLockedRole(user.roleId(), user.branchId(), body.branchId());
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return visitService.book(businessId, body, caller(businessId, user));
    }

    @PostMapping("/visits/{id}/arrive")
    @PreAuthorize("hasPermission(null, 'clinic.reception')")
    public VisitResponse arrive(@PathVariable String id, HttpServletRequest request) {
        Ctx c = ctx(request, id);
        return visitService.arrive(c.businessId, id, c.caller);
    }

    // ── Shared ────────────────────────────────────────────────────────

    private record Ctx(String businessId, Caller caller) {
    }

    /** Resolves the business and the caller, and keeps a branch-tied role to its own branch's visits. */
    private Ctx ctx(HttpServletRequest request, String visitId) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        ServiceTicketService.TicketRef ticket = serviceTicketService.ref(businessId, visitId);
        branchResolutionService.requireBranchForLockedRole(user.roleId(), user.branchId(), ticket.branchId());
        return new Ctx(businessId, caller(businessId, user));
    }

    private Caller caller(String businessId, TenantPrincipal user) {
        return visitService.caller(businessId, user.userId(), user.roleId());
    }
}
