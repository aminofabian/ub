package zelisline.ub.support.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.support.repository.SupportConversationRepository;
import zelisline.ub.support.repository.SupportMessageRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Tenant ↔ super-admin support chat: thread creation, live message exchange,
 * read receipts, unread counts, resolve/reopen, and tenant isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SupportChatIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SLUG_A = "support-shop-a";
    private static final String SLUG_B = "support-shop-b";
    private static final String ROLE_OWNER = "22222222-aaaa-bbbb-cccc-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private SupportConversationRepository supportConversationRepository;

    @Autowired
    private SupportMessageRepository supportMessageRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String saToken;

    @BeforeEach
    void seed() throws Exception {
        supportMessageRepository.deleteAll();
        supportConversationRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();
        superAdminRepository.deleteAll();

        seedShop(TENANT_A, SLUG_A);
        seedShop(TENANT_B, SLUG_B);
        userRepository.save(user("owner@a.test", TENANT_A));
        userRepository.save(user("owner@b.test", TENANT_B));

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops-support@example.com");
        admin.setName("Support Ops");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops-support@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void tenantFirstMessageCreatesThreadVisibleToAdmin() throws Exception {
        MvcResult sent = mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"Hello, we need help with our till.\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderType").value("TENANT"))
                .andExpect(jsonPath("$.conversationId").isNotEmpty())
                .andReturn();

        String conversationId = objectMapper.readTree(sent.getResponse().getContentAsString())
                .get("conversationId").asText();

        // Tenant sees the thread.
        mockMvc.perform(get("/api/v1/support/conversation")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.id").value(conversationId))
                .andExpect(jsonPath("$.conversation.status").value("OPEN"))
                .andExpect(jsonPath("$.messages[0].body").value("Hello, we need help with our till."));

        // Admin inbox lists it as unread.
        mockMvc.perform(get("/api/v1/super-admin/support/conversations")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations[0].businessId").value(TENANT_A))
                .andExpect(jsonPath("$.conversations[0].unreadCount").value(1))
                .andExpect(jsonPath("$.unread").value(1));
    }

    @Test
    void adminReplyFlowsBackWithReadReceipts() throws Exception {
        MvcResult sent = mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"M-Pesa till 247100 stopped.\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        String conversationId = objectMapper.readTree(sent.getResponse().getContentAsString())
                .get("conversationId").asText();

        // Admin opens the thread — marks the tenant message as read (✓✓ on tenant side).
        mockMvc.perform(get("/api/v1/super-admin/support/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.id").value(conversationId))
                .andExpect(jsonPath("$.messages[0].readAt").isEmpty());

        // A second fetch reflects the receipt the first GET wrote.
        mockMvc.perform(get("/api/v1/super-admin/support/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(jsonPath("$.messages[0].readAt").isNotEmpty());

        // Admin replies.
        mockMvc.perform(post("/api/v1/super-admin/support/conversations/" + conversationId + "/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"On it — checking the gateway now.\"}")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderType").value("SUPER_ADMIN"));

        // Tenant sees the reply and an unread count of 1.
        mockMvc.perform(get("/api/v1/support/unread-count")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        MvcResult thread = mockMvc.perform(get("/api/v1/support/conversation")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].senderType").value("SUPER_ADMIN"))
                .andReturn();
        String adminMessageId = objectMapper.readTree(thread.getResponse().getContentAsString())
                .path("messages").path(1).get("id").asText();

        // Tenant reads → the admin's own message gets a read receipt.
        mockMvc.perform(post("/api/v1/support/conversation/read")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/super-admin/support/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[?(@.id == '" + adminMessageId + "')].readAt")
                        .isNotEmpty());
    }

    @Test
    void resolveAndReopenLifecycle() throws Exception {
        MvcResult sent = mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"How do I add a cashier?\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        String conversationId = objectMapper.readTree(sent.getResponse().getContentAsString())
                .get("conversationId").asText();

        mockMvc.perform(post("/api/v1/super-admin/support/conversations/" + conversationId + "/resolve")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/support/conversation")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.status").value("RESOLVED"));

        // A tenant message reopens a resolved thread automatically.
        mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"Actually, one more thing…\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/support/conversation")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.status").value("OPEN"));

        mockMvc.perform(get("/api/v1/super-admin/support/conversations?status=OPEN")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations[0].status").value("OPEN"));
    }

    @Test
    void tenantThreadsAreIsolated() throws Exception {
        mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"A's private question\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated());

        // Tenant B has its own (empty) thread — A's is invisible.
        mockMvc.perform(get("/api/v1/support/conversation")
                        .header("X-Tenant-Id", TENANT_B)
                        .header("X-Test-User-Id", userIdFor(TENANT_B))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation").doesNotExist())
                .andExpect(jsonPath("$.messages.length()").value(0));

        mockMvc.perform(get("/api/v1/support/unread-count")
                        .header("X-Tenant-Id", TENANT_B)
                        .header("X-Test-User-Id", userIdFor(TENANT_B))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void tenantCannotReachSuperAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/super-admin/support/conversations")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isForbidden());
    }

    // ── Visitor chat (kiosk.ke guest → super-admin) ────────────────────────

    @Test
    void visitorGuestThreadLifecycleWithTokenAuth() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type":"VISITOR","guestId":"guest-visitor-1","guestName":"Wanjiru","body":"Hi, I need help with billing."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.conversation.conversationType").value("VISITOR"))
                .andExpect(jsonPath("$.messages[0].body").value("Hi, I need help with billing."))
                .andReturn();
        String conversationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("conversation").get("id").asText();
        String token = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("token").asText();

        // Resume with the token.
        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", "guest-visitor-1")
                        .header("X-Guest-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.id").value(conversationId));

        // A wrong token is rejected.
        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", "guest-visitor-1")
                        .header("X-Guest-Token", "definitely-wrong"))
                .andExpect(status().isUnauthorized());

        // The guest sends another message.
        mockMvc.perform(post("/api/v1/public/support/threads/" + conversationId + "/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"It's about my invoice #102.\"}")
                        .header("X-Guest-Id", "guest-visitor-1")
                        .header("X-Guest-Token", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderType").value("GUEST"));

        // The super-admin sees the visitor thread and can reply.
        mockMvc.perform(get("/api/v1/super-admin/support/conversations?type=VISITOR")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations[0].guestName").value("Wanjiru"))
                .andExpect(jsonPath("$.conversations[0].conversationType").value("VISITOR"));

        mockMvc.perform(post("/api/v1/super-admin/support/conversations/" + conversationId + "/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"On it — checking the invoice now.\"}")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderType").value("SUPER_ADMIN"));

        // The guest picks up the reply on resume.
        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", "guest-visitor-1")
                        .header("X-Guest-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[2].senderType").value("SUPER_ADMIN"));
    }

    @Test
    void guestCannotReadAnotherGuestsThread() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type":"VISITOR","guestId":"guest-a","body":"Private question"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String conversationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("conversation").get("id").asText();

        // Another guest cannot send into it — the thread is invisible to them.
        mockMvc.perform(post("/api/v1/public/support/threads/" + conversationId + "/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"let me in\"}")
                        .header("X-Guest-Id", "guest-b")
                        .header("X-Guest-Token", "guessed-token"))
                .andExpect(status().isNotFound());

        // The thread owner with a wrong token is rejected too (401, not 404).
        mockMvc.perform(post("/api/v1/public/support/threads/" + conversationId + "/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"trying with a stale token\"}")
                        .header("X-Guest-Id", "guest-a")
                        .header("X-Guest-Token", "stale-token"))
                .andExpect(status().isUnauthorized());

        // A guest with no thread gets a clean 404 on resume.
        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", "guest-b")
                        .header("X-Guest-Token", "whatever"))
                .andExpect(status().isNotFound());
    }

    // ── Storefront chat (buyer → tenant staff) ─────────────────────────────

    @Test
    void storefrontBuyerThreadIsVisibleOnlyToTheirTenant() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type":"STOREFRONT","businessSlug":"support-shop-a","guestId":"buyer-1","guestName":"Njeri","body":"Is this item still in stock?"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversation.businessId").value(TENANT_A))
                .andExpect(jsonPath("$.conversation.conversationType").value("STOREFRONT"))
                .andReturn();
        String conversationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("conversation").get("id").asText();
        String token = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("token").asText();

        // Tenant A's staff sees the buyer thread.
        mockMvc.perform(get("/api/v1/support/storefront/conversations")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations.length()").value(1))
                .andExpect(jsonPath("$.conversations[0].guestName").value("Njeri"))
                .andExpect(jsonPath("$.unread").value(1));

        // Tenant B cannot see it.
        mockMvc.perform(get("/api/v1/support/storefront/conversations")
                        .header("X-Tenant-Id", TENANT_B)
                        .header("X-Test-User-Id", userIdFor(TENANT_B))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations.length()").value(0));

        mockMvc.perform(get("/api/v1/support/storefront/conversations/" + conversationId)
                        .header("X-Tenant-Id", TENANT_B)
                        .header("X-Test-User-Id", userIdFor(TENANT_B))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isNotFound());

        // Tenant A opens the thread (marks the buyer's message read) and replies.
        mockMvc.perform(get("/api/v1/support/storefront/conversations/" + conversationId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.id").value(conversationId));

        mockMvc.perform(post("/api/v1/support/storefront/conversations/" + conversationId + "/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"Yes — 3 left on the shelf.\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderType").value("TENANT"));

        // The buyer sees the reply on resume.
        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "STOREFRONT")
                        .param("businessSlug", "support-shop-a")
                        .param("guestId", "buyer-1")
                        .header("X-Guest-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].senderType").value("TENANT"));
    }

    @Test
    void presenceSnapshotListsEveryThreadWithOnlineFlag() throws Exception {
        mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"We're online now\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor(TENANT_A))
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated());

        // No WebSocket sessions in this test — every tenant is offline.
        mockMvc.perform(get("/api/v1/super-admin/support/presence")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presence." + TENANT_A + ".online").value(false))
                .andExpect(jsonPath("$.presence." + TENANT_A + ".lastSeenAt").isEmpty())
                .andExpect(jsonPath("$.presence." + TENANT_B).doesNotExist());
    }

    private void seedShop(String tenantId, String slug) {
        Business b = new Business();
        b.setId(tenantId);
        b.setName("Shop " + slug);
        b.setSlug(slug);
        b.setSettings("{}");
        businessRepository.save(b);
    }

    private User user(String email, String tenant) {
        User u = new User();
        u.setBusinessId(tenant);
        u.setEmail(email);
        u.setName("Owner " + email);
        u.setRoleId(ROLE_OWNER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }

    private String userIdFor(String tenantId) {
        return userRepository.findAll().stream()
                .filter(u -> tenantId.equals(u.getBusinessId()))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
