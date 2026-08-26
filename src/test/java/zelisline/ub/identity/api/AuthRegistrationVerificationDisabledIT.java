package zelisline.ub.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "app.auth.email-verification-required=false",
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRegistrationVerificationDisabledIT {

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OWNER_ROLE_ID = "22222222-0000-0000-0000-000000000001";
    private static final String BUYER_ROLE_ID = "22222222-0000-0000-0000-000000000006";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private zelisline.ub.identity.repository.RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private NotificationService notificationService;

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
        business.setSlug("tenant-a-reg-novfy");
        businessRepository.save(business);

        Role owner = new Role();
        owner.setId(OWNER_ROLE_ID);
        owner.setBusinessId(null);
        owner.setRoleKey("owner");
        owner.setName("Owner");
        owner.setSystem(true);
        roleRepository.save(owner);

        Role buyer = new Role();
        buyer.setId(BUYER_ROLE_ID);
        buyer.setBusinessId(null);
        buyer.setRoleKey("buyer");
        buyer.setName("Buyer");
        buyer.setSystem(true);
        roleRepository.save(buyer);
    }

    @Test
    void registerIsActiveAndLoginWorksWithoutVerifyEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"direct@example.com","name":"Direct","password":"secretpass"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("direct@example.com"))
                .andExpect(jsonPath("$.status").value("active"));

        verify(notificationService, never()).sendEmailVerificationEmail(anyString(), anyString(), anyString());
        verify(notificationService).sendWelcomeEmail(
                org.mockito.ArgumentMatchers.eq("direct@example.com"),
                org.mockito.ArgumentMatchers.contains("Welcome to Kiosk"),
                anyString());

        assertThat(
                userRepository
                        .findByBusinessIdAndEmailAndDeletedAtIsNull(TENANT, "direct@example.com")
                        .orElseThrow()
                        .getRoleId()
        ).isEqualTo(OWNER_ROLE_ID);

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"direct@example.com","password":"secretpass"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    @Test
    void secondSelfSignupGetsConfiguredDefaultRole() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"first@example.com","name":"First","password":"secretpass"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"second@example.com","name":"Second","password":"secretpass"}
                                """))
                .andExpect(status().isCreated());

        assertThat(
                userRepository
                        .findByBusinessIdAndEmailAndDeletedAtIsNull(TENANT, "first@example.com")
                        .orElseThrow()
                        .getRoleId()
        ).isEqualTo(OWNER_ROLE_ID);
        assertThat(
                userRepository
                        .findByBusinessIdAndEmailAndDeletedAtIsNull(TENANT, "second@example.com")
                        .orElseThrow()
                        .getRoleId()
        ).isEqualTo(BUYER_ROLE_ID);
    }
}
