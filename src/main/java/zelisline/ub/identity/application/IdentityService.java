package zelisline.ub.identity.application;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.AssignRoleRequest;
import zelisline.ub.identity.api.dto.CreateRoleRequest;
import zelisline.ub.identity.api.dto.CreateUserRequest;
import zelisline.ub.identity.api.dto.PermissionResponse;
import zelisline.ub.identity.api.dto.RoleResponse;
import zelisline.ub.identity.api.dto.UpdateMeRequest;
import zelisline.ub.identity.api.dto.UpdateRoleRequest;
import zelisline.ub.identity.api.dto.UpdateUserRequest;
import zelisline.ub.identity.api.dto.UserResponse;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;

/**
 * Use-case orchestration for Slice 2 — Identity primitives
 * (PHASE_1_PLAN.md §2.3, §2.4).
 *
 * <p>Tenant isolation is enforced here, not in the controllers. Every read or
 * write that touches a tenant-scoped entity passes a {@code businessId} that
 * the controller obtains from the request's tenant context — never from the
 * client. Cross-tenant lookups return {@link HttpStatus#NOT_FOUND} (Slice 2 DoD,
 * §2.5: "{@code GET /users/{id}} returns 404 (not 403, not 200)").
 */
@Service
@RequiredArgsConstructor
public class IdentityService {

    /** System role key that triggers the last-owner guard (§2.4 invariant 1). */
    public static final String OWNER_ROLE_KEY = "owner";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PasswordEncoder passwordEncoder;

    // ---------- Users -------------------------------------------------------

    @Transactional
    public UserResponse createUser(String businessId, CreateUserRequest request) {
        requireTenant(businessId);

        String email = normaliseEmail(request.email());
        if (userRepository.existsByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A user with this email already exists in this tenant");
        }

        Role role = requireRoleAssignableToTenant(businessId, request.roleId());

        if ((request.password() == null || request.password().isBlank())
                && (request.pin() == null || request.pin().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Either password or PIN must be provided");
        }

        UserStatus status = parseStatus(request.status(), UserStatus.ACTIVE);

        User user = new User();
        user.setBusinessId(businessId);
        user.setBranchId(blankToNull(request.branchId()));
        user.setEmail(email);
        user.setName(request.name().trim());
        user.setPhone(blankToNull(request.phone()));
        user.setRoleId(role.getId());
        user.setStatus(status);

        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (request.pin() != null && !request.pin().isBlank()) {
            user.setPinHash(encodePin(businessId, request.pin()));
        }

        User saved = userRepository.save(user);
        return toResponse(saved, role);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(
            String businessId,
            Pageable pageable,
            String statusFilter,
            String roleIdFilter,
            String branchIdFilter
    ) {
        requireTenant(businessId);
        String statusWire = blankToNull(statusFilter);
        if (statusWire != null) {
            try {
                UserStatus.fromWire(statusWire);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status filter");
            }
        }
        String roleId = blankToNull(roleIdFilter);
        String branchId = blankToNull(branchIdFilter);
        Page<User> page = userRepository.pageByBusinessFiltered(
                businessId,
                statusWire,
                roleId,
                branchId,
                pageable
        );
        if (page.isEmpty()) {
            return page.map(u -> toResponse(u, null));
        }

        Map<String, Role> rolesById = loadRolesById(
                page.getContent().stream().map(User::getRoleId).collect(Collectors.toSet())
        );
        return page.map(u -> toResponse(u, rolesById.get(u.getRoleId())));
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(String businessId, String userId) {
        User user = requireTenantUser(businessId, userId);
        Role role = roleRepository.findById(user.getRoleId()).orElse(null);
        return toResponse(user, role);
    }

    @Transactional
    public UserResponse updateUser(String businessId, String userId, UpdateUserRequest request) {
        User user = requireTenantUser(businessId, userId);

        if (request.name() != null && !request.name().isBlank()) {
            user.setName(request.name().trim());
        }
        if (request.phone() != null) {
            user.setPhone(blankToNull(request.phone()));
        }
        if (request.branchId() != null) {
            user.setBranchId(blankToNull(request.branchId()));
        }
        if (request.status() != null && !request.status().isBlank()) {
            UserStatus newStatus = parseStatus(request.status(), user.statusAsEnum());
            if (newStatus != UserStatus.ACTIVE
                    && user.statusAsEnum() == UserStatus.ACTIVE
                    && hasOwnerRole(user.getRoleId())) {
                guardLastOwner(businessId, "deactivate the last owner");
            }
            user.setStatus(newStatus);
        }

        User saved = userRepository.save(user);
        Role role = roleRepository.findById(saved.getRoleId()).orElse(null);
        return toResponse(saved, role);
    }

    @Transactional
    public UserResponse assignRole(String businessId, String userId, AssignRoleRequest request) {
        User user = requireTenantUser(businessId, userId);
        Role newRole = requireRoleAssignableToTenant(businessId, request.roleId());

        if (Objects.equals(user.getRoleId(), newRole.getId())) {
            return toResponse(user, newRole);
        }

        if (hasOwnerRole(user.getRoleId()) && !OWNER_ROLE_KEY.equals(newRole.getRoleKey())) {
            guardLastOwner(businessId, "demote the last owner");
        }

        user.setRoleId(newRole.getId());
        User saved = userRepository.save(user);
        return toResponse(saved, newRole);
    }

    @Transactional
    public UserResponse deactivateUser(String businessId, String userId) {
        User user = requireTenantUser(businessId, userId);

        if (user.statusAsEnum() != UserStatus.ACTIVE) {
            // Idempotent: already inactive.
            Role role = roleRepository.findById(user.getRoleId()).orElse(null);
            return toResponse(user, role);
        }

        if (hasOwnerRole(user.getRoleId())) {
            guardLastOwner(businessId, "deactivate the last owner");
        }
        user.setStatus(UserStatus.SUSPENDED);
        User saved = userRepository.save(user);
        Role role = roleRepository.findById(saved.getRoleId()).orElse(null);
        return toResponse(saved, role);
    }

    // ---------- Self --------------------------------------------------------

    @Transactional(readOnly = true)
    public UserResponse getMe(String businessId, String userId) {
        return getUser(businessId, userId);
    }

    @Transactional
    public UserResponse updateMe(String businessId, String userId, UpdateMeRequest request) {
        User user = requireTenantUser(businessId, userId);
        if (request.name() != null && !request.name().isBlank()) {
            user.setName(request.name().trim());
        }
        if (request.phone() != null) {
            user.setPhone(blankToNull(request.phone()));
        }
        User saved = userRepository.save(user);
        Role role = roleRepository.findById(saved.getRoleId()).orElse(null);
        return toResponse(saved, role);
    }

    // ---------- Roles -------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles(String businessId) {
        requireTenant(businessId);
        List<Role> roles = roleRepository.findVisibleForTenant(businessId);
        return roles.stream().map(this::toRoleResponse).toList();
    }

    @Transactional
    public RoleResponse createRole(String businessId, CreateRoleRequest request) {
        requireTenant(businessId);

        Role role = new Role();
        role.setBusinessId(businessId);
        role.setRoleKey(request.roleKey());
        role.setName(request.name().trim());
        role.setDescription(blankToNull(request.description()));
        role.setSystem(false);

        Role saved = roleRepository.save(role);
        replaceRolePermissions(saved.getId(), request.permissionIds());
        return toRoleResponse(saved);
    }

    @Transactional
    public RoleResponse updateRole(String businessId, String roleId, UpdateRoleRequest request) {
        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found"));

        if (role.isSystem()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "System roles cannot be edited");
        }
        if (!businessId.equals(role.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found");
        }

        if (request.name() != null && !request.name().isBlank()) {
            role.setName(request.name().trim());
        }
        if (request.description() != null) {
            role.setDescription(blankToNull(request.description()));
        }
        if (request.permissionIds() != null) {
            replaceRolePermissions(role.getId(), request.permissionIds());
        }

        Role saved = roleRepository.save(role);
        return toRoleResponse(saved);
    }

    // ---------- Permissions -------------------------------------------------

    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions() {
        return permissionRepository.findAll().stream()
                .map(p -> new PermissionResponse(p.getId(), p.getPermissionKey(), p.getDescription()))
                .toList();
    }

    // ---------- Internals ---------------------------------------------------

    private User requireTenantUser(String businessId, String userId) {
        requireTenant(businessId);
        return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private Role requireRoleAssignableToTenant(String businessId, String roleId) {
        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role"));
        boolean isSystem = role.getBusinessId() == null;
        boolean sameTenant = businessId.equals(role.getBusinessId());
        if (!isSystem && !sameTenant) {
            // The role belongs to a different tenant. Surface as BAD_REQUEST so
            // it cannot be used as a tenant-discovery oracle.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role does not belong to this tenant");
        }
        return role;
    }

    private boolean hasOwnerRole(String roleId) {
        return roleRepository.findById(roleId)
                .map(r -> OWNER_ROLE_KEY.equals(r.getRoleKey()))
                .orElse(false);
    }

    private void guardLastOwner(String businessId, String action) {
        long owners = userRepository.countActiveByRoleKey(businessId, OWNER_ROLE_KEY);
        if (owners <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot " + action + " of this tenant");
        }
    }

    private void requireTenant(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant context is required");
        }
    }

    private void replaceRolePermissions(String roleId, List<String> permissionIds) {
        if (permissionIds == null) {
            return;
        }
        rolePermissionRepository.deleteByIdRoleId(roleId);
        Set<String> deduped = new HashSet<>();
        for (String permissionId : permissionIds) {
            if (permissionId == null || permissionId.isBlank() || !deduped.add(permissionId)) {
                continue;
            }
            if (!permissionRepository.existsById(permissionId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown permission: " + permissionId);
            }
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(roleId, permissionId));
            rolePermissionRepository.save(rp);
        }
    }

    private Map<String, Role> loadRolesById(Set<String> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return roleRepository.findAllById(roleIds).stream()
                .collect(Collectors.toMap(Role::getId, r -> r));
    }

    private UserResponse toResponse(User user, Role role) {
        UserResponse.RoleSummary roleSummary = role == null
                ? null
                : new UserResponse.RoleSummary(role.getId(), role.getRoleKey(), role.getName(), role.isSystem());
        List<String> permissions = role == null
                ? List.of()
                : permissionRepository.findPermissionKeysByRoleId(role.getId());
        return new UserResponse(
                user.getId(),
                user.getBusinessId(),
                user.getBranchId(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                user.getStatus(),
                roleSummary,
                permissions,
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    private RoleResponse toRoleResponse(Role role) {
        List<String> grants = permissionRepository.findPermissionKeysByRoleId(role.getId());
        return new RoleResponse(
                role.getId(),
                role.getBusinessId(),
                role.getRoleKey(),
                role.getName(),
                role.getDescription(),
                role.isSystem(),
                grants
        );
    }

    private String encodePin(String businessId, String pin) {
        // Per PHASE_1_PLAN.md §3.2 PIN row: bcrypt over (business_id || ":" || pin)
        // — defends against cross-tenant rainbow tests if a PIN dump leaks.
        return passwordEncoder.encode(businessId + ":" + pin);
    }

    private String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private UserStatus parseStatus(String wire, UserStatus fallback) {
        if (wire == null || wire.isBlank()) {
            return fallback;
        }
        try {
            return UserStatus.fromWire(wire);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown user status: " + wire);
        }
    }

}
