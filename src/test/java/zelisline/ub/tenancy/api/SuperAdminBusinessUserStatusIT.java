package zelisline.ub.tenancy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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

import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserSession;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Pins the super-admin tenant-user status contract:
 * {@code PATCH /super-admin/businesses/{id}/users/{userId}/status}.
 *
 * <p>Super-admin can move a user between lifecycle states (e.g. invited →
 * active) without going through the tenant's own flow, but the last-active-owner
 * invariant is still enforced and leaving {@code active} revokes sessions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminBusinessUserStatusIT {

    private static final String OWNER_ROLE_ID = "22222222-0000-0000-0000-000000000001";
    private static final String STAFF_ROLE_ID = "22222222-0000-0000-0000-000000000002";

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
    private UserSessionRepository userSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String saToken;
    private String businessId;
    private String ownerUserId;
    private String invitedUserId;

    @BeforeEach
    void seed() throws Exception {
        superAdminRepository.deleteAll();
        userSessionRepository.deleteAll();
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
        business.setName("Status Co");
        business.setSlug("status-co");
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

        Role staff = new Role();
        staff.setId(STAFF_ROLE_ID);
        staff.setBusinessId(businessId);
        staff.setRoleKey("staff");
        staff.setName("Staff");
        staff.setSystem(false);
        roleRepository.save(staff);

        User ownerUser = new User();
        ownerUser.setBusinessId(businessId);
        ownerUser.setEmail("owner@status.test");
        ownerUser.setName("Owner");
        ownerUser.setRoleId(OWNER_ROLE_ID);
        ownerUser.setStatus(UserStatus.ACTIVE);
        ownerUser.setPasswordHash(passwordEncoder.encode("p"));
        userRepository.save(ownerUser);
        ownerUserId = ownerUser.getId();

        User invitedUser = new User();
        invitedUser.setBusinessId(businessId);
        invitedUser.setEmail("staff@status.test");
        invitedUser.setName("Staff");
        invitedUser.setRoleId(STAFF_ROLE_ID);
        invitedUser.setStatus(UserStatus.INVITED);
        userRepository.save(invitedUser);
        invitedUserId = invitedUser.getId();

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

    private String patchStatus(String userId, String statusWire) throws Exception {
        return mockMvc.perform(patch(
                        "/api/v1/super-admin/businesses/{b}/users/{u}/status", businessId, userId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + statusWire + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.status").value(statusWire))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void activatesInvitedUser() throws Exception {
        patchStatus(invitedUserId, "active");

        mockMvc.perform(get("/api/v1/super-admin/businesses/{b}/users", businessId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + invitedUserId + "')].status")
                        .value("active"));
    }

    @Test
    void suspendingActiveUserRevokesTheirSessions() throws Exception {
        // Second active owner so the last-owner guard does not block the change.
        User secondOwner = new User();
        secondOwner.setBusinessId(businessId);
        secondOwner.setEmail("owner2@status.test");
        secondOwner.setName("Owner Two");
        secondOwner.setRoleId(OWNER_ROLE_ID);
        secondOwner.setStatus(UserStatus.ACTIVE);
        userRepository.save(secondOwner);

        UserSession session = new UserSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(ownerUserId);
        session.setBusinessId(businessId);
        session.setAccessTokenJti(UUID.randomUUID().toString());
        session.setRefreshTokenHash("x".repeat(64));
        session.setUserAgent("test");
        session.setIp("127.0.0.1");
        session.setIssuedAt(Instant.now());
        session.setExpiresAt(Instant.now().plusSeconds(900));
        session.setRefreshExpiresAt(Instant.now().plus(java.time.Duration.ofDays(30)));
        session.setLastSeenAt(Instant.now());
        userSessionRepository.save(session);

        patchStatus(ownerUserId, "suspended");

        var rows = userSessionRepository.findAll();
        assertThat(rows.stream().filter(s -> s.getUserId().equals(ownerUserId))
                .allMatch(s -> s.getRevokedAt() != null));
    }

    @Test
    void rejectsUnknownStatus() throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/super-admin/businesses/{b}/users/{u}/status", businessId, invitedUserId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"banned"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesToDeactivateLastActiveOwner() throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/super-admin/businesses/{b}/users/{u}/status", businessId, ownerUserId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"suspended"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void returnsNotFoundForMissingUser() throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/super-admin/businesses/{b}/users/{u}/status", businessId, "missing-user-id")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"active"}
                                """))
                .andExpect(status().isNotFound());
    }
}
