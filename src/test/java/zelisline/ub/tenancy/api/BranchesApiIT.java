package zelisline.ub.tenancy.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.identity.PermissionCacheProbeController;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Slice 1 — branches endpoints (PHASE_1_PLAN.md §1.3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PermissionCacheProbeController.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class BranchesApiIT {

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PERM_MANAGE = "11111111-1111-1111-1111-111111111199";
    private static final String PERM_LIST = "11111111-1111-1111-1111-111111111101";
    private static final String ROLE_OWNER = "22222222-2222-2222-2222-222222222201";
    private static final String ROLE_CASHIER = "22222222-2222-2222-2222-222222222202";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private User cashier;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Tenant A");
        business.setSlug("tenant-branches");
        businessRepository.save(business);

        permissionRepository.save(perm(PERM_MANAGE, "business.manage_settings", "Settings"));
        permissionRepository.save(perm(PERM_LIST, "users.list", "List users"));

        Role ownerRole = role(ROLE_OWNER, "owner");
        Role cashierRole = role(ROLE_CASHIER, "cashier");
        roleRepository.save(ownerRole);
        roleRepository.save(cashierRole);

        grant(ROLE_OWNER, PERM_MANAGE);
        grant(ROLE_OWNER, PERM_LIST);
        grant(ROLE_CASHIER, PERM_LIST);

        owner = user(TENANT, "owner@branches.test", ROLE_OWNER);
        cashier = user(TENANT, "cashier@branches.test", ROLE_CASHIER);
        userRepository.save(owner);
        userRepository.save(cashier);
    }

    @Test
    void createListPatchBranch() throws Exception {
        mockMvc.perform(post("/api/v1/branches")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Main Store","address":"Nairobi"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Main Store"))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/v1/branches")
                        .param("size", "20")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_CASHIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Main Store"));

        MvcResult listed = mockMvc.perform(get("/api/v1/branches")
                        .param("size", "20")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        String branchId = JsonPath.read(listed.getResponse().getContentAsString(), "$.content[0].id");

        mockMvc.perform(patch("/api/v1/branches/" + branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Flagship\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Flagship"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void cashierCannotCreateBranch() throws Exception {
        mockMvc.perform(post("/api/v1/branches")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_CASHIER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    private static Permission perm(String id, String key, String description) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(description);
        return p;
    }

    private static Role role(String id, String key) {
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
        u.setName("User");
        u.setRoleId(roleId);
        u.setStatus(zelisline.ub.identity.domain.UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }
}
