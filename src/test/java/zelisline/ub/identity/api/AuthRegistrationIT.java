package zelisline.ub.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.identity.application.AuthService;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRegistrationIT {

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OWNER_ROLE_ID = "22222222-0000-0000-0000-000000000001";
    private static final String VIEWER_ROLE_ID = "22222222-0000-0000-0000-000000000005";

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([^\\s]+)");

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
        business.setSlug("tenant-a-reg");
        businessRepository.save(business);

        Role owner = new Role();
        owner.setId(OWNER_ROLE_ID);
        owner.setBusinessId(null);
        owner.setRoleKey("owner");
        owner.setName("Owner");
        owner.setSystem(true);
        roleRepository.save(owner);

        Role viewer = new Role();
        viewer.setId(VIEWER_ROLE_ID);
        viewer.setBusinessId(null);
        viewer.setRoleKey("viewer");
        viewer.setName("Viewer");
        viewer.setSystem(true);
        roleRepository.save(viewer);
    }

    @Test
    void registerVerifyThenLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com","name":"New User","password":"secretpass"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.status").value("invited"));

        assertThat(
                userRepository
                        .findByBusinessIdAndEmailAndDeletedAtIsNull(TENANT, "new@example.com")
                        .orElseThrow()
                        .getRoleId()
        ).isEqualTo(OWNER_ROLE_ID);

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com","password":"secretpass"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value(AuthService.LOGIN_EMAIL_NOT_VERIFIED_DETAIL));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendEmailVerificationEmail(anyString(), anyString(), bodyCaptor.capture());
        String rawToken = extractToken(bodyCaptor.getValue());

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com","password":"secretpass"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    private static String extractToken(String emailBody) {
        Matcher m = TOKEN_IN_LINK.matcher(emailBody);
        if (!m.find()) {
            throw new IllegalStateException("No token= in body: " + emailBody);
        }
        return m.group(1).trim();
    }
}
