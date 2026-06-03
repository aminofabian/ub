package zelisline.ub.identity.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.identity.domain.PasswordResetToken;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PasswordResetTokenRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/** Reset tokens are only accepted when the resolved tenant matches the token owner's business. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AuthPasswordResetTenantBindingIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ROLE_ID = "22222222-2222-2222-2222-222222222201";
    private static final String RAW_TOKEN = "tenant-bound-reset-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void seed() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        businessRepository.deleteAll();

        Role owner = new Role();
        owner.setId(ROLE_ID);
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

        User user = new User();
        user.setBusinessId(TENANT_A);
        user.setEmail("owner@example.com");
        user.setName("Owner");
        user.setRoleId(ROLE_ID);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(passwordEncoder.encode("old-password"));
        userRepository.save(user);

        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(TokenHasher.sha256Hex(RAW_TOKEN));
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        passwordResetTokenRepository.save(token);
    }

    @Test
    void passwordResetRejectsTokenUnderWrongTenant() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .header("X-Tenant-Id", TENANT_B)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"new-password-ok"}
                                """.formatted(RAW_TOKEN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetAcceptsTokenUnderMatchingTenant() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"new-password-ok"}
                                """.formatted(RAW_TOKEN)))
                .andExpect(status().isNoContent());
    }
}
