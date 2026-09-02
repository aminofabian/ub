package zelisline.ub.serving.api;

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
import zelisline.ub.identity.domain.SuperAdminDeskRoles;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.serving.repository.ServingTicketEventRepository;
import zelisline.ub.serving.repository.ServingTicketNoteRepository;
import zelisline.ub.serving.repository.ServingTicketPointRepository;
import zelisline.ub.serving.repository.ServingTicketRepository;
import zelisline.ub.support.repository.SupportConversationRepository;
import zelisline.ub.support.repository.SupportMessageRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ServingPortalIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa01";
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
    private ServingTicketRepository servingTicketRepository;

    @Autowired
    private ServingTicketNoteRepository servingTicketNoteRepository;

    @Autowired
    private ServingTicketPointRepository servingTicketPointRepository;

    @Autowired
    private ServingTicketEventRepository servingTicketEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String ownerToken;

    @BeforeEach
    void seed() throws Exception {
        servingTicketPointRepository.deleteAll();
        servingTicketNoteRepository.deleteAll();
        servingTicketEventRepository.deleteAll();
        servingTicketRepository.deleteAll();
        supportMessageRepository.deleteAll();
        supportConversationRepository.deleteAll();
        userRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();
        superAdminRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT_A);
        business.setName("Serving Shop");
        business.setSlug("serving-shop");
        business.setSettings("{}");
        businessRepository.save(business);

        User owner = new User();
        owner.setBusinessId(TENANT_A);
        owner.setEmail("owner@serving.test");
        owner.setName("Shop Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops-serving@example.com");
        admin.setName("Serving Owner");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        admin.setDeskRole(SuperAdminDeskRoles.OWNER);
        superAdminRepository.save(admin);

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops-serving@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deskRole").value("owner"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        ownerToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void tenantMessageOpensTicketAndAgentCanBeAssigned() throws Exception {
        mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"Till is stuck on the splash.\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated());

        MvcResult queue = mockMvc.perform(get("/api/v1/super-admin/serving/tickets")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.tickets[0].status").value("NEW"))
                .andExpect(jsonPath("$.tickets[0].type").value("TENANT"))
                .andExpect(jsonPath("$.tickets[0].displayNumber").value("K-1001"))
                .andReturn();
        String ticketId = objectMapper.readTree(queue.getResponse().getContentAsString())
                .path("tickets").get(0).path("id").asText();

        MvcResult invited = mockMvc.perform(post("/api/v1/super-admin/serving/staff")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Amina Agent","email":"agent@serving.test","deskRole":"agent","password":"agent-pass-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staff.deskRole").value("agent"))
                .andReturn();
        String agentId = objectMapper.readTree(invited.getResponse().getContentAsString())
                .path("staff").path("id").asText();

        String agentLogin = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"agent@serving.test","password":"agent-pass-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deskRole").value("agent"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String agentToken = JsonPath.read(agentLogin, "$.accessToken");

        mockMvc.perform(get("/api/v1/super-admin/businesses")
                        .param("page", "0")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/super-admin/support/conversations")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations").isArray());

        mockMvc.perform(get("/api/v1/super-admin/contact-messages")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/super-admin/serving/tickets/" + ticketId + "/assign")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"assigneeId\":\"" + agentId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.assignedTo").value(agentId));

        mockMvc.perform(post("/api/v1/super-admin/serving/tickets/" + ticketId + "/messages")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"We are on it — reboot the till.\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/super-admin/serving/tickets/" + ticketId + "/notes")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"Called the shop; they will retry after lunch.\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/super-admin/serving/tickets/" + ticketId + "/status")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"WAITING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"));

        mockMvc.perform(get("/api/v1/super-admin/serving/board")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[?(@.id=='" + agentId + "')].waitingCount").isNotEmpty());

        mockMvc.perform(get("/api/v1/support/tickets")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.tickets[0].id").value(ticketId));
    }

    @Test
    void agentCanClaimUnassignedTicket() throws Exception {
        mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"Need a domain.\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated());

        String ticketId = objectMapper.readTree(
                        mockMvc.perform(get("/api/v1/super-admin/serving/tickets")
                                        .header("Authorization", "Bearer " + ownerToken))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("tickets").get(0).path("id").asText();

        mockMvc.perform(post("/api/v1/super-admin/serving/staff")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Claim Agent","email":"claim@serving.test","deskRole":"agent","password":"agent-pass-2"}
                                """))
                .andExpect(status().isCreated());

        String agentToken = JsonPath.read(
                mockMvc.perform(post("/api/v1/super-admin/auth/login")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"email":"claim@serving.test","password":"agent-pass-2"}
                                        """))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.accessToken");

        mockMvc.perform(get("/api/v1/super-admin/serving/assignees")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees").isArray());

        mockMvc.perform(get("/api/v1/super-admin/serving/staff")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/super-admin/serving/tickets/" + ticketId + "/claim")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.assignedTo").isNotEmpty());

        mockMvc.perform(post("/api/v1/super-admin/serving/tickets/" + ticketId + "/status")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shopEscalatesStorefrontChatIntoShopperTicket() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type":"STOREFRONT","businessSlug":"serving-shop","guestId":"buyer-1","guestName":"Njeri","body":"My order never arrived"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String conversationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("conversation").get("id").asText();

        mockMvc.perform(get("/api/v1/super-admin/serving/tickets")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(post("/api/v1/support/storefront/conversations/" + conversationId + "/escalate")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SHOPPER"))
                .andExpect(jsonPath("$.status").value("NEW"));

        mockMvc.perform(get("/api/v1/super-admin/serving/tickets")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.tickets[0].type").value("SHOPPER"));
    }

    @Test
    void shopCanOpenASecondTicketAndACustomerReplyReopensResolved() throws Exception {
        mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"Billing looks wrong.\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated());

        String firstId = objectMapper.readTree(
                        mockMvc.perform(get("/api/v1/super-admin/serving/tickets")
                                        .header("Authorization", "Bearer " + ownerToken))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("tickets").get(0).path("id").asText();

        mockMvc.perform(post("/api/v1/support/tickets")
                        .contentType(APPLICATION_JSON)
                        .content("{\"subject\":\"Need a custom domain\",\"body\":\"Please point kiosk.ke to our shop.\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayNumber").value("K-1002"))
                .andExpect(jsonPath("$.type").value("TENANT"));

        mockMvc.perform(get("/api/v1/support/tickets")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(post("/api/v1/super-admin/serving/tickets/" + firstId + "/status")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"It is still wrong.\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/super-admin/serving/tickets/" + firstId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.status").value("OPEN"));
    }

    @Test
    void organizeThreadIntoNumberedPointsTenantCanComplete() throws Exception {
        mockMvc.perform(post("/api/v1/support/conversation/messages")
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"1. Till is stuck on the splash. 2. Please point a custom domain. 3. We also need SMS credits.\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isCreated());

        String ticketId = objectMapper.readTree(
                        mockMvc.perform(get("/api/v1/super-admin/serving/tickets")
                                        .header("Authorization", "Bearer " + ownerToken))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("tickets").get(0).path("id").asText();

        MvcResult organized = mockMvc.perform(post("/api/v1/super-admin/serving/tickets/" + ticketId + "/organize")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("HEURISTIC"))
                .andExpect(jsonPath("$.ticket.points[0].seq").value(1))
                .andExpect(jsonPath("$.ticket.points[1].seq").value(2))
                .andExpect(jsonPath("$.ticket.points[2].seq").value(3))
                .andExpect(jsonPath("$.ticket.ticket.shopSeq").value(1))
                .andExpect(jsonPath("$.ticket.ticket.pointCount").value(3))
                .andReturn();

        String pointId = objectMapper.readTree(organized.getResponse().getContentAsString())
                .path("ticket").path("points").get(0).path("id").asText();

        mockMvc.perform(get("/api/v1/support/tickets")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets[0].shopSeq").value(1));

        mockMvc.perform(post("/api/v1/support/tickets/" + ticketId + "/points/" + pointId + "/complete")
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedByKind").value("TENANT"));

        mockMvc.perform(get("/api/v1/support/tickets/" + ticketId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header("X-Test-User-Id", userIdFor())
                        .header("X-Test-Role-Id", ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].status").value("DONE"))
                .andExpect(jsonPath("$.points[1].status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.doneCount").value(1));
    }

    private String userIdFor() {
        return userRepository.findAll().stream()
                .filter(u -> TENANT_A.equals(u.getBusinessId()))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
