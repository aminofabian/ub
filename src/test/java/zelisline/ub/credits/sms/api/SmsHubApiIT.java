package zelisline.ub.credits.sms.api;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.BusinessCreditSettingsRepository;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.LoyaltyTransactionRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;
import zelisline.ub.credits.repository.WalletTransactionRepository;
import zelisline.ub.credits.sms.domain.SmsMarketingStatus;
import zelisline.ub.credits.sms.repository.BusinessSmsHubSettingsRepository;
import zelisline.ub.credits.sms.repository.CustomerSmsCampaignRecipientRepository;
import zelisline.ub.credits.sms.repository.CustomerSmsCampaignRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.messaging.repository.SmsMessageRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * SMS Hub API: tenant isolation, consent rules and campaign drafting. No SMS is ever
 * sent here (the test profile has no provider); sending is covered by unit tests of
 * the guard and by the manual checks in docs/scopes/SMS_HUB_SCOPE.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.sms-hub.campaigns.enabled=false",
        "app.sms-hub.winback.enabled=false"
})
class SmsHubApiIT {

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
    @Autowired
    private SmsMessageRepository smsMessageRepository;
    @Autowired
    private CustomerSmsCampaignRepository campaignRepository;
    @Autowired
    private CustomerSmsCampaignRecipientRepository recipientRepository;
    @Autowired
    private BusinessSmsHubSettingsRepository hubSettingsRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;
    private User viewerA;
    private User ownerB;
    private Customer ada;
    private Customer ben;
    private Customer masked;
    private Customer zed;

    @BeforeEach
    void seed() {
        smsMessageRepository.deleteAll();
        recipientRepository.deleteAll();
        campaignRepository.deleteAll();
        hubSettingsRepository.deleteAll();
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

        insertBusiness(TENANT_A, "tenant-a-sms");
        insertBusiness(TENANT_B, "tenant-b-sms");

        permissionRepository.save(perm(P_READ, "credits.customers.read", "read"));
        permissionRepository.save(perm(P_WRITE, "credits.customers.write", "write"));
        roleRepository.save(systemRole(ROLE_OWNER, "owner"));
        roleRepository.save(systemRole(ROLE_VIEWER, "viewer"));
        grant(ROLE_OWNER, P_READ);
        grant(ROLE_OWNER, P_WRITE);
        grant(ROLE_VIEWER, P_READ);

        ownerA = userRepository.save(user("owner@a-sms.test", TENANT_A, ROLE_OWNER));
        viewerA = userRepository.save(user("viewer@a-sms.test", TENANT_A, ROLE_VIEWER));
        ownerB = userRepository.save(user("owner@b-sms.test", TENANT_B, ROLE_OWNER));

        ada = customer(TENANT_A, "Ada Wanjiku", SmsMarketingStatus.OPTED_IN, "0722111000", null);
        ben = customer(TENANT_A, "Ben Otieno", SmsMarketingStatus.UNKNOWN, "0733222000", null);
        masked = customer(TENANT_A, "Mpesa Payer", SmsMarketingStatus.UNKNOWN, null, "2547XXXXX123");
        zed = customer(TENANT_B, "Zed Other", SmsMarketingStatus.UNKNOWN, "0744333000", null);
    }

    @Test
    void contactsListOnlyOwnCustomersWithReachability() throws Exception {
        mockMvc.perform(get("/api/v1/sms-hub/contacts")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.counts.total").value(3))
                .andExpect(jsonPath("$.counts.canText").value(1))
                .andExpect(jsonPath("$.counts.receiptsOnly").value(1))
                .andExpect(jsonPath("$.counts.noNumber").value(1))
                .andExpect(jsonPath("$.rows[?(@.name == 'Zed Other')]").isEmpty());

        mockMvc.perform(get("/api/v1/sms-hub/contacts").param("reach", "NO_NUMBER")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.rows[0].name").value("Mpesa Payer"))
                .andExpect(jsonPath("$.rows[0].maskedNumber").value("2547XXXXX123"));
    }

    @Test
    void consentUpdateCannotReachAnotherTenantsCustomer() throws Exception {
        mockMvc.perform(post("/api/v1/sms-hub/contacts/consent")
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerIds\":[\"" + zed.getId() + "\"],\"status\":\"OPTED_IN\","
                                + "\"customerAgreed\":true}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(0));

        assertThat(customerRepository.findById(zed.getId()).orElseThrow().getSmsMarketingStatus())
                .isEqualTo(SmsMarketingStatus.UNKNOWN);
    }

    @Test
    void optInNeedsStaffToConfirmTheCustomerAgreed() throws Exception {
        String ids = "[\"" + ben.getId() + "\"]";
        mockMvc.perform(post("/api/v1/sms-hub/contacts/consent")
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerIds\":" + ids + ",\"status\":\"OPTED_IN\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/sms-hub/contacts/consent")
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerIds\":" + ids + ",\"status\":\"OPTED_IN\",\"customerAgreed\":true}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(1));

        Customer saved = customerRepository.findById(ben.getId()).orElseThrow();
        assertThat(saved.getSmsMarketingStatus()).isEqualTo(SmsMarketingStatus.OPTED_IN);
        assertThat(saved.getSmsMarketingSource()).isEqualTo(SmsMarketingStatus.SOURCE_STAFF);
        assertThat(saved.getSmsMarketingUpdatedAt()).isNotNull();
    }

    @Test
    void readOnlyRoleCannotChangeConsentOrCreateCampaigns() throws Exception {
        mockMvc.perform(post("/api/v1/sms-hub/contacts/consent")
                        .contentType(APPLICATION_JSON)
                        .content("{\"customerIds\":[\"" + ben.getId() + "\"],\"status\":\"OPTED_OUT\"}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, viewerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_VIEWER))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/customer-sms-campaigns")
                        .contentType(APPLICATION_JSON)
                        .content(campaignBody(ada.getId()))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, viewerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_VIEWER))
                .andExpect(status().isForbidden());
    }

    @Test
    void draftKeepsOnlyCustomersWhoAgreedAndCanBeTexted() throws Exception {
        mockMvc.perform(post("/api/v1/customer-sms-campaigns")
                        .contentType(APPLICATION_JSON)
                        .content(campaignBody(ada.getId(), ben.getId(), masked.getId()))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.recipientsTargeted").value(3))
                .andExpect(jsonPath("$.recipientsPending").value(1))
                .andExpect(jsonPath("$.recipientsSkipped").value(2))
                .andExpect(jsonPath("$.partsPerMessage").value(1))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("STOP")));
    }

    @Test
    void campaignIsInvisibleToAnotherTenant() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/customer-sms-campaigns")
                        .contentType(APPLICATION_JSON)
                        .content(campaignBody(ada.getId()))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/customer-sms-campaigns/" + id)
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerB.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/customer-sms-campaigns/" + id + "/cancel")
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerB.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/customer-sms-campaigns")
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerB.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/v1/sms-hub/messages").param("campaignId", id)
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerB.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void campaignCannotBeSentWhileSmsIsNotSetUp() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/customer-sms-campaigns")
                        .contentType(APPLICATION_JSON)
                        .content(campaignBody(ada.getId()))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // Outside the sending window this is refused for the hour; inside it, because the
        // test profile has no SMS provider. Either way nothing is queued.
        mockMvc.perform(post("/api/v1/customer-sms-campaigns/" + id + "/send")
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());

        assertThat(campaignRepository.findById(id).orElseThrow().getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void unknownVariableInWordingIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/customer-sms-campaigns")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"body\":\"Hi {surname}\",\"recipientMethod\":\"specific\","
                                + "\"customerIds\":[\"" + ada.getId() + "\"]}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void automationsDefaultToOrderUpdatesOnAndSaveChanges() throws Exception {
        mockMvc.perform(get("/api/v1/sms-hub/automations")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNotices.confirmed.enabled").value(true))
                .andExpect(jsonPath("$.thankYouEnabled").value(false))
                .andExpect(jsonPath("$.promoWeeklyCap").value(1));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/sms-hub/automations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"orderNotices\":{\"confirmed\":{\"enabled\":false}},"
                                + "\"winbackEnabled\":true,\"winbackDays\":45}")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNotices.confirmed.enabled").value(false))
                .andExpect(jsonPath("$.orderNotices.dispatched.enabled").value(true))
                .andExpect(jsonPath("$.winbackDays").value(45));

        // The other shop still has the defaults.
        mockMvc.perform(get("/api/v1/sms-hub/automations")
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerB.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNotices.confirmed.enabled").value(true))
                .andExpect(jsonPath("$.winbackEnabled").value(false));
    }

    private static String campaignBody(String... customerIds) {
        StringBuilder ids = new StringBuilder();
        for (String id : customerIds) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append('"').append(id).append('"');
        }
        return "{\"name\":\"November offer\",\"body\":\"Hi {first_name}, 10% off at {shop} this week.\","
                + "\"recipientMethod\":\"specific\",\"customerIds\":[" + ids + "]}";
    }

    private Customer customer(String tenant, String name, String status, String phone, String mask) {
        Customer c = new Customer();
        c.setBusinessId(tenant);
        c.setName(name);
        c.setSmsMarketingStatus(status);
        c = customerRepository.save(c);
        CustomerPhone p = new CustomerPhone();
        p.setBusinessId(tenant);
        p.setCustomerId(c.getId());
        p.setPhone(phone);
        p.setMaskedMsisdn(mask);
        p.setPrimary(true);
        customerPhoneRepository.save(p);
        return c;
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

    private static User user(String email, String tenant, String roleId) {
        User u = new User();
        u.setBusinessId(tenant);
        u.setEmail(email);
        u.setName(email);
        u.setRoleId(roleId);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }
}
