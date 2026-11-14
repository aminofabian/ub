package zelisline.ub.serviceops.api;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.serviceops.api.dto.HelperRoleDtos.HelperRoleListResponse;
import zelisline.ub.serviceops.api.dto.HelperRoleDtos.HelperRoleResponse;
import zelisline.ub.serviceops.api.dto.HelperRoleDtos.SaveHelperRoleRequest;
import zelisline.ub.serviceops.application.ServiceHelperRoleService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * The usual helpers on a service and what each is paid (ADR 0016, amendment 4). Anyone at the
 * till can read them; saying what a helper is paid is a commission decision.
 */
@Validated
@RestController
@RequestMapping("/api/v1/service-helper-roles")
@RequiredArgsConstructor
public class ServiceHelperRolesController {

    private final ServiceHelperRoleService roleService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'services.catalog.manage')"
            + " or hasPermission(null, 'commissions.plans.manage')")
    public HelperRoleListResponse list(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return new HelperRoleListResponse(roleService.list(TenantRequestIds.resolveBusinessId(request)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'commissions.plans.manage')")
    public HelperRoleResponse create(@Valid @RequestBody SaveHelperRoleRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return roleService.create(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'commissions.plans.manage')")
    public HelperRoleResponse update(
            @PathVariable String id, @Valid @RequestBody SaveHelperRoleRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return roleService.update(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'commissions.plans.manage')")
    public void delete(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        roleService.delete(TenantRequestIds.resolveBusinessId(request), id);
    }
}
