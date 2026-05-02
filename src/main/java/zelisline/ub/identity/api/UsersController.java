package zelisline.ub.identity.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import zelisline.ub.identity.api.dto.AssignRoleRequest;
import zelisline.ub.identity.api.dto.CreateUserRequest;
import zelisline.ub.identity.api.dto.UpdateUserRequest;
import zelisline.ub.identity.api.dto.UserResponse;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UsersController {

    private final IdentityService identityService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'users.list')")
    public Page<UserResponse> listUsers(
            Pageable pageable,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return identityService.listUsers(
                TenantRequestIds.resolveBusinessId(request),
                pageable,
                status,
                roleId,
                branchId
        );
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'users.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(
            @Valid @RequestBody CreateUserRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return identityService.createUser(TenantRequestIds.resolveBusinessId(request), body);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasPermission(null, 'users.list')")
    public UserResponse getUser(@PathVariable String userId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return identityService.getUser(TenantRequestIds.resolveBusinessId(request), userId);
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasPermission(null, 'users.update')")
    public UserResponse updateUser(
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return identityService.updateUser(TenantRequestIds.resolveBusinessId(request), userId, body);
    }

    @PostMapping("/{userId}/deactivate")
    @PreAuthorize("hasPermission(null, 'users.deactivate')")
    public UserResponse deactivateUser(@PathVariable String userId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return identityService.deactivateUser(TenantRequestIds.resolveBusinessId(request), userId);
    }

    @PostMapping("/{userId}/role")
    @PreAuthorize("hasPermission(null, 'users.assign_role')")
    public UserResponse assignRole(
            @PathVariable String userId,
            @Valid @RequestBody AssignRoleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return identityService.assignRole(TenantRequestIds.resolveBusinessId(request), userId, body);
    }
}
