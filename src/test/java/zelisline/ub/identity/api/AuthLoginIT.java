package zelisline.ub.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Slice 3 — email login + refresh rotation smoke (PHASE_1_PLAN.md §3.3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AuthLoginIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ROLE_ID = "22222222-2222-2222-2222-222222222201";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Tenant A");
        business.setSlug("tenant-a-auth");
        businessRepository.save(business);

        Role owner = new Role();
        owner.setId(ROLE_ID);
        owner.setBusinessId(null);
        owner.setRoleKey("owner");
        owner.setName("Owner");
        owner.setSystem(true);
        roleRepository.save(owner);

        User user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("owner@example.com");
        user.setName("Owner");
        user.setRoleId(ROLE_ID);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        userRepository.save(user);
    }

    @Test
    void loginReturnsTokens() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.com","password":"correct-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.user.email").value("owner@example.com"));
    }

    @Test
    void wrongPasswordReturns401WithoutLeak() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Invalid credentials"));
    }

    @Test
    void refreshRotatesAndOldRefreshInvalidatesAllSessions() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.com","password":"correct-password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String refresh1 = JsonPath.read(login.getResponse().getContentAsString(), "$.refreshToken");

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of("refreshToken", refresh1))))
                .andExpect(status().isOk())
                .andReturn();

        String refresh2 = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of("refreshToken", refresh1))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of("refreshToken", refresh2))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordForgotAlways204() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password/forgot")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void accessTokenAuthenticatesApi() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.com","password":"correct-password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String access = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/permissions")
                        .header("X-Tenant-Id", TENANT)
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
    }
}
