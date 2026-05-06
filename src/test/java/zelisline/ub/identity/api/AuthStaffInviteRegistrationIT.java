package zelisline.ub.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
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
                "app.auth.signup-role-key=buyer",
                "app.auth.staff-signup-token=fixture-invite-secret",
                "app.auth.staff-signup-role-key=viewer",
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthStaffInviteRegistrationIT {

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OWNER_ROLE_ID = "22222222-0000-0000-0000-000000000001";
    private static final String BUYER_ROLE_ID = "22222222-0000-0000-0000-000000000006";
    private static final String VIEWER_ROLE_ID = "22222222-0000-0000-0000-000000000005";

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
        business.setSlug("tenant-a-staff-inv");
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

        Role viewer = new Role();
        viewer.setId(VIEWER_ROLE_ID);
        viewer.setBusinessId(null);
        viewer.setRoleKey("viewer");
        viewer.setName("Viewer");
        viewer.setSystem(true);
        roleRepository.save(viewer);
    }

    @Test
    void staffSignupWithWrongTokenFails() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"bootstrap@example.com","name":"A","password":"secretpass"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"bad-invite@example.com","name":"B","password":"secretpass","staffInviteToken":"wrong"}
                                """))
                .andExpect(status().isForbidden());

        verify(notificationService, never()).sendEmailVerificationEmail(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    @Test
    void staffSignupWithValidTokenGetsStaffRole() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"one@example.com","name":"One","password":"secretpass"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"staff@example.com","name":"Staff","password":"secretpass","staffInviteToken":"fixture-invite-secret"}
                                """))
                .andExpect(status().isCreated());

        assertThat(
                userRepository
                        .findByBusinessIdAndEmailAndDeletedAtIsNull(TENANT, "staff@example.com")
                        .orElseThrow()
                        .getRoleId()
        ).isEqualTo(VIEWER_ROLE_ID);
    }
}
