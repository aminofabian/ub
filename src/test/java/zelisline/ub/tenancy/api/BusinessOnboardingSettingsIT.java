package zelisline.ub.tenancy.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import zelisline.ub.identity.PermissionCacheProbeController;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PermissionCacheProbeController.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class BusinessOnboardingSettingsIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PERM_MANAGE = "11111111-1111-1111-1111-111111111199";
    private static final String ROLE_OWNER = "22222222-2222-2222-2222-222222222201";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

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

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Onboarding Co");
        business.setSlug("onboarding-co");
        business.setSettings("{}");
        businessRepository.save(business);

        permissionRepository.save(perm(PERM_MANAGE, "business.manage_settings", "Settings"));
        Role ownerRole = role(ROLE_OWNER, "owner");
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, PERM_MANAGE);

        owner = user(TENANT, "owner@onboarding.test", ROLE_OWNER);
        userRepository.save(owner);
    }

    @Test
    void patchOnboardingPersistsStatusStepAndAnswers() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me/onboarding")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "active",
                                  "step": 3,
                                  "answers": {
                                    "storeType": "mini-mart",
                                    "selectedDepartments": ["Grocery", "Beverages"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.step").value(3))
                .andExpect(jsonPath("$.answers.storeType").value("mini-mart"))
                .andExpect(jsonPath("$.answers.selectedDepartments[0]").value("Grocery"));

        mockMvc.perform(get("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.storeType").value("mini-mart"))
                .andExpect(jsonPath("$.onboarding.status").value("active"));
    }

    @Test
    void clearAnswersViaPatchAndListStarterKits() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/me/onboarding/store-section-kits")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("mini-mart"))
                .andExpect(jsonPath("$[0].sections[0]").value("General Shop"));
    }

    private static Permission perm(String id, String key, String label) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setLabel(label);
        return p;
    }

    private static Role role(String id, String key) {
        Role r = new Role();
        r.setId(id);
        r.setRoleKey(key);
        r.setLabel(key);
        r.setSystem(true);
        return r;
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setRoleId(roleId);
        rp.setPermissionId(permissionId);
        rolePermissionRepository.save(rp);
    }

    private static User user(String businessId, String email, String roleId) {
        User u = new User();
        u.setBusinessId(businessId);
        u.setEmail(email);
        u.setName("Owner");
        u.setPasswordHash("hash");
        u.setRoleId(roleId);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }
}
