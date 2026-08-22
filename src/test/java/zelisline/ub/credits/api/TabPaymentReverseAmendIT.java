package zelisline.ub.credits.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import zelisline.ub.credits.CreditClaimStatuses;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditTransaction;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.PublicPaymentClaim;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
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
class TabPaymentReverseAmendIT {

    private static final String TENANT = "cabbabcd-aaaa-bbbb-cccc-000000000099";
    private static final String P_READ = "cabbabcd-aaaa-bbbb-cccc-000000000001";
    private static final String P_REVIEW = "cabbabcd-aaaa-bbbb-cccc-000000000002";
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
    private CreditAccountRepository creditAccountRepository;
    @Autowired
    private CreditTransactionRepository creditTransactionRepository;
    @Autowired
    private PublicPaymentClaimRepository publicPaymentClaimRepository;
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
        creditTransactionRepository.deleteAll();
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Tabs Co");
        b.setSlug("tabs-co");
        b.setSettings("{}");
        businessRepository.save(b);

        permissionRepository.save(perm(P_READ, "credits.customers.read", "read"));
        permissionRepository.save(perm(P_REVIEW, "credits.claims.review", "review"));

        Role cashierRole = role(ROLE_CASHIER, "cashier");
        roleRepository.save(cashierRole);
        grant(ROLE_CASHIER, P_READ);

        Role adminRole = role(ROLE_ADMIN, "admin");
        roleRepository.save(adminRole);
        for (String p : List.of(P_READ, P_REVIEW)) {
            grant(ROLE_ADMIN, p);
        }

        cashier = user("cashier@tabs.test", ROLE_CASHIER);
        userRepository.save(cashier);
        admin = user("admin@tabs.test", ROLE_ADMIN);
        userRepository.save(admin);

        Customer c = new Customer();
        c.setBusinessId(TENANT);
        c.setName("Ada Owe");
        customerRepository.save(c);
        customerId = c.getId();

        CreditAccount acc = new CreditAccount();
        acc.setBusinessId(TENANT);
        acc.setCustomerId(customerId);
        acc.setBalanceOwed(new BigDecimal("150.00"));
        acc.setWalletBalance(BigDecimal.ZERO);
        acc.setLoyaltyPoints(0);
        creditAccountRepository.save(acc);
    }

    @Test
    void reverseRestoresBalanceAndMarksClaimReversed() throws Exception {
        MvcResult recorded = mockMvc.perform(post("/api/v1/credits/tab-payments")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","amount":150,"channel":"cash","reference":"RCPT-A"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceOwed").value("0.00"))
                .andReturn();
        String claimId = objectMapper.readTree(recorded.getResponse().getContentAsString())
                .get("claimId").asText();

        mockMvc.perform(post("/api/v1/credits/tab-payments/reverse")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s"}
                                """.formatted(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceOwed").value("150.00"));

        assertThat(creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT)
                .orElseThrow()
                .getBalanceOwed())
                .isEqualByComparingTo("150.00");
        assertThat(publicPaymentClaimRepository.findById(claimId)
                .orElseThrow()
                .getStatus())
                .isEqualTo(CreditClaimStatuses.REVERSED);

        // A payment + a payment_reversal appear in the statement.
        List<CreditTransaction> txns = creditTransactionRepository
                .findByCreditAccountIdOrderByCreatedAtAsc(
                        creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT)
                                .orElseThrow()
                                .getId());
        assertThat(txns).hasSize(2);
        assertThat(txns.get(1).getTxnType()).isEqualTo("payment_reversal");

        mockMvc.perform(get("/api/v1/customers/" + customerId + "/credit-statement")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].kind").value("credit_payment_reversal"));
    }

    @Test
    void amendReversesAndRecordsCorrectedAmount() throws Exception {
        mockMvc.perform(post("/api/v1/credits/tab-payments")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","amount":150,"channel":"cash","reference":"RCPT-B"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceOwed").value("0.00"));

        mockMvc.perform(post("/api/v1/credits/tab-payments/amend")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","amount":50,"channel":"mpesa","reference":"MP-1"}
                                """.formatted(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceOwed").value("100.00"));

        assertThat(creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT)
                .orElseThrow()
                .getBalanceOwed())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void reverseWithoutPaymentIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/credits/tab-payments/reverse")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s"}
                                """.formatted(customerId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void reverseRequiresClaimsReview() throws Exception {
        mockMvc.perform(post("/api/v1/credits/tab-payments/reverse")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, cashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_CASHIER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s"}
                                """.formatted(customerId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void amendRejectsAmountAboveRestoredBalance() throws Exception {
        mockMvc.perform(post("/api/v1/credits/tab-payments")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","amount":150,"channel":"cash"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/credits/tab-payments/amend")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, admin.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_ADMIN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","amount":999,"channel":"cash"}
                                """.formatted(customerId)))
                .andExpect(status().isBadRequest());
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
