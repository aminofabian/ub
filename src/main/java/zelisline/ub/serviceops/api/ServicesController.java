package zelisline.ub.serviceops.api;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import zelisline.ub.serviceops.api.dto.CreateServiceRequest;
import zelisline.ub.serviceops.api.dto.PatchServiceRequest;
import zelisline.ub.serviceops.api.dto.ServicePerformerResponse;
import zelisline.ub.serviceops.api.dto.ServiceResponse;
import zelisline.ub.serviceops.api.dto.SetServiceStaffRequest;
import zelisline.ub.serviceops.application.ServiceCatalogService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Service catalogue of a service business (ADR 0014). Reading is open to anyone who can sell,
 * because the till needs the list; changing it needs {@code services.catalog.manage}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/services")
@RequiredArgsConstructor
public class ServicesController {

    private final ServiceCatalogService serviceCatalogService;
    private final zelisline.ub.payroll.application.StaffProfileService staffProfileService;
    private final zelisline.ub.platform.security.CurrentUserPermissions currentUserPermissions;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'services.catalog.manage')")
    public Page<ServiceResponse> list(
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        Page<ServiceResponse> services = serviceCatalogService.listServices(
                TenantRequestIds.resolveBusinessId(request), categoryId, active, q, page, size);
        // What a service pays its performer is not a seller's business.
        return currentUserPermissions.has(ServiceCatalogService.PERM_MANAGE)
                ? services
                : services.map(ServiceResponse::withoutCommission);
    }

    /** Staff who serve customers, for the till's "who did it" picker. Names only. */
    @GetMapping("/performers")
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'services.catalog.manage')")
    public List<ServicePerformerResponse> performers(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return staffProfileService.commissionPlans(TenantRequestIds.resolveBusinessId(request)).stream()
                .filter(plan -> plan.current() && plan.bookable())
                .map(plan -> new ServicePerformerResponse(plan.staffProfileId(), plan.displayName()))
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'services.catalog.manage')")
    public ServiceResponse get(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        ServiceResponse service = serviceCatalogService.getService(TenantRequestIds.resolveBusinessId(request), id);
        return currentUserPermissions.has(ServiceCatalogService.PERM_MANAGE) ? service : service.withoutCommission();
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceResponse create(@Valid @RequestBody CreateServiceRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return serviceCatalogService.createService(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    public ServiceResponse patch(
            @PathVariable String id,
            @Valid @RequestBody PatchServiceRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return serviceCatalogService.updateService(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @PutMapping("/{id}/staff")
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    public ServiceResponse setStaff(
            @PathVariable String id,
            @Valid @RequestBody SetServiceStaffRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return serviceCatalogService.setServiceStaff(
                TenantRequestIds.resolveBusinessId(request), id, body.staffProfileIds());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        serviceCatalogService.deleteService(TenantRequestIds.resolveBusinessId(request), id);
    }
}
