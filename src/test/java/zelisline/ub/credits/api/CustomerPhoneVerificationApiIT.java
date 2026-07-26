package zelisline.ub.credits.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.repository.BusinessCreditSettingsRepository;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerPhoneVerificationRepository;
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
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.infrastructure.RapidApiWhatsAppLookupClient;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CustomerPhoneVerificationApiIT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String P_READ = "11111111-cccc-dddd-eeee-000000000071";
    private static final String P_WRITE = "11111111-cccc-dddd-eeee-000000000072";
    private static final String ROLE_OWNER = "22222222-cccc-dddd-eeee-000000000001";
    private static final Pattern OTP_IN_MESSAGE = Pattern.compile("\\b(\\d{4})\\b");

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
    private CustomerPhoneVerificationRepository verificationRepository;
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

    @MockitoBean
    private CustomerMessageDispatcher customerMessageDispatcher;

    @MockitoBean
    private BusinessCreditMessagingSettingsService messagingSettingsService;

    private User owner;
    private ArgumentCaptor<String> messageCaptor;

    @BeforeEach
    void seed() {
        mpesaStkIntentRepository.deleteAll();
        publicPaymentClaimRepository.deleteAll();
        loyaltyTransactionRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        creditTransactionRepository.deleteAll();
        verificationRepository.deleteAll();
        customerPhoneRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessCreditSettingsRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("OTP Co");
        b.setSlug("otp-co");
        businessRepository.save(b);

        permissionRepository.save(perm(P_READ, "credits.customers.read"));
        permissionRepository.save(perm(P_WRITE, "credits.customers.write"));
        Role ownerRole = systemRole(ROLE_OWNER, "owner");
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, P_READ);
        grant(ROLE_OWNER, P_WRITE);

        owner = user("owner@otp.test", TENANT, ROLE_OWNER);
        userRepository.save(owner);

        when(messagingSettingsService.resolveForTest(TENANT)).thenReturn(testMessagingConfig());
        messageCaptor = ArgumentCaptor.forClass(String.class);
        when(customerMessageDispatcher.deliver(any(), anyString(), messageCaptor.capture()))
                .thenReturn(new CustomerMessageDispatcher.DeliveryResult(
                        RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("test"),
                        "sms",
                        "sent",
                        "test"));
    }

    @Test
    void sendRejectsExistingPhone() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("Ada", "0711222333", null))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/customers/phone-verifications")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"0711222333\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    @Test
    void verifyWrongCodeThenSucceedAndCreateWithToken() throws Exception {
        mockMvc.perform(post("/api/v1/customers/phone-verifications")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"0711999888\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("0711999888"))
                .andExpect(jsonPath("$.channel").value("sms"));

        String code = extractOtp(messageCaptor.getValue());

        mockMvc.perform(post("/api/v1/customers/phone-verifications/verify")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"0711999888\",\"code\":\"0000\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());

        MvcResult verified = mockMvc.perform(post("/api/v1/customers/phone-verifications/verify")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"0711999888\",\"code\":\"" + code + "\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneVerificationToken").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(verified.getResponse().getContentAsString())
                .get("phoneVerificationToken")
                .asText();

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("Verified Ada", "0711999888", "bad-token"))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());

        MvcResult created = mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("Verified Ada", "0711999888", token))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Verified Ada"))
                .andExpect(jsonPath("$.phones[0].verifiedAt").isNotEmpty())
                .andReturn();

        JsonNode phones = objectMapper.readTree(created.getResponse().getContentAsString()).get("phones");
        assertThat(phones.get(0).get("verifiedAt").asText()).isNotBlank();

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("Reuse", "0711999888", token))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    @Test
    void createWithoutTokenStillWorks() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(APPLICATION_JSON)
                        .content(createBody("Directory Ada", "0700111222", null))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phones[0].verifiedAt").isEmpty());
    }

    @Test
    void tooManyWrongAttemptsBlocked() throws Exception {
        mockMvc.perform(post("/api/v1/customers/phone-verifications")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"0700555666\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/customers/phone-verifications/verify")
                            .contentType(APPLICATION_JSON)
                            .content("{\"phone\":\"0700555666\",\"code\":\"1111\"}")
                            .header("X-Tenant-Id", TENANT)
                            .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                            .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                    .andExpect(i < 4 ? status().isBadRequest() : status().isTooManyRequests());
        }
    }

    private static String extractOtp(String message) {
        Matcher m = OTP_IN_MESSAGE.matcher(message);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static String createBody(String name, String phone, String token) throws Exception {
        ObjectMapper om = new ObjectMapper();
        var node = om.createObjectNode();
        node.put("name", name);
        node.set("phones", om.createArrayNode().add(om.createObjectNode()
                .put("phone", phone)
                .put("primary", true)));
        if (token != null) {
            node.put("phoneVerificationToken", token);
        }
        return node.toString();
    }

    private static TenantMessagingConfig testMessagingConfig() {
        return new TenantMessagingConfig(
                true,
                "https://example.test/pay",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "v21.0",
                "none",
                "africas_talking",
                "test-user",
                "test-key",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null);
    }

    private static Permission perm(String id, String key) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(key);
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
}
