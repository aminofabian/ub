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
import zelisline.ub.pharmacy.application.PharmacyPatientService;

/** Patient profiles (ADR 0033, Phase 2). Reading needs {@code pharmacy.records.read}; every read is audited. */
@Validated
@RestController
@RequestMapping("/api/v1/pharmacy/patients")
@RequiredArgsConstructor
public class PatientsController {

    private final PharmacyPatientService patientService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public PharmacyStandardDtos.PatientPage list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest request
    ) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return patientService.list(TenantRequestIds.resolveBusinessId(request), q, page, size, user.userId());
    }

    /** Whole phone number or a patient number ("P-412"), for the till. Never a name search. */
    @GetMapping("/lookup")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public List<PharmacyStandardDtos.PatientMatch> lookup(@RequestParam String phone, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return patientService.lookupByPhone(TenantRequestIds.resolveBusinessId(request), phone, user.userId());
    }

    @GetMapping("/{patientId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public PharmacyStandardDtos.PatientHistory history(@PathVariable String patientId, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return patientService.history(TenantRequestIds.resolveBusinessId(request), patientId, user.userId());
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'pharmacy.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyStandardDtos.PatientResponse create(
            @Valid @RequestBody PharmacyStandardDtos.PatientRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return patientService.create(TenantRequestIds.resolveBusinessId(request), body, user.userId());
    }

    @PutMapping("/{patientId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'pharmacy.manage')")
    public PharmacyStandardDtos.PatientResponse update(
            @PathVariable String patientId,
            @Valid @RequestBody PharmacyStandardDtos.PatientRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return patientService.update(TenantRequestIds.resolveBusinessId(request), patientId, body, user.userId());
    }

    @DeleteMapping("/{patientId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String patientId, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        patientService.remove(TenantRequestIds.resolveBusinessId(request), patientId, user.userId());
    }
}
