package zelisline.ub.till.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.till.api.dto.RegisterTillDeviceRequest;
import zelisline.ub.till.api.dto.TillDeviceListResponse;
import zelisline.ub.till.api.dto.TillDeviceResponse;
import zelisline.ub.till.application.TillDeviceService;

@Validated
@RestController
@RequestMapping("/api/v1/till-devices")
@RequiredArgsConstructor
public class TillDevicesController {

    private final TillDeviceService tillDeviceService;
    private final BranchResolutionService branchResolutionService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public ResponseEntity<TillDeviceResponse> register(
            @Valid @RequestBody RegisterTillDeviceRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.requireMatchingTenant(request, principal.businessId());
        String branchId = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), body.branchId());
        String headerDeviceKey = request.getHeader(TillDeviceService.TILL_DEVICE_HEADER);
        TillDeviceResponse created = tillDeviceService.register(
                businessId,
                principal.userId(),
                branchId,
                body,
                headerDeviceKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public TillDeviceListResponse list(
            @RequestParam String branchId,
            @RequestParam(required = false, defaultValue = "false") boolean includeRevoked,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.requireMatchingTenant(request, principal.businessId());
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), branchId);
        return tillDeviceService.list(businessId, validatedBranch, includeRevoked);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public void revoke(@PathVariable("id") String id, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.requireMatchingTenant(request, principal.businessId());
        tillDeviceService.revoke(businessId, id);
    }
}
