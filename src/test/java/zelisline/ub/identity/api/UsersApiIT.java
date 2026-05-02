package zelisline.ub.identity.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.PermissionCacheProbeController;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Slice 2 HTTP invariants (PHASE_1_PLAN.md §2.4–2.5): cross-tenant user reads
 * return {@code 404}; permission-denied surfaces as Problem+JSON {@code 403}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PermissionCacheProbeController.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class UsersApiIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static final String PERM_USERS_LIST = "11111111-1111-1111-1111-111111111101";
    private static final String PERM_CATALOG_READ = "11111111-1111-1111-1111-111111111102";

    private static final String ROLE_OWNER = "22222222-2222-2222-2222-222222222201";
    private static final String ROLE_CASHIER = "22222222-2222-2222-2222-222222222202";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRepository userRepository;

    /** Stubs the domain-mapping repository bean required by the application context. */
    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User userA;
    private User userB;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();

        permissionRepository.save(permission(PERM_USERS_LIST, "users.list", "List users"));
        permissionRepository.save(permission(PERM_CATALOG_READ, "catalog.items.read", "Read catalog"));

        Role owner = systemRole(ROLE_OWNER, "owner");
        Role cashier = systemRole(ROLE_CASHIER, "cashier");
        roleRepository.save(owner);
        roleRepository.save(cashier);

        grant(ROLE_OWNER, PERM_USERS_LIST);
        grant(ROLE_OWNER, PERM_CATALOG_READ);
        grant(ROLE_CASHIER, PERM_CATALOG_READ);

        userA = user(TENANT_A, "a@tenant-a.test", ROLE_OWNER);
        userB = user(TENANT_B, "b@tenant-b.test", ROLE_OWNER);
        userRepository.save(userA);
        userRepository.save(userB);

        userRepository.save(user(TENANT_A, "cashier@tenant-a.test", ROLE_CASHIER));
    }

    @Test
    void getUserFromAnotherTenantReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", userB.getId())
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, userA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNotFound())
                .andExpect(contentTypeProblem())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void listUsersAsCashierReturns403ProblemJson() throws Exception {
        User cashier = userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(
                TENANT_A, "cashier@tenant-a.test").orElseThrow();

        mockMvc.perform(get("/api/v1/users")
                        .param("size", "20")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_CASHIER))
                .andExpect(status().isForbidden())
                .andExpect(contentTypeProblem())
                .andExpect(jsonPath("$.type").value("urn:problem:permission-denied"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void permissionCacheProbeAllowsOwnerWithTwoPermissionChecks() throws Exception {
        mockMvc.perform(get("/api/v1/__test/permission-cache-probe")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, userA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.ResultMatcher contentTypeProblem() {
        return result -> {
            String ct = result.getResponse().getContentType();
            org.assertj.core.api.Assertions.assertThat(ct).isNotNull();
            org.assertj.core.api.Assertions.assertThat(ct).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        };
    }

    private static Permission permission(String id, String key, String description) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(description);
        return p;
    }

    private static Role systemRole(String id, String key) {
        Role r = new Role();
        r.setId(id);
        r.setBusinessId(null);
        r.setRoleKey(key);
        r.setName(key);
        r.setSystem(true);
        return r;
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }

    private static User user(String tenant, String email, String roleId) {
        User u = new User();
        u.setBusinessId(tenant);
        u.setEmail(email);
        u.setName("Test");
        u.setRoleId(roleId);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }
}
