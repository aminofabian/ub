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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Phase 15 — Slice 1: storefront settings under {@code businesses.settings.storefront}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PermissionCacheProbeController.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class BusinessStorefrontSettingsIT {

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PERM_MANAGE = "11111111-1111-1111-1111-111111111199";
    private static final String ROLE_OWNER = "22222222-2222-2222-2222-222222222201";

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

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private String branchActiveId;
    private String branchInactiveId;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Shop A");
        business.setSlug("shop-storefront-it");
        businessRepository.save(business);

        Branch active = new Branch();
        active.setBusinessId(TENANT);
        active.setName("Main");
        active.setActive(true);
        branchActiveId = branchRepository.save(active).getId();

        Branch inactive = new Branch();
        inactive.setBusinessId(TENANT);
        inactive.setName("Closed");
        inactive.setActive(false);
        branchInactiveId = branchRepository.save(inactive).getId();

        permissionRepository.save(perm(PERM_MANAGE, "business.manage_settings", "Settings"));

        Role ownerRole = role(ROLE_OWNER, "owner");
        roleRepository.save(ownerRole);

        grant(ROLE_OWNER, PERM_MANAGE);

        owner = user(TENANT, "owner@storefront.test", ROLE_OWNER);
        userRepository.save(owner);
    }

    @Test
    void getDefaultsStorefrontDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storefront.enabled").value(false));
    }

    @Test
    void patchEnableWithoutBranchReturns400() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"storefront\":{\"enabled\":true}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchEnableInactiveBranchReturns400() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"storefront":{"enabled":true,"catalogBranchId":"%s"}}
                                """.formatted(branchInactiveId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchEnableActiveBranchPersistsAndReturnsStorefront() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"storefront":{"enabled":true,"catalogBranchId":"%s",
                                  "label":"Shop","announcement":"Hello"}}
                                """.formatted(branchActiveId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storefront.enabled").value(true))
                .andExpect(jsonPath("$.storefront.catalogBranchId").value(branchActiveId))
                .andExpect(jsonPath("$.storefront.label").value("Shop"))
                .andExpect(jsonPath("$.storefront.announcement").value("Hello"));

        entityManager.clear();

        mockMvc.perform(get("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storefront.enabled").value(true))
                .andExpect(jsonPath("$.storefront.catalogBranchId").value(branchActiveId));
    }

    @Test
    void patchFeaturedIdsRejectsInvalidUuid() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"storefront":{"enabled":true,"catalogBranchId":"%s",
                                  "featuredItemIds":["not-a-uuid"]}}
                                """.formatted(branchActiveId)))
                .andExpect(status().isBadRequest());
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
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }
}
