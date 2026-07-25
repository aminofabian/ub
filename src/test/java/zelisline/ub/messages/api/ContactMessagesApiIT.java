package zelisline.ub.messages.api;

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

import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;
import zelisline.ub.messages.repository.ContactMessageRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ContactMessagesApiIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SLUG_A = "contact-shop-a";
    private static final String SLUG_B = "contact-shop-b";

    private static final String P_READ = "11111111-aaaa-bbbb-cccc-000000000093";
    private static final String P_REPLY = "11111111-aaaa-bbbb-cccc-000000000094";
    private static final String ROLE_OWNER = "22222222-aaaa-bbbb-cccc-000000000001";
    private static final String ROLE_VIEWER = "22222222-aaaa-bbbb-cccc-000000000002";

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
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private ContactMessageRepository contactMessageRepository;

    @Autowired
    private ContactMessageReplyRepository contactMessageReplyRepository;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;
    private User viewerA;
    private String saToken;

    @BeforeEach
    void seed() throws Exception {
        contactMessageReplyRepository.deleteAll();
        contactMessageRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();
        superAdminRepository.deleteAll();

        seedShop(TENANT_A, SLUG_A);
        seedShop(TENANT_B, SLUG_B);

        permissionRepository.save(perm(P_READ, "messages.read", "read"));
        permissionRepository.save(perm(P_REPLY, "messages.reply", "reply"));

        Role ownerRole = systemRole(ROLE_OWNER, "owner");
        Role viewerRole = systemRole(ROLE_VIEWER, "viewer");
        roleRepository.save(ownerRole);
        roleRepository.save(viewerRole);

        grant(ROLE_OWNER, P_READ);
        grant(ROLE_OWNER, P_REPLY);
        // viewer: no messages permissions

        ownerA = user("owner@a.test", TENANT_A, ROLE_OWNER);
        viewerA = user("viewer@a.test", TENANT_A, ROLE_VIEWER);
        userRepository.save(ownerA);
        userRepository.save(viewerA);
        userRepository.save(user("owner@b.test", TENANT_B, ROLE_OWNER));

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops-contact@example.com");
        admin.setName("Ops Contact");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops-contact@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void platformSubmitAppearsInSuperAdminInbox() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/public/contact-messages")
                        .contentType(APPLICATION_JSON)
                        .content(submitBody("Ada", "ada@example.com", null, "Need a demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/super-admin/contact-messages")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].name").value("Ada"))
                .andExpect(jsonPath("$.content[0].status").value("UNREAD"));

        mockMvc.perform(get("/api/v1/super-admin/contact-messages/" + id)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Need a demo"))
                .andExpect(jsonPath("$.status").value("READ"));
    }

    @Test
    void tenantSubmitVisibleOnlyToThatTenant() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/public/businesses/" + SLUG_A + "/contact-messages")
                        .contentType(APPLICATION_JSON)
                        .content(submitBody("Ben", "ben@example.com", "0712345678", "Where is my order?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/contact-messages")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].phone").value("254712345678"));

        mockMvc.perform(get("/api/v1/contact-messages")
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, userIdForTenant(TENANT_B))
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/v1/contact-messages/" + id)
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, userIdForTenant(TENANT_B))
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNotFound());
    }

    @Test
    void platformMessagesInvisibleToTenantInbox() throws Exception {
        mockMvc.perform(post("/api/v1/public/contact-messages")
                        .contentType(APPLICATION_JSON)
                        .content(submitBody("Pat", "pat@example.com", null, "Platform only")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/contact-messages")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void viewerWithoutPermissionForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/contact-messages")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, viewerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_VIEWER))
                .andExpect(status().isForbidden());
    }

    @Test
    void validationRejectsMissingFields() throws Exception {
        mockMvc.perform(post("/api/v1/public/contact-messages")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"","email":"bad","message":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailReplySucceedsAndWhatsAppRequiresPhone() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/public/businesses/" + SLUG_A + "/contact-messages")
                        .contentType(APPLICATION_JSON)
                        .content(submitBody("Cara", "cara@example.com", null, "Hello shop")))
                .andExpect(status().isOk())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/contact-messages/" + id + "/replies")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"channel":"EMAIL","body":"Thanks for reaching out."}
                                """)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.outcome").value("sent"));

        mockMvc.perform(post("/api/v1/contact-messages/" + id + "/replies")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"channel":"WHATSAPP","body":"Hi on WhatsApp"}
                                """)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    private void seedShop(String tenantId, String slug) {
        Business b = new Business();
        b.setId(tenantId);
        b.setName("Shop " + slug);
        b.setSlug(slug);
        b.setSettings("{}");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(tenantId);
        br.setName("Main");
        br.setActive(true);
        String branchId = branchRepository.save(br).getId();

        b.setSettings(
                "{\"storefront\":{\"enabled\":true,\"catalogBranchId\":\"%s\"}}".formatted(branchId));
        businessRepository.save(b);
    }

    private static String submitBody(String name, String email, String phone, String message)
            throws Exception {
        var node = new ObjectMapper().createObjectNode()
                .put("name", name)
                .put("email", email)
                .put("message", message);
        if (phone != null) {
            node.put("phone", phone);
        }
        return node.toString();
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }

    private static Role systemRole(String id, String key) {
        Role r = new Role();
        r.setId(id);
        r.setBusinessId(null);
        r.setRoleKey(key);
        r.setName(key);
        r.setSystem(true);
        return r;
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }

    private User user(String email, String tenant, String roleId) {
        User u = new User();
        u.setBusinessId(tenant);
        u.setEmail(email);
        u.setName(email);
        u.setRoleId(roleId);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }

    private String userIdForTenant(String tenantId) {
        return userRepository.findAll().stream()
                .filter(u -> tenantId.equals(u.getBusinessId()))
                .filter(u -> ROLE_OWNER.equals(u.getRoleId()))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
