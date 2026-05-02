package zelisline.ub.identity.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.CreateRoleRequest;
import zelisline.ub.identity.api.dto.RoleResponse;
import zelisline.ub.identity.api.dto.UpdateRoleRequest;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RolesController {

    private final IdentityService identityService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'roles.list')")
    public List<RoleResponse> listRoles(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return identityService.listRoles(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'roles.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse createRole(
            @Valid @RequestBody CreateRoleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return identityService.createRole(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/{roleId}")
    @PreAuthorize("hasPermission(null, 'roles.update')")
    public RoleResponse updateRole(
            @PathVariable String roleId,
            @Valid @RequestBody UpdateRoleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return identityService.updateRole(TenantRequestIds.resolveBusinessId(request), roleId, body);
    }
}
