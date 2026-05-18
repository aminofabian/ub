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
 * Regression: signup after onboarding must succeed even when the browser still
 * sends a Bearer token from another tenant (JwtAuthenticationFilter used to 403).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.auth.email-verification-required=false",
})
class AuthRegisterStaleBearerIT {

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
        userA.setEmail("existing@example.com");
        userA.setName("Existing");
        userA.setRoleId(OWNER_ROLE_ID);
        userA.setStatus(UserStatus.ACTIVE);
        userA.setPasswordHash(passwordEncoder.encode("password"));
        userRepository.save(userA);
    }

    @Test
    void registerIgnoresStaleBearerFromAnotherTenant() throws Exception {
        String jti = UUID.randomUUID().toString();
        UserSession session = new UserSession();
        session.setUserId(userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(TENANT_A, "existing@example.com")
                .orElseThrow()
                .getId());
        session.setBusinessId(TENANT_A);
        session.setAccessTokenJti(jti);
        session.setRefreshTokenHash("a".repeat(64));
        session.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        session.setRefreshExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        userSessionRepository.save(session);

        String staleToken = jwtTokenService.createAccessToken(
                session.getUserId(),
                TENANT_A,
                OWNER_ROLE_ID,
                null,
                jti);

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Authorization", "Bearer " + staleToken)
                        .header("X-Tenant-Id", TENANT_B)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com","name":"New User","password":"secretpass"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.com"));
    }
}
