package zelisline.ub.credits.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

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

import zelisline.ub.credits.CreditClaimSources;
import zelisline.ub.credits.CreditClaimStatuses;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.PublicPaymentClaim;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
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
class CashierTabClearanceIT {

    private static final String TENANT = "cabbabcd-aaaa-bbbb-cccc-000000000099";
    private static final String P_READ = "cabbabcd-aaaa-bbbb-cccc-000000000001";
    private static final String P_REVIEW = "cabbabcd-aaaa-bbbb-cccc-000000000002";
    private static final String P_SETTINGS = "cabbabcd-aaaa-bbbb-cccc-000000000003";
    private static final String ROLE_CASHIER = "cabbabcd-aaaa-bbbb-cccc-000000000011";
    private static final String ROLE_ADMIN = "cabbabcd-aaaa-bbbb-cccc-000000000012";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private CustomerPhoneRepository customerPhoneRepository;
    @Autowired
    private CreditAccountRepository creditAccountRepository;
    @Autowired
    private PublicPaymentClaimRepository publicPaymentClaimRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private JournalLineRepository journalLineRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User cashier;
    private User admin;
    private String customerId;

    @BeforeEach
    void seed() {
        publicPaymentClaimRepository.deleteAll();
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        customerPhoneRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Tabs Co");
        b.setSlug("tabs-co");
        b.setSettings("{}");
        businessRepository.save(b);

        permissionRepository.save(perm(P_READ, "credits.customers.read", "read"));
        permissionRepository.save(perm(P_REVIEW, "credits.claims.review", "review"));
        permissionRepository.save(perm(P_SETTINGS, "business.manage_settings", "settings"));

        Role cashierRole = role(ROLE_CASHIER, "cashier");
        roleRepository.save(cashierRole);
        grant(ROLE_CASHIER, P_READ);

        Role adminRole = role(ROLE_ADMIN, "admin");
        roleRepository.save(adminRole);
        for (String p : List.of(P_READ, P_REVIEW, P_SETTINGS)) {
            grant(ROLE_ADMIN, p);
        }

        cashier = user("cashier@tabs.test", ROLE_CASHIER);
        userRepository.save(cashier);
        admin = user("admin@tabs.test", ROLE_ADMIN);
        userRepository.save(admin);

        ensureLedger(LedgerAccountCodes.OPERATING_CASH);
        ensureLedger(LedgerAccountCodes.MPESA_CLEARING);
        ensureLedger(LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS);

        Customer c = new Customer();
        c.setBusinessId(TENANT);
        c.setName("Ada Owe");
        customerRepository.save(c);
        customerId = c.getId();

        CustomerPhone phone = new CustomerPhone();
        phone.setBusinessId(TENANT);
        phone.setCustomerId(customerId);
        phone.setPhone("254700000001");
        phone.setPrimary(true);
        customerPhoneRepository.save(phone);

        CreditAccount acc = new CreditAccount();
        acc.setBusinessId(TENANT);
        acc.setCustomerId(customerId);
        acc.setBalanceOwed(new BigDecimal("150.00"));
        acc.setWalletBalance(BigDecimal.ZERO);
        acc.setLoyaltyPoints(0);
        creditAccountRepository.save(acc);
    }

    @Test
    void proposeBlockedWhenToggleOff() throws Exception {
        mockMvc.perform(post("/api/v1/credits/tab-clearances")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_CASHIER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","amount":50,"channel":"cash"}
                                """.formatted(customerId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void proposeCreatesSubmittedClaimAndAdminApproves() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"inventory":{"creditTabs":{"allowCashierTabClearance":true}}}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/customers/outstanding-tabs")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_CASHIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerId").value(customerId))
                .andExpect(jsonPath("$[0].balanceOwed").value(150.00));

        MvcResult created = mockMvc.perform(post("/api/v1/credits/tab-clearances")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_CASHIER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","amount":50,"channel":"cash","reference":"RCPT-1"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn();
        String claimId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("claimId").asText();

        PublicPaymentClaim claim = publicPaymentClaimRepository.findById(claimId).orElseThrow();
        assertThat(claim.getStatus()).isEqualTo(CreditClaimStatuses.SUBMITTED);
        assertThat(claim.getSource()).isEqualTo(CreditClaimSources.CASHIER);
        assertThat(claim.getProposedChannel()).isEqualTo("cash");
        assertThat(claim.getSubmittedAmount()).isEqualByComparingTo("50.00");

        mockMvc.perform(get("/api/v1/credits/payment-claims/submitted")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("cashier"))
                .andExpect(jsonPath("$[0].customerName").value("Ada Owe"))
                .andExpect(jsonPath("$[0].proposedChannel").value("cash"));

        mockMvc.perform(post("/api/v1/credits/payment-claims/" + claimId + "/approve")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"channel\":\"cash\"}"))
                .andExpect(status().isNoContent());

        assertThat(creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT)
                .orElseThrow()
                .getBalanceOwed())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void proposeRejectsAmountAboveOwed() throws Exception {
        enableToggle();
        mockMvc.perform(post("/api/v1/credits/tab-clearances")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_CASHIER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","amount":999,"channel":"mpesa"}
                                """.formatted(customerId)))
                .andExpect(status().isBadRequest());
    }

    private void enableToggle() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"inventory":{"creditTabs":{"allowCashierTabClearance":true}}}
                                """))
                .andExpect(status().isOk());
    }

    private void ensureLedger(String code) {
        LedgerAccount a = new LedgerAccount();
        a.setBusinessId(TENANT);
        a.setCode(code);
        a.setName(code);
        a.setAccountType("asset");
        ledgerAccountRepository.save(a);
    }

    private User user(String email, String roleId) {
        User u = new User();
        u.setBusinessId(TENANT);
        u.setEmail(email);
        u.setName(email);
        u.setRoleId(roleId);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }

    private static Role role(String id, String key) {
        Role r = new Role();
        r.setId(id);
        r.setBusinessId(null);
        r.setRoleKey(key);
        r.setName(key);
        r.setSystem(true);
        return r;
    }

    private void grant(String roleId, String permId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permId));
        rolePermissionRepository.save(rp);
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
