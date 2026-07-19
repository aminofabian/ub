package zelisline.ub.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.identity.api.dto.AssignRoleRequest;
import zelisline.ub.identity.api.dto.CreateUserRequest;
import zelisline.ub.identity.api.dto.UpdateUserRequest;
import zelisline.ub.identity.api.dto.UserResponse;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserItemTypeRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;

/**
 * Unit tests for {@link IdentityService} invariants
 * (PHASE_1_PLAN.md §2.4). Focuses on the rules that don't need a database to
 * exercise: cross-tenant 404, last-owner guard, role-belongs-to-tenant guard,
 * PIN-with-tenant-salt hashing.
 */
@ExtendWith(MockitoExtension.class)
class IdentityServiceTest {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000001";
    private static final String ROLE_CASHIER = "22222222-0000-0000-0000-000000000004";

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private UserItemTypeRepository userItemTypeRepository;
    @Mock private ItemTypeRepository itemTypeRepository;
    @Mock private UserSessionRepository userSessionRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks
    private IdentityService identityService;

    private Role ownerRole;
    private Role cashierRole;

    @BeforeEach
    void seedRoles() {
        ownerRole = systemRole(ROLE_OWNER, "owner");
        cashierRole = systemRole(ROLE_CASHIER, "cashier");
    }

    // ---------- createUser --------------------------------------------------

    @Test
    void createUserHashesPasswordAndPin() {
        given(userRepository.existsByBusinessIdAndEmailAndDeletedAtIsNull(TENANT_A, "owner@example.com"))
                .willReturn(false);
        given(roleRepository.findByIdAndDeletedAtIsNull(ROLE_OWNER)).willReturn(Optional.of(ownerRole));
        given(passwordEncoder.encode(anyString())).willAnswer(inv -> "enc:" + inv.getArgument(0));
        given(userRepository.save(any(User.class))).willAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId("user-1");
            return u;
        });
        given(permissionRepository.findPermissionKeysByRoleId(ROLE_OWNER))
                .willReturn(List.of("users.create", "business.manage_settings"));

        UserResponse response = identityService.createUser(TENANT_A, new CreateUserRequest(
                "Owner@Example.com", "Owner Name", null, ROLE_OWNER, null, null,
                "correct-horse-battery-staple", "1234", null
        ));

        assertThat(response.email()).isEqualTo("owner@example.com");
        assertThat(response.permissions())
                .containsExactlyInAnyOrder("users.create", "business.manage_settings");
        verify(passwordEncoder).encode("correct-horse-battery-staple");
        verify(passwordEncoder).encode(TENANT_A + ":1234");
    }

    @Test
    void createUserRejectsDuplicateEmail() {
        given(userRepository.existsByBusinessIdAndEmailAndDeletedAtIsNull(TENANT_A, "owner@example.com"))
                .willReturn(true);

        ResponseStatusException ex = catchThrowableOfType(
                () -> identityService.createUser(TENANT_A,
                        new CreateUserRequest("owner@example.com", "Owner", null, ROLE_OWNER, null, null,
                                "password-12345", null, null)),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createUserRejectsRoleFromOtherTenant() {
        given(userRepository.existsByBusinessIdAndEmailAndDeletedAtIsNull(eq(TENANT_A), anyString()))
                .willReturn(false);
        Role foreignRole = tenantRole("role-foreign", "owner", TENANT_B);
        given(roleRepository.findByIdAndDeletedAtIsNull("role-foreign")).willReturn(Optional.of(foreignRole));

        ResponseStatusException ex = catchThrowableOfType(
                () -> identityService.createUser(TENANT_A,
                        new CreateUserRequest("a@b.com", "X", null, "role-foreign", null, null,
                                "password-12345", null, null)),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createUserRequiresPasswordOrPin() {
        given(userRepository.existsByBusinessIdAndEmailAndDeletedAtIsNull(TENANT_A, "a@b.com"))
                .willReturn(false);
        given(roleRepository.findByIdAndDeletedAtIsNull(ROLE_OWNER)).willReturn(Optional.of(ownerRole));

        ResponseStatusException ex = catchThrowableOfType(
                () -> identityService.createUser(TENANT_A,
                        new CreateUserRequest("a@b.com", "Z", null, ROLE_OWNER, null, null, null, null, null)),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
    }

    // ---------- cross-tenant -----------------------------------------------

    @Test
    void getUserReturns404WhenLookupCrossesTenant() {
        given(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull("u-1", TENANT_B))
                .willReturn(Optional.empty());

        ResponseStatusException ex = catchThrowableOfType(
                () -> identityService.getUser(TENANT_B, "u-1"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------- last-owner guard --------------------------------------------

    @Test
    void assignRoleBlocksDemotingTheLastOwner() {
        User user = ownerUserOf(TENANT_A);
        given(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(user.getId(), TENANT_A))
                .willReturn(Optional.of(user));
        given(roleRepository.findByIdAndDeletedAtIsNull(ROLE_CASHIER)).willReturn(Optional.of(cashierRole));
        given(roleRepository.findById(ROLE_OWNER)).willReturn(Optional.of(ownerRole));
        given(userRepository.countActiveByRoleKey(TENANT_A, "owner")).willReturn(1L);

        ResponseStatusException ex = catchThrowableOfType(
                () -> identityService.assignRole(TENANT_A, user.getId(),
                        new AssignRoleRequest(ROLE_CASHIER)),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(userRepository, never()).save(any());
    }

    @Test
    void assignRoleAllowsDemotingWhenAnotherOwnerExists() {
        User user = ownerUserOf(TENANT_A);
        given(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(user.getId(), TENANT_A))
                .willReturn(Optional.of(user));
        given(roleRepository.findByIdAndDeletedAtIsNull(ROLE_CASHIER)).willReturn(Optional.of(cashierRole));
        given(roleRepository.findById(ROLE_OWNER)).willReturn(Optional.of(ownerRole));
        given(userRepository.countActiveByRoleKey(TENANT_A, "owner")).willReturn(2L);
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(permissionRepository.findPermissionKeysByRoleId(ROLE_CASHIER))
                .willReturn(List.of("catalog.items.read"));

        UserResponse response = identityService.assignRole(TENANT_A, user.getId(),
                new AssignRoleRequest(ROLE_CASHIER));

        assertThat(response.role().key()).isEqualTo("cashier");
    }

    @Test
    void deactivateBlocksTheLastOwner() {
        User user = ownerUserOf(TENANT_A);
        given(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(user.getId(), TENANT_A))
                .willReturn(Optional.of(user));
        given(roleRepository.findById(ROLE_OWNER)).willReturn(Optional.of(ownerRole));
        given(userRepository.countActiveByRoleKey(TENANT_A, "owner")).willReturn(1L);

        ResponseStatusException ex = catchThrowableOfType(
                () -> identityService.deactivateUser(TENANT_A, user.getId()),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateUserStatusToSuspendedBlocksLastOwner() {
        User user = ownerUserOf(TENANT_A);
        given(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(user.getId(), TENANT_A))
                .willReturn(Optional.of(user));
        given(roleRepository.findById(ROLE_OWNER)).willReturn(Optional.of(ownerRole));
        given(userRepository.countActiveByRoleKey(TENANT_A, "owner")).willReturn(1L);

        ResponseStatusException ex = catchThrowableOfType(
                () -> identityService.updateUser(TENANT_A, user.getId(),
                        new UpdateUserRequest(null, null, null, "suspended")),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------- setUserPassword ---------------------------------------------

    @Test
    void setUserPasswordHashesAndRevokesSessions() {
        User user = ownerUserOf(TENANT_A);
        given(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(user.getId(), TENANT_A))
                .willReturn(Optional.of(user));
        given(passwordEncoder.encode("new-secret-password")).willReturn("enc:new-secret-password");
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(roleRepository.findById(ROLE_OWNER)).willReturn(Optional.of(ownerRole));
        given(permissionRepository.findPermissionKeysByRoleId(ROLE_OWNER)).willReturn(List.of());

        identityService.setUserPassword(TENANT_A, user.getId(), "new-secret-password");

        assertThat(user.getPasswordHash()).isEqualTo("enc:new-secret-password");
        assertThat(user.statusAsEnum()).isEqualTo(UserStatus.ACTIVE);
        verify(passwordEncoder).encode("new-secret-password");
        verify(userSessionRepository).revokeAllActiveForUser(eq(user.getId()), any(Instant.class));
    }

    @Test
    void setUserPasswordActivatesInvitedUser() {
        User user = ownerUserOf(TENANT_A);
        user.setStatus(UserStatus.INVITED);
        given(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(user.getId(), TENANT_A))
                .willReturn(Optional.of(user));
        given(passwordEncoder.encode(anyString())).willReturn("enc:pw");
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(roleRepository.findById(ROLE_OWNER)).willReturn(Optional.of(ownerRole));
        given(permissionRepository.findPermissionKeysByRoleId(ROLE_OWNER)).willReturn(List.of());

        UserResponse response = identityService.setUserPassword(TENANT_A, user.getId(), "password123");

        assertThat(user.statusAsEnum()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.status()).isEqualTo("active");
    }

    @Test
    void setUserPasswordRejectsOtherTenant() {
        given(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull("user-1", TENANT_A))
                .willReturn(Optional.empty());

        ResponseStatusException ex = catchThrowableOfType(
                () -> identityService.setUserPassword(TENANT_A, "user-1", "password123"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(userRepository, never()).save(any(User.class));
        verify(userSessionRepository, never()).revokeAllActiveForUser(anyString(), any(Instant.class));
    }

    // ---------- helpers -----------------------------------------------------

    private static Role systemRole(String id, String key) {
        Role r = new Role();
        r.setId(id);
        r.setRoleKey(key);
        r.setName(key);
        r.setSystem(true);
        return r;
    }

    private static Role tenantRole(String id, String key, String tenantId) {
        Role r = new Role();
        r.setId(id);
        r.setRoleKey(key);
        r.setName(key);
        r.setBusinessId(tenantId);
        r.setSystem(false);
        return r;
    }

    private static User ownerUserOf(String tenant) {
        User u = new User();
        u.setId("user-1");
        u.setBusinessId(tenant);
        u.setEmail("owner@example.com");
        u.setName("Owner");
        u.setRoleId(ROLE_OWNER);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }
}
