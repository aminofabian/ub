package zelisline.ub.credits.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.credits.CreditClaimStatuses;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.JournalLine;
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
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Loosen so the test does not race the global filter.
        "app.security.public-credit-claim-rate-limit-per-minute=200"
})
class PublicPaymentClaimsApiIT {

    private static final String TENANT = "11119999-aaaa-bbbb-cccc-000000000001";
    private static final String P_ISSUE = "21111111-aaaa-bbbb-cccc-000000000001";
    private static final String P_REVIEW = "21111111-aaaa-bbbb-cccc-000000000002";
    private static final String ROLE_OWNER = "31111111-aaaa-bbbb-cccc-000000000001";

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
    private CreditTransactionRepository creditTransactionRepository;
    @Autowired
    private PublicPaymentClaimRepository publicPaymentClaimRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private JournalLineRepository journalLineRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
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

    private User owner;
    private CreditAccount account;

    @BeforeEach
    void seed() {
        publicPaymentClaimRepository.deleteAll();
        creditTransactionRepository.deleteAll();
        customerPhoneRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Claim Co");
        b.setSlug("claim-co");
        businessRepository.save(b);

        permissionRepository.save(perm(P_ISSUE, "credits.claims.issue", "issue claim"));
        permissionRepository.save(perm(P_REVIEW, "credits.claims.review", "review claim"));
        Role r = new Role();
        r.setId(ROLE_OWNER);
        r.setBusinessId(null);
        r.setRoleKey("claims_owner");
        r.setName("claims_owner");
        r.setSystem(true);
        roleRepository.save(r);
        for (String p : List.of(P_ISSUE, P_REVIEW)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, p));
            rolePermissionRepository.save(rp);
        }

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("o@claim.test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        Customer c = new Customer();
        c.setBusinessId(TENANT);
        c.setName("Tab Holder");
        customerRepository.save(c);

        CreditAccount a = new CreditAccount();
        a.setBusinessId(TENANT);
        a.setCustomerId(c.getId());
        a.setBalanceOwed(new BigDecimal("500.00"));
        creditAccountRepository.save(a);
        account = a;

        seedLedger(LedgerAccountCodes.OPERATING_CASH, "Cash", "asset");
        seedLedger(LedgerAccountCodes.MPESA_CLEARING, "Mpesa", "asset");
        seedLedger(LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS, "AR", "asset");
    }

    @Test
    void issue_submit_approveCash_postsJournalAndReducesBalance() throws Exception {
        String token = issueToken(account.getCustomerId());

        // Public submit — no auth header.
        mockMvc.perform(post("/api/v1/public/credits/payment-claims/" + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":150,\"reference\":\"REF1\"}"))
                .andExpect(status().isNoContent());

        String claimId = publicPaymentClaimRepository.findAll().getFirst().getId();
        approve(claimId, "cash").andExpect(status().isNoContent());

        var refreshed = creditAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(refreshed.getBalanceOwed()).isEqualByComparingTo("350.00");
        assertThat(creditTransactionRepository.findAll()).hasSize(1);
        assertThat(publicPaymentClaimRepository.findById(claimId).orElseThrow().getStatus())
                .isEqualTo(CreditClaimStatuses.APPROVED);
        // Ledger: Dr 1010 / Cr 1100 for 150.
        assertJournal(LedgerAccountCodes.OPERATING_CASH, new BigDecimal("150.00"), true);
        assertJournal(LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS, new BigDecimal("150.00"), false);
    }

    @Test
    void approveMpesa_routesToMpesaClearing() throws Exception {
        String token = issueToken(account.getCustomerId());
        mockMvc.perform(post("/api/v1/public/credits/payment-claims/" + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":75}"))
                .andExpect(status().isNoContent());

        String claimId = publicPaymentClaimRepository.findAll().getFirst().getId();
        approve(claimId, "mpesa").andExpect(status().isNoContent());

        assertJournal(LedgerAccountCodes.MPESA_CLEARING, new BigDecimal("75.00"), true);
        assertJournal(LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS, new BigDecimal("75.00"), false);
    }

    @Test
    void approveTwice_isSilentNoop() throws Exception {
        String token = issueToken(account.getCustomerId());
        mockMvc.perform(post("/api/v1/public/credits/payment-claims/" + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":40}"))
                .andExpect(status().isNoContent());
        String claimId = publicPaymentClaimRepository.findAll().getFirst().getId();

        approve(claimId, "cash").andExpect(status().isNoContent());
        approve(claimId, "cash").andExpect(status().isNoContent());

        assertThat(creditTransactionRepository.findAll()).hasSize(1);
        var refreshed = creditAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(refreshed.getBalanceOwed()).isEqualByComparingTo("460.00");
    }

    @Test
    void approveInvalidChannel_returns400() throws Exception {
        String token = issueToken(account.getCustomerId());
        mockMvc.perform(post("/api/v1/public/credits/payment-claims/" + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":10}"))
                .andExpect(status().isNoContent());
        String claimId = publicPaymentClaimRepository.findAll().getFirst().getId();
        approve(claimId, "card").andExpect(status().isBadRequest());
    }

    @Test
    void rejectClaim_thenApprove_returns409() throws Exception {
        String token = issueToken(account.getCustomerId());
        mockMvc.perform(post("/api/v1/public/credits/payment-claims/" + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":99}"))
                .andExpect(status().isNoContent());
        String claimId = publicPaymentClaimRepository.findAll().getFirst().getId();

        mockMvc.perform(post("/api/v1/credits/payment-claims/" + claimId + "/reject")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"duplicate\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        approve(claimId, "cash").andExpect(status().isConflict());
        // Re-rejecting is a no-op.
        mockMvc.perform(post("/api/v1/credits/payment-claims/" + claimId + "/reject")
                        .contentType(APPLICATION_JSON)
                        .content("{}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        assertThat(publicPaymentClaimRepository.findById(claimId).orElseThrow().getStatus())
                .isEqualTo(CreditClaimStatuses.REJECTED);
    }

    @Test
    void byPhoneEndpointIsRemoved() throws Exception {
        // Old phone-fallback path must not silently succeed — anything other than 2xx is acceptable.
        // We accept 4xx (no handler match / validation failure) or 5xx (no-handler exception). The
        // important guarantee is "no 204 for the legacy contract" (ADR-0010).
        var result = mockMvc.perform(post("/api/v1/public/credits/payment-claims/by-phone/" + TENANT + "/0700000000")
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":1}"))
                .andReturn();
        int status = result.getResponse().getStatus();
        assertThat(status >= 200 && status < 300).isFalse();
    }

    private String issueToken(String customerId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/customers/" + customerId + "/payment-claims")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
        return node.get("plaintextToken").asText();
    }

    private org.springframework.test.web.servlet.ResultActions approve(String claimId, String channel) throws Exception {
        return mockMvc.perform(post("/api/v1/credits/payment-claims/" + claimId + "/approve")
                .contentType(APPLICATION_JSON)
                .content("{\"channel\":\"" + channel + "\"}")
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER));
    }

    private void seedLedger(String code, String name, String type) {
        if (ledgerAccountRepository.existsByBusinessIdAndCode(TENANT, code)) {
            return;
        }
        LedgerAccount a = new LedgerAccount();
        a.setBusinessId(TENANT);
        a.setCode(code);
        a.setName(name);
        a.setAccountType(type);
        ledgerAccountRepository.save(a);
    }

    private void assertJournal(String code, BigDecimal amount, boolean debit) {
        LedgerAccount account = ledgerAccountRepository.findByBusinessIdAndCode(TENANT, code).orElseThrow();
        BigDecimal sum = BigDecimal.ZERO;
        for (JournalLine l : journalLineRepository.findAll()) {
            if (account.getId().equals(l.getLedgerAccountId())) {
                sum = sum.add(debit ? l.getDebit() : l.getCredit());
            }
        }
        assertThat(sum).isEqualByComparingTo(amount);
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
