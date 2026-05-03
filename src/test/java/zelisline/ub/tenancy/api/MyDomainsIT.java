package zelisline.ub.tenancy.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Tenant self-service domains: list / add / set-primary / delete.
 *
 * <p>Pins the contract that an authenticated owner can manage only their own
 * business's domains (scope), that duplicates are rejected (409), and that
 * the primary domain cannot be deleted without first promoting another
 * (keeps the tenant reachable via {@code Host}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class MyDomainsIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PERM_MANAGE = "11111111-1111-1111-1111-111111111199";
    private static final String ROLE_OWNER = "22222222-2222-2222-2222-222222222201";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private DomainMappingRepository domainMappingRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRepository userRepository;

    private User ownerA;
    private String otherTenantDomainId;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        domainMappingRepository.deleteAll();
        businessRepository.deleteAll();

        businessRepository.save(business(TENANT_A, "tenant-a"));
        businessRepository.save(business(TENANT_B, "tenant-b"));

        otherTenantDomainId = domainMappingRepository.save(
                domain(TENANT_B, "tenant-b.example.com", true)).getId();

        permissionRepository.save(perm(PERM_MANAGE, "business.manage_settings", "Settings"));
        roleRepository.save(role(ROLE_OWNER, "owner"));
        grant(ROLE_OWNER, PERM_MANAGE);

        ownerA = user(TENANT_A, "owner-a@domains.test", ROLE_OWNER);
        userRepository.save(ownerA);
    }

    @Test
    void listEmptyForTenantWithoutDomains() throws Exception {
        mockMvc.perform(authed(get("/api/v1/businesses/me/domains")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void addDomain_firstBecomesPrimary() throws Exception {
        mockMvc.perform(authed(post("/api/v1/businesses/me/domains"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"domain\":\"shop.acme.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("shop.acme.com"))
                .andExpect(jsonPath("$.primary").value(true))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void addDomain_normalizesCaseAndTrimsWhitespace() throws Exception {
        mockMvc.perform(authed(post("/api/v1/businesses/me/domains"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"domain\":\"  SHOP.Acme.COM  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("shop.acme.com"));
    }

    @Test
    void addDomain_conflictsWhenAlreadyMapped() throws Exception {
        mockMvc.perform(authed(post("/api/v1/businesses/me/domains"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"domain\":\"tenant-b.example.com\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void setPrimary_onForeignDomainIsNotFound() throws Exception {
        mockMvc.perform(authed(post(
                        "/api/v1/businesses/me/domains/" + otherTenantDomainId + "/primary")))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteDomain_refusesPrimary() throws Exception {
        String primaryId = domainMappingRepository.save(
                domain(TENANT_A, "tenant-a.example.com", true)).getId();

        mockMvc.perform(authed(delete("/api/v1/businesses/me/domains/" + primaryId)))
                .andExpect(status().isConflict());
    }

    @Test
    void setPrimary_swapsWithoutHittingUniqueIndex() throws Exception {
        String oldPrimaryId = domainMappingRepository.save(
                domain(TENANT_A, "tenant-a.example.com", true)).getId();
        String newPrimaryId = domainMappingRepository.save(
                domain(TENANT_A, "new.acme.com", false)).getId();

        mockMvc.perform(authed(post(
                        "/api/v1/businesses/me/domains/" + newPrimaryId + "/primary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(newPrimaryId))
                .andExpect(jsonPath("$.primary").value(true));

        mockMvc.perform(authed(get("/api/v1/businesses/me/domains")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id=='" + newPrimaryId + "')].primary").value(true))
                .andExpect(jsonPath("$[?(@.id=='" + oldPrimaryId + "')].primary").value(false));
    }

    @Test
    void deleteDomain_softDeletesNonPrimary() throws Exception {
        domainMappingRepository.save(domain(TENANT_A, "tenant-a.example.com", true));
        String secondaryId = domainMappingRepository.save(
                domain(TENANT_A, "extra.acme.com", false)).getId();

        mockMvc.perform(authed(delete("/api/v1/businesses/me/domains/" + secondaryId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(authed(get("/api/v1/businesses/me/domains")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].domain").value("tenant-a.example.com"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder base) {
        return base
                .header("X-Tenant-Id", TENANT_A)
                .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER);
    }

    private static Business business(String id, String slug) {
        Business b = new Business();
        b.setId(id);
        b.setName(slug);
        b.setSlug(slug);
        b.setSettings("{}");
        return b;
    }

    private static DomainMapping domain(String businessId, String host, boolean primary) {
        DomainMapping d = new DomainMapping();
        d.setBusinessId(businessId);
        d.setDomain(host);
        d.setPrimary(primary);
        d.setActive(true);
        return d;
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
