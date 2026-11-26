package zelisline.ub.till.api;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.till.api.dto.ApproveTillAccessRequestBody;
import zelisline.ub.till.api.dto.TillAccessRequestCreateBody;
import zelisline.ub.till.api.dto.TillAccessRequestListResponse;
import zelisline.ub.till.api.dto.TillAccessRequestResponse;
import zelisline.ub.till.application.TillAccessRequestService;
import zelisline.ub.till.application.TillDeviceService;

@Validated
@RestController
@RequestMapping("/api/v1/till-access-requests")
@RequiredArgsConstructor
public class TillAccessRequestsController {

    private final TillAccessRequestService tillAccessRequestService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public TillAccessRequestListResponse list(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false, defaultValue = "pending") String status,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.requireMatchingTenant(request, principal.businessId());
        String validatedBranch = null;
        if (branchId != null && !branchId.isBlank()) {
            validatedBranch = branchResolutionService.requireBranchForLockedRole(
                    principal.roleId(), principal.branchId(), branchId);
        }
        return tillAccessRequestService.listForBusiness(businessId, validatedBranch, status);
    }

    /**
     * "This phone is at this branch — please make it a till."
     *
     * Any signed-in person may ask: the point of the endpoint is that a cashier who cannot reach the owner in person
     * still gets a message to them, with a link that approves or dismisses the request. Empty 204 on purpose — the
     * caller only needs to know the ask was accepted; the row itself is the owner's to see.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> request(
            @Valid @RequestBody TillAccessRequestCreateBody body,
            @RequestHeader(value = TillDeviceService.TILL_DEVICE_HEADER, required = false) String tillDeviceHeader,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.requireMatchingTenant(request, principal.businessId());
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), body.branchId());
        String deviceKey = body.deviceKey() != null && !body.deviceKey().isBlank()
                ? body.deviceKey()
                : tillDeviceHeader;
        tillAccessRequestService.requestAccess(
                businessId,
                principal.userId(),
                validatedBranch,
                deviceKey,
                body.deviceName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public TillAccessRequestResponse approve(
            @PathVariable("id") String id,
            @Valid @RequestBody(required = false) ApproveTillAccessRequestBody body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.requireMatchingTenant(request, principal.businessId());
        String label = body != null ? body.label() : null;
        return tillAccessRequestService.approveById(businessId, id, principal.userId(), label);
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public TillAccessRequestResponse dismiss(
            @PathVariable("id") String id,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.requireMatchingTenant(request, principal.businessId());
        return tillAccessRequestService.dismissById(businessId, id, principal.userId());
    }
}
