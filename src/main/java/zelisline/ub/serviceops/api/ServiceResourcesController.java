package zelisline.ub.serviceops.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.serviceops.api.dto.ResourceDtos.CreateResourceRequest;
import zelisline.ub.serviceops.api.dto.ResourceDtos.ResourceListResponse;
import zelisline.ub.serviceops.api.dto.ResourceDtos.ResourceResponse;
import zelisline.ub.serviceops.api.dto.ResourceDtos.SetServiceResourceTypeRequest;
import zelisline.ub.serviceops.api.dto.ResourceDtos.UpdateResourceRequest;
import zelisline.ub.serviceops.application.ServiceResourceService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

/** Chairs, beds, rooms and machines of a branch (ADR 0016). */
@Validated
@RestController
@RequestMapping("/api/v1/service-resources")
@RequiredArgsConstructor
public class ServiceResourcesController {

    private final ServiceResourceService resourceService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'services.catalog.manage')")
    public ResourceListResponse list(@RequestParam String branchId, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(), branchId);
        return new ResourceListResponse(branch, resourceService.list(TenantRequestIds.resolveBusinessId(request), branch));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    public ResourceResponse create(@Valid @RequestBody CreateResourceRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(
                caller.roleId(), caller.branchId(), body.branchId());
        return resourceService.create(TenantRequestIds.resolveBusinessId(request),
                new CreateResourceRequest(branch, body.name(), body.resourceType(), body.cleanupMinutes()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    public ResourceResponse update(
            @PathVariable String id, @Valid @RequestBody UpdateResourceRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return resourceService.update(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    /** Which kind of resource a service needs. */
    @PutMapping("/requirements/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    public void setRequirement(
            @PathVariable String serviceId, @Valid @RequestBody SetServiceResourceTypeRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        resourceService.setServiceResourceType(TenantRequestIds.resolveBusinessId(request), serviceId, body.resourceType());
    }
}
