package zelisline.ub.identity.application;

import java.time.Instant;
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
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.identity.api.dto.AssignRoleRequest;
import zelisline.ub.identity.api.dto.CreateRoleRequest;
import zelisline.ub.identity.api.dto.CreateUserRequest;
import zelisline.ub.identity.api.dto.PermissionResponse;
import zelisline.ub.identity.api.dto.RoleResponse;
import zelisline.ub.identity.api.dto.UpdateMeRequest;
import zelisline.ub.identity.api.dto.UpdateRoleRequest;
import zelisline.ub.identity.api.dto.UpdateUserRequest;
import zelisline.ub.identity.api.dto.UserPinResponse;
import zelisline.ub.identity.api.dto.UserResponse;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserItemType;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserItemTypeRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;

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
    private final UserItemTypeRepository userItemTypeRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final CredentialEncryptionService credentialEncryptionService;

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

        boolean invite = Boolean.TRUE.equals(request.sendInvite());
        boolean hasPassword = request.password() != null && !request.password().isBlank();
        boolean hasPin = request.pin() != null && !request.pin().isBlank();
        if (!invite && !hasPassword && !hasPin) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Either a password, a PIN, or an email invitation must be provided");
        }

        // Invited users have no credentials yet — they stay INVITED (login blocked)
        // until they accept the email invite and set their own password.
        UserStatus status = parseStatus(request.status(), invite ? UserStatus.INVITED : UserStatus.ACTIVE);

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
            applyPin(user, businessId, request.pin().trim());
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

    /**
     * Admin sets a user's password without knowing the current one.
     * Invited users become active (same as accepting an invite via reset link).
     * All of the user's sessions are revoked so the new credential takes effect.
     */
    @Transactional
    public UserResponse setUserPassword(String businessId, String userId, String newPassword) {
        User user = requireTenantUser(businessId, userId);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        if (user.statusAsEnum() == UserStatus.INVITED) {
            user.setStatus(UserStatus.ACTIVE);
        }
        User saved = userRepository.save(user);
        userSessionRepository.revokeAllActiveForUser(saved.getId(), Instant.now());
        Role role = roleRepository.findById(saved.getRoleId()).orElse(null);
        return toResponse(saved, role);
    }

    /**
     * Admin sets a user's till PIN without knowing the current one.
     * Stores bcrypt hash for auth and an encrypted copy for admin reveal.
     * Invited users become active. Sessions are revoked so till unlock uses the new PIN.
     */
    @Transactional
    public UserResponse setUserPin(String businessId, String userId, String pin) {
        User user = requireTenantUser(businessId, userId);
        applyPin(user, businessId, pin.trim());
        if (user.statusAsEnum() == UserStatus.INVITED) {
            user.setStatus(UserStatus.ACTIVE);
        }
        User saved = userRepository.save(user);
        userSessionRepository.revokeAllActiveForUser(saved.getId(), Instant.now());
        Role role = roleRepository.findById(saved.getRoleId()).orElse(null);
        return toResponse(saved, role);
    }

    /**
     * Admin reveal of a till PIN. Returns the decrypted value only when
     * {@code pin_enc} is present; legacy hash-only rows are not recoverable.
     */
    @Transactional(readOnly = true)
    public UserPinResponse getUserPin(String businessId, String userId) {
        User user = requireTenantUser(businessId, userId);
        boolean hasPin = user.getPinHash() != null && !user.getPinHash().isBlank();
        if (!hasPin) {
            return new UserPinResponse(false, false, null);
        }
        String enc = user.getPinEnc();
        if (enc == null || enc.isBlank()) {
            return new UserPinResponse(true, false, null);
        }
        try {
            String pin = credentialEncryptionService.decrypt(enc);
            if (pin == null || pin.isBlank()) {
                return new UserPinResponse(true, false, null);
            }
            return new UserPinResponse(true, true, pin);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to decrypt stored PIN. Re-set the PIN, or check APP_PAYMENTS_ENCRYPTION_KEY.");
        }
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
        List<String> itemTypeIds = userItemTypeRepository.findItemTypeIdsByUserId(user.getId());
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
                itemTypeIds,
                user.getPinHash() != null && !user.getPinHash().isBlank(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    // ---------- User ↔ item type assignments --------------------------------

    /**
     * Read the department (item type) IDs assigned to a user. Returned in
     * insertion order from the join table.
     */
    @Transactional(readOnly = true)
    public List<String> listItemTypeIdsForUser(String businessId, String userId) {
        requireTenantUser(businessId, userId);
        return userItemTypeRepository.findItemTypeIdsByUserId(userId);
    }

    /**
     * Replace the user's department assignments with the given set. All IDs
     * are validated to belong to the calling tenant; unknown / cross-tenant
     * IDs cause a {@code 400 BAD_REQUEST}.
     */
    @Transactional
    public List<String> setItemTypeIdsForUser(
            String businessId,
            String userId,
            List<String> itemTypeIds
    ) {
        requireTenantUser(businessId, userId);
        List<String> deduped = dedupeNonBlank(itemTypeIds);
        for (String itemTypeId : deduped) {
            itemTypeRepository.findByIdAndBusinessId(itemTypeId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Unknown item type: " + itemTypeId));
        }
        userItemTypeRepository.deleteAllByUserId(userId);
        userItemTypeRepository.flush();
        for (String itemTypeId : deduped) {
            UserItemType row = new UserItemType();
            row.setId(new UserItemType.Id(userId, itemTypeId));
            userItemTypeRepository.save(row);
        }
        return userItemTypeRepository.findItemTypeIdsByUserId(userId);
    }

    private List<String> dedupeNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<String> kept = new java.util.ArrayList<>(values.size());
        for (String v : values) {
            if (v == null) continue;
            String trimmed = v.trim();
            if (trimmed.isEmpty()) continue;
            if (seen.add(trimmed)) {
                kept.add(trimmed);
            }
        }
        return kept;
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

    private void applyPin(User user, String businessId, String pin) {
        user.setPinHash(encodePin(businessId, pin));
        user.setPinEnc(credentialEncryptionService.encryptSecret(pin));
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
