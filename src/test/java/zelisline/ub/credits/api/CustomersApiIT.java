package zelisline.ub.credits.api;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.credits.repository.BusinessCreditSettingsRepository;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.LoyaltyTransactionRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;
import zelisline.ub.credits.repository.WalletTransactionRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CustomersApiIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static final String P_READ = "11111111-aaaa-bbbb-cccc-000000000071";
    private static final String P_WRITE = "11111111-aaaa-bbbb-cccc-000000000072";
    private static final String ROLE_OWNER = "22222222-aaaa-bbbb-cccc-000000000001";
    private static final String ROLE_VIEWER = "22222222-aaaa-bbbb-cccc-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CustomerPhoneRepository customerPhoneRepository;

    @Autowired
    private CreditAccountRepository creditAccountRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private LoyaltyTransactionRepository loyaltyTransactionRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private PublicPaymentClaimRepository publicPaymentClaimRepository;

    @Autowired
    private MpesaStkIntentRepository mpesaStkIntentRepository;

    @Autowired
    private BusinessCreditSettingsRepository businessCreditSettingsRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;
    private User viewerA;

    @BeforeEach
    void seed() {
        mpesaStkIntentRepository.deleteAll();
        publicPaymentClaimRepository.deleteAll();
        loyaltyTransactionRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        creditTransactionRepository.deleteAll();
        customerPhoneRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessCreditSettingsRepository.deleteAll();
        businessRepository.deleteAll();

        insertBusiness(TENANT_A, "tenant-a-cust");
        insertBusiness(TENANT_B, "tenant-b-cust");

        permissionRepository.save(perm(P_READ, "credits.customers.read", "read"));
        permissionRepository.save(perm(P_WRITE, "credits.customers.write", "write"));

        Role ownerRole = systemRole(ROLE_OWNER, "owner");
        Role viewerRole = systemRole(ROLE_VIEWER, "viewer");
        roleRepository.save(ownerRole);
        roleRepository.save(viewerRole);

        grant(ROLE_OWNER, P_READ);
        grant(ROLE_OWNER, P_WRITE);
        grant(ROLE_VIEWER, P_READ);

        ownerA = user("owner@a.test", TENANT_A, ROLE_OWNER);
        viewerA = user("viewer@a.test", TENANT_A, ROLE_VIEWER);
        userRepository.save(ownerA);
        userRepository.save(viewerA);
        userRepository.save(user("owner@b.test", TENANT_B, ROLE_OWNER));
    }

    @Test
    void createListsPhonesAndCreditAccount() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("Ada", "0722111000", true))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ada"))
                .andExpect(jsonPath("$.phones[0].phone").value("0722111000"))
                .andExpect(jsonPath("$.phones[0].primary").value(true))
                .andExpect(jsonPath("$.credit.balanceOwed").value(0))
                .andExpect(jsonPath("$.credit.walletBalance").value(0))
                .andReturn();

        JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
        String id = node.get("id").asText();

        mockMvc.perform(get("/api/v1/customers/" + id)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phones[0].phone").value("0722111000"));
    }

    @Test
    void duplicateNormalizedPhoneRejected() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("First", "0722333444", true))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("Second", "072-233-3444", true))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    @Test
    void crossTenantCustomerLookupReturns404() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("Other tenant", "0700000001", true))
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, userIdForTenant(TENANT_B))
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();

        String otherId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/customers/" + otherId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNotFound());
    }

    @Test
    void phoneSearchRespectsTenantBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("B only", "0700000099", true))
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, userIdForTenant(TENANT_B))
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/customers")
                        .param("phone", "0700000099")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void readOnlyRoleCannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("No", "0700000022", true))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, viewerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_VIEWER))
                .andExpect(status().isForbidden());
    }

    private static String createBody(String name, String phone, boolean primary) throws Exception {
        ObjectMapper om = new ObjectMapper();
        return om.createObjectNode()
                .put("name", name)
                .set("phones", om.createArrayNode().add(om.createObjectNode()
                        .put("phone", phone)
                        .put("primary", primary)))
                .toString();
    }

    private void insertBusiness(String id, String slug) {
        Business b = new Business();
        b.setId(id);
        b.setName("B " + id.substring(0, 8));
        b.setSlug(slug);
        businessRepository.save(b);
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
