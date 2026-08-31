package zelisline.ub.desktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageScope;
import zelisline.ub.messages.domain.ContactMessageStatus;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;
import zelisline.ub.messages.repository.ContactMessageRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Cloud side of the desktop ⇄ cloud message relay (docs/scopes/DESKTOP_MESSAGES_SCOPE.md):
 *
 * <ul>
 *   <li>{@code GET /api/v1/desktop/sync/messages} — tenant-scoped, activity
 *       cursor (an old message with a new reply re-appears), full reply threads.</li>
 *   <li>{@code POST /api/v1/desktop/sync/message-replies} — sends via the shop's
 *       providers, idempotent by reply id, per-item failures never block the
 *       batch, and the message is marked read.</li>
 * </ul>
 *
 * <p>Both endpoints are plain {@code authenticated()} (same JWT contract as the
 * sales sync) — no permission check, tenant comes from {@code X-Tenant-Id}.
 *
 * <p>Messages are seeded through the repository (not the public storefront
 * submit) so the tests stay deterministic and never trip the shared public
 * contact-message rate-limit bucket in the test JVM.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class DesktopMessagesSyncIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ROLE_OWNER = "22222222-aaaa-bbbb-cccc-000000000001";
    private static final String P_READ = "11111111-aaaa-bbbb-cccc-000000000093";
    private static final String P_REPLY = "11111111-aaaa-bbbb-cccc-000000000094";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private ContactMessageRepository contactMessageRepository;

    @Autowired
    private ContactMessageReplyRepository contactMessageReplyRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void seed() {
        contactMessageReplyRepository.deleteAll();
        contactMessageRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        business(TENANT_A, "Shop A");
        business(TENANT_B, "Shop B");

        permissionRepository.save(perm(P_READ, "messages.read"));
        permissionRepository.save(perm(P_REPLY, "messages.reply"));
        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, P_READ);
        grant(ROLE_OWNER, P_REPLY);
    }

    /** Seed a TENANT-scope message directly (avoids the public rate-limited submit). */
    private String seedMessage(String businessId, String name, String email, String body) {
        ContactMessage m = new ContactMessage();
        m.setId(UUID.randomUUID().toString());
        m.setScope(ContactMessageScope.TENANT);
        m.setBusinessId(businessId);
        m.setName(name);
        m.setEmail(email);
        m.setBody(body);
        m.setStatus(ContactMessageStatus.UNREAD);
        m.setCreatedAt(Instant.now());
        contactMessageRepository.save(m);
        return m.getId();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asTenantA(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder
            .header("X-Tenant-Id", TENANT_A)
            .header(TestAuthenticationFilter.HEADER_USER_ID, "till-user-a")
            .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER);
    }

    @Test
    void messagesPullScopesToTenantAndReturnsFullThreads() throws Exception {
        seedMessage(TENANT_A, "Ada", "ada@example.com", "Do you stock maize flour?");
        seedMessage(TENANT_B, "Bob", "bob@example.com", "Where is my order?");

        MvcResult a = mockMvc.perform(asTenantA(
                        get("/api/v1/desktop/sync/messages")
                            .param("since", "1970-01-01T00:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].name").value("Ada"))
                .andExpect(jsonPath("$.messages[0].replies.length()").value(0))
                .andReturn();
        // Tenant B's message never appears for A.
        String body = a.getResponse().getContentAsString();
        assertEquals(1, objectMapper.readTree(body).path("messages").size());
    }

    @Test
    void messagesPullActivityCursorIncludesOldMessageWithNewReply() throws Exception {
        ContactMessage seeded = new ContactMessage();
        seeded.setId("m-1");
        seeded.setScope(ContactMessageScope.TENANT);
        seeded.setBusinessId(TENANT_A);
        seeded.setName("Ada");
        seeded.setEmail("ada@example.com");
        seeded.setBody("Maize flour?");
        seeded.setStatus(ContactMessageStatus.UNREAD);
        seeded.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
        contactMessageRepository.save(seeded);

        // A cursor AFTER the message's creation returns nothing…
        String since = "2026-08-20T10:00:01Z";
        mockMvc.perform(asTenantA(
                        get("/api/v1/desktop/sync/messages").param("since", since)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(0));

        // …until a reply lands, which re-activates the thread with it attached.
        mockMvc.perform(asTenantA(post("/api/v1/contact-messages/m-1/replies")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"channel":"EMAIL","body":"Yes we do!"}
                                """)))
                .andExpect(status().isOk());

        mockMvc.perform(asTenantA(
                        get("/api/v1/desktop/sync/messages").param("since", since)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].id").value("m-1"))
                .andExpect(jsonPath("$.messages[0].replies.length()").value(1))
                .andExpect(jsonPath("$.messages[0].replies[0].body").value("Yes we do!"))
                .andExpect(jsonPath("$.messages[0].replies[0].outcome").value("sent"));
    }

    @Test
    void messageRepliesIngestIsIdempotentAndMarksRead() throws Exception {
        String id = seedMessage(TENANT_A, "Cara", "cara@example.com", "Hello shop");
        String payload = """
                {"replies":[{"replyId":"r-1","contactMessageId":"%s","channel":"EMAIL",\
                "body":"Thanks for reaching out.","sentByUserId":"till-user-a",\
                "createdAt":"2026-08-20T12:00:00Z"}]}
                """.formatted(id);

        mockMvc.perform(asTenantA(post("/api/v1/desktop/sync/message-replies")
                        .contentType(APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].replyId").value("r-1"))
                .andExpect(jsonPath("$.results[0].outcome").value("sent"));

        // Idempotent re-push: same replyId returns the existing outcome without
        // creating a second row or re-sending.
        mockMvc.perform(asTenantA(post("/api/v1/desktop/sync/message-replies")
                        .contentType(APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("sent"));
        assertEquals(1, contactMessageReplyRepository
            .findByContactMessageIdOrderByCreatedAtAsc(id).size());

        // Replying marks the message read (cloud badge clears).
        mockMvc.perform(asTenantA(get("/api/v1/contact-messages/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READ"));
    }

    @Test
    void messageRepliesIngestScopesToTenantWithPerItemFailure() throws Exception {
        String otherTenantId = seedMessage(TENANT_B, "Bob", "bob@example.com", "Where is my order?");

        mockMvc.perform(asTenantA(post("/api/v1/desktop/sync/message-replies")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"replies":[{"replyId":"r-9","contactMessageId":"%s","channel":"EMAIL",\
                                "body":"Hello","sentByUserId":"till-user-a",\
                                "createdAt":"2026-08-20T12:00:00Z"}]}
                                """.formatted(otherTenantId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("failed"))
                .andExpect(jsonPath("$.results[0].detail").value("Message not found"));
    }

    @Test
    void desktopSyncEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/desktop/sync/messages")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/desktop/sync/message-replies")
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(APPLICATION_JSON)
                        .content("{\"replies\":[]}"))
                .andExpect(status().isForbidden());
    }

    private void business(String tenantId, String name) {
        Business b = new Business();
        b.setId(tenantId);
        b.setName(name);
        b.setSlug(name.toLowerCase().replace(' ', '-'));
        b.setSettings("{}");
        businessRepository.save(b);
    }

    private static Permission perm(String id, String key) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(key);
        return p;
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }
}
