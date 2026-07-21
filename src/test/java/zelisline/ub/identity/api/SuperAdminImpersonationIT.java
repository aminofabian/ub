package zelisline.ub.identity.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminImpersonationIT {

    private static final String OWNER_ROLE_ID = "22222222-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String businessId;
    private String ownerUserId;
    private String saToken;

    @BeforeEach
    void seed() throws Exception {
        superAdminRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        businessRepository.deleteAll();

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops@example.com");
        admin.setName("Ops");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        Business business = new Business();
        business.setName("Impersonate Co");
        business.setSlug("impersonate-co");
        business.setActive(true);
        businessRepository.save(business);
        businessId = business.getId();

        Role owner = new Role();
        owner.setId(OWNER_ROLE_ID);
        owner.setBusinessId(null);
        owner.setRoleKey("owner");
        owner.setName("Owner");
        owner.setSystem(true);
        roleRepository.save(owner);

        User ownerUser = new User();
        ownerUser.setBusinessId(businessId);
        ownerUser.setEmail("owner@impersonate.test");
        ownerUser.setName("Owner");
        ownerUser.setRoleId(OWNER_ROLE_ID);
        ownerUser.setStatus(UserStatus.ACTIVE);
        ownerUser.setPasswordHash(passwordEncoder.encode("p"));
        userRepository.save(ownerUser);
        ownerUserId = ownerUser.getId();

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void impersonateOwnerThenAccessTenantApi() throws Exception {
        String json = mockMvc.perform(post("/api/v1/super-admin/businesses/{id}/impersonate", businessId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.user.id").value(ownerUserId))
                .andExpect(jsonPath("$.slug").value("impersonate-co"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String access = JsonPath.read(json, "$.accessToken");

        mockMvc.perform(get("/api/v1/businesses/me")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Tenant-Id", businessId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(businessId));
    }
}
