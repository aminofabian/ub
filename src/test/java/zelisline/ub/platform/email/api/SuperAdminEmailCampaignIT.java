package zelisline.ub.platform.email.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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
import zelisline.ub.platform.email.repository.PlatformEmailCampaignRecipientRepository;
import zelisline.ub.platform.email.repository.PlatformEmailCampaignRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.tenancy.slug-domain-suffix=kiosk.ke"
})
class SuperAdminEmailCampaignIT {

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
    private PlatformEmailCampaignRepository campaignRepository;

    @Autowired
    private PlatformEmailCampaignRecipientRepository recipientRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String saToken;
    private String doneUserId;

    @BeforeEach
    void seed() throws Exception {
        recipientRepository.deleteAll();
        campaignRepository.deleteAll();
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

        Role owner = new Role();
        owner.setBusinessId(null);
        owner.setRoleKey("owner");
        owner.setName("Owner");
        owner.setSystem(true);
        roleRepository.save(owner);

        Business stuckBiz = business("Stuck Shop", "stuck-shop", "{\"onboarding\":{\"status\":\"pending\"}}");
        Business doneBiz = business("Done Shop", "done-shop", "{\"onboarding\":{\"status\":\"completed\"}}");

        user(stuckBiz.getId(), "stuck@shop.test", "Stuck Owner", owner.getId(), UserStatus.INVITED, null);
        User done = user(doneBiz.getId(), "done@shop.test", "Done Owner", owner.getId(), UserStatus.ACTIVE, Instant.now());
        doneUserId = done.getId();

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
    void stuckSignupSegmentIncludesInvitedAndExcludesFinishedOwners() throws Exception {
        mockMvc.perform(get("/api/v1/super-admin/email-recipients")
                        .header("Authorization", "Bearer " + saToken)
                        .param("segment", "stuck_signup")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("stuck@shop.test"))
                .andExpect(jsonPath("$.content[0].continueKind").value("verify"))
                .andExpect(jsonPath("$.content[0].onboardingStatus").value("pending"));
    }

    @Test
    void sendDraftMarksRecipientsAndRefusesSecondRun() throws Exception {
        String created = mockMvc.perform(post("/api/v1/super-admin/email-campaigns")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Stuck signups",
                                  "segmentKey": "stuck_signup",
                                  "subject": "Finish {{businessName}} on Kiosk",
                                  "bodyMarkdown": "Hi {{name}}, continue at {{continueUrl}}.",
                                  "ctaLabel": "Continue setup"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.recipientsTargeted").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String campaignId = JsonPath.read(created, "$.id");

        mockMvc.perform(post("/api/v1/super-admin/email-campaigns/{id}/send", campaignId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recipientsSent").value(1))
                .andExpect(jsonPath("$.recipients[0].status").value("SENT"));

        mockMvc.perform(post("/api/v1/super-admin/email-campaigns/{id}/send", campaignId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isConflict());
    }

    @Test
    void selectedUsersCanTargetAFinishedOwner() throws Exception {
        mockMvc.perform(get("/api/v1/super-admin/email-recipients")
                        .header("Authorization", "Bearer " + saToken)
                        .param("segment", "selected_users")
                        .param("userIds", doneUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(doneUserId));
    }

    @Test
    void refusesAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/v1/super-admin/email-recipients")
                        .param("segment", "stuck_signup"))
                .andExpect(status().isForbidden());
    }

    private Business business(String name, String slug, String settings) {
        Business business = new Business();
        business.setName(name);
        business.setSlug(slug);
        business.setSettings(settings);
        return businessRepository.save(business);
    }

    private User user(
            String businessId,
            String email,
            String name,
            String roleId,
            UserStatus status,
            Instant lastLoginAt
    ) {
        User user = new User();
        user.setBusinessId(businessId);
        user.setEmail(email);
        user.setName(name);
        user.setRoleId(roleId);
        user.setStatus(status);
        user.setLastLoginAt(lastLoginAt);
        user.setPasswordHash(passwordEncoder.encode("password-1"));
        return userRepository.save(user);
    }
}
