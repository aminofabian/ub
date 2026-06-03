package zelisline.ub.identity.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserSession;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.platform.security.JwtTokenService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Regression: token refresh must succeed even when the client still sends a
 * stale {@code Authorization} header from a prior session (same class of bug as
 * {@link AuthRegisterStaleBearerIT}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.auth.email-verification-required=false",
})
class AuthRefreshStaleBearerIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String OWNER_ROLE_ID = "22222222-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String userBId;

    @BeforeEach
    void seed() {
        userSessionRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        businessRepository.deleteAll();

        Role owner = new Role();
        owner.setId(OWNER_ROLE_ID);
        owner.setBusinessId(null);
        owner.setRoleKey("owner");
        owner.setName("Owner");
        owner.setSystem(true);
        roleRepository.save(owner);

        for (String[] pair : new String[][]{
                {TENANT_A, "tenant-a"},
                {TENANT_B, "tenant-b"},
        }) {
            Business business = new Business();
            business.setId(pair[0]);
            business.setName(pair[1]);
            business.setSlug(pair[1]);
            businessRepository.save(business);
        }

        User userA = new User();
        userA.setBusinessId(TENANT_A);
        userA.setEmail("user@example.com");
        userA.setName("User A");
        userA.setRoleId(OWNER_ROLE_ID);
        userA.setStatus(UserStatus.ACTIVE);
        userA.setPasswordHash(passwordEncoder.encode("password"));
        userRepository.save(userA);

        User userB = new User();
        userB.setBusinessId(TENANT_B);
        userB.setEmail("other@example.com");
        userB.setName("User B");
        userB.setRoleId(OWNER_ROLE_ID);
        userB.setStatus(UserStatus.ACTIVE);
        userB.setPasswordHash(passwordEncoder.encode("password"));
        userBId = userRepository.save(userB).getId();
    }

    @Test
    void refreshIgnoresStaleBearerFromAnotherTenant() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String refreshToken = JsonPath.read(
                login.getResponse().getContentAsString(),
                "$.refreshToken");

        String staleJti = UUID.randomUUID().toString();
        UserSession staleSession = new UserSession();
        staleSession.setUserId(userBId);
        staleSession.setBusinessId(TENANT_B);
        staleSession.setAccessTokenJti(staleJti);
        staleSession.setRefreshTokenHash("b".repeat(64));
        staleSession.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        staleSession.setRefreshExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        userSessionRepository.save(staleSession);

        String staleToken = jwtTokenService.createAccessToken(
                userBId,
                TENANT_B,
                OWNER_ROLE_ID,
                null,
                staleJti);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Authorization", "Bearer " + staleToken)
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }
}
