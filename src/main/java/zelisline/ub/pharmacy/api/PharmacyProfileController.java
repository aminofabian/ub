package zelisline.ub.pharmacy.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.pharmacy.api.dto.PharmacyProfileResponse;
import zelisline.ub.pharmacy.api.dto.UpdatePharmacyProfileRequest;
import zelisline.ub.pharmacy.api.dto.UpdateStaffCredentialRequest;
import zelisline.ub.pharmacy.application.PharmacyProfileService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/pharmacy/profile")
@RequiredArgsConstructor
public class PharmacyProfileController {

    private final PharmacyProfileService pharmacyProfileService;
    private final org.springframework.security.access.PermissionEvaluator permissionEvaluator;
    private final zelisline.ub.pharmacy.application.PharmacyDashboardService pharmacyDashboardService;

    /** Counts and the set-up checklist for the pharmacy home screen. */
    @GetMapping("/dashboard")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public zelisline.ub.pharmacy.api.dto.PharmacyDashboardResponse dashboard(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pharmacyDashboardService.dashboard(TenantRequestIds.resolveBusinessId(request), branchId);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public PharmacyProfileResponse get(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        PharmacyProfileResponse out = pharmacyProfileService.get(TenantRequestIds.resolveBusinessId(request));
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean seesCredentials = permissionEvaluator.hasPermission(auth, null, "pharmacy.manage")
                || permissionEvaluator.hasPermission(auth, null, "pharmacy.records.read");
        if (seesCredentials || out.staff() == null) {
            return out;
        }
        // Anyone who can open the pharmacy page sees who dispenses; professional registration
        // numbers are for those who manage the pharmacy or read its records.
        return new PharmacyProfileResponse(
                out.premisesLicenceNo(),
                out.licenceExpiresOn(),
                out.licenceDaysLeft(),
                out.superintendentUserId(),
                out.superintendentName(),
                out.controlledSalesEnabled(),
                out.shortDatedDays(),
                out.staff().stream()
                        .map(s -> new PharmacyProfileResponse.Staff(s.userId(), s.name(), s.title(), null, s.hasPin()))
                        .toList(),
                out.secondCheckEnabled());
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public PharmacyProfileResponse update(
            @Valid @RequestBody UpdatePharmacyProfileRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return pharmacyProfileService.update(TenantRequestIds.resolveBusinessId(request), body, user.userId());
    }

    @PutMapping("/staff/{userId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.manage')")
    public PharmacyProfileResponse updateStaff(
            @PathVariable String userId,
            @Valid @RequestBody UpdateStaffCredentialRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return pharmacyProfileService.updateStaffCredential(
                TenantRequestIds.resolveBusinessId(request), userId, body);
    }
}
