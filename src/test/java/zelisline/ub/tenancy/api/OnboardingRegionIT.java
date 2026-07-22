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
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.selfserve.countries=KE,UG",
        "app.selfserve.cash-credit-only-countries=UG",
        "app.tenancy.slug-domain-suffix=test.local"
})
class OnboardingRegionIT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String PERM_MANAGE = "11111111-1111-1111-1111-111111111188";
    private static final String ROLE_OWNER = "22222222-2222-2222-2222-222222222288";

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

    private User owner;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        domainMappingRepository.deleteAll();
        businessRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();

        permissionRepository.save(perm(PERM_MANAGE, "business.manage_settings", "Settings"));
        Role ownerRole = role(ROLE_OWNER, "owner");
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, PERM_MANAGE);

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Region Co");
        business.setSlug("region-co");
        business.setCountryCode("KE");
        business.setCurrency("KES");
        business.setTimezone("Africa/Nairobi");
        business.setSettings("""
                {"onboarding":{"status":"pending","step":1}}
                """);
        businessRepository.save(business);

        owner = user(TENANT, "owner@region.test", ROLE_OWNER);
        userRepository.save(owner);
    }

    @Test
    void selfServeCountries_listsEnabledOnly() throws Exception {
        mockMvc.perform(get("/api/v1/public/host/selfserve-countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.countryCode=='KE')].currency").value(org.hamcrest.Matchers.hasItem("KES")))
                .andExpect(jsonPath("$[?(@.countryCode=='UG')].currency").value(org.hamcrest.Matchers.hasItem("UGX")))
                .andExpect(jsonPath("$[?(@.countryCode=='UG')].cashCreditOnly").value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.countryCode=='UG')].paymentHint").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Cash"))))
                .andExpect(jsonPath("$[?(@.countryCode=='TZ')]").isEmpty());
    }

    @Test
    void onboardOmitCountry_defaultsToKenya() throws Exception {
        mockMvc.perform(post("/api/v1/public/host/onboard")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Sunrise Mart","host":"kiosk.test.local"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.countryCode").value("KE"))
                .andExpect(jsonPath("$.tenantName").value("Sunrise Mart"));

        Business saved = businessRepository.findAll().stream()
                .filter(b -> "Sunrise Mart".equals(b.getName()))
                .findFirst()
                .orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("KES", saved.getCurrency());
        org.junit.jupiter.api.Assertions.assertEquals("Africa/Nairobi", saved.getTimezone());
    }

    @Test
    void onboardWithUganda_setsDerivedRegion() throws Exception {
        mockMvc.perform(post("/api/v1/public/host/onboard")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Kampala Mart","host":"kiosk.test.local","countryCode":"UG"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.countryCode").value("UG"));

        Business saved = businessRepository.findAll().stream()
                .filter(b -> "Kampala Mart".equals(b.getName()))
                .findFirst()
                .orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("UGX", saved.getCurrency());
        org.junit.jupiter.api.Assertions.assertEquals("Africa/Kampala", saved.getTimezone());
    }

    @Test
    void onboardWithTanzania_rejectedWhenNotSelfServe() throws Exception {
        mockMvc.perform(post("/api/v1/public/host/onboard")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Dar Mart","host":"kiosk.test.local","countryCode":"TZ"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tenantCanPatchCountryWhileOnboardingPending() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"countryCode":"UG"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("UG"))
                .andExpect(jsonPath("$.currency").value("UGX"))
                .andExpect(jsonPath("$.timezone").value("Africa/Kampala"));
    }

    @Test
    void tenantCannotPatchCountryAfterOnboardingCompleted() throws Exception {
        Business business = businessRepository.findById(TENANT).orElseThrow();
        business.setSettings("""
                {"onboarding":{"status":"completed","step":6}}
                """);
        businessRepository.save(business);

        mockMvc.perform(patch("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"countryCode":"UG"}
                                """))
                .andExpect(status().isForbidden());
    }

    private static Permission perm(String id, String key, String label) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(label);
        return p;
    }

    private static Role role(String id, String key) {
        Role r = new Role();
        r.setId(id);
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
