package zelisline.ub.credits.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.credits.MpesaStkIntentPurposes;
import zelisline.ub.credits.MpesaStkStatuses;
import zelisline.ub.credits.CreditClaimSources;
import zelisline.ub.credits.CreditClaimStatuses;
import zelisline.ub.credits.application.CreditSaleDebtService;
import zelisline.ub.credits.application.CreditsJournalService;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.MpesaStkIntent;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;
import zelisline.ub.credits.repository.PublicPaymentClaimRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.security.public-credit-claim-rate-limit-per-minute=200"
})
class PublicCustomerTabApiIT {

    private static final String TENANT = "11118888-aaaa-bbbb-cccc-000000000001";
    private static final String PHONE = "0714282874";

    @Autowired
    private MockMvc mockMvc;
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
    private MpesaStkIntentRepository mpesaStkIntentRepository;
    @Autowired
    private PublicPaymentClaimRepository publicPaymentClaimRepository;
    @Autowired
    private CreditSaleDebtService creditSaleDebtService;
    @Autowired
    private CreditsJournalService creditsJournalService;
    @Autowired
    private zelisline.ub.credits.application.CustomerPhoneOnPaymentService customerPhoneOnPaymentService;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private JournalLineRepository journalLineRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private CreditAccount account;

    @BeforeEach
    void seed() {
        mpesaStkIntentRepository.deleteAll();
        publicPaymentClaimRepository.deleteAll();
        creditTransactionRepository.deleteAll();
        customerPhoneRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Palmart");
        b.setSlug("palmart-tab");
        b.setCurrency("KES");
        businessRepository.save(b);

        Customer c = new Customer();
        c.setBusinessId(TENANT);
        c.setName("Amina");
        customerRepository.save(c);

        CustomerPhone phone = new CustomerPhone();
        phone.setBusinessId(TENANT);
        phone.setCustomerId(c.getId());
        phone.setPhone(PHONE);
        phone.setPrimary(true);
        customerPhoneRepository.save(phone);

        CreditAccount a = new CreditAccount();
        a.setBusinessId(TENANT);
        a.setCustomerId(c.getId());
        a.setBalanceOwed(new BigDecimal("500.00"));
        creditAccountRepository.save(a);
        account = a;

        seedLedger(LedgerAccountCodes.MPESA_CLEARING, "Mpesa", "asset");
        seedLedger(LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS, "AR", "asset");
    }

    @Test
    void overview_returnsBalanceAndShopName() throws Exception {
        mockMvc.perform(get("/api/v1/public/credits/tabs/" + PHONE)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Amina"))
                .andExpect(jsonPath("$.shopName").value("Palmart"))
                .andExpect(jsonPath("$.balanceOwed").value(500.00))
                .andExpect(jsonPath("$.phoneDisplay").value(PHONE));
    }

    @Test
    void overview_accepts254Form() throws Exception {
        mockMvc.perform(get("/api/v1/public/credits/tabs/254714282874")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceOwed").value(500.00));
    }

    @Test
    void overview_unknownPhone_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/public/credits/tabs/0799999999")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound());
    }

    @Test
    void stk_amountExceedingBalance_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/public/credits/tabs/" + PHONE + "/stk")
                        .header("X-Tenant-Id", TENANT)
                        .header("Idempotency-Key", "tab-over-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":600}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stk_createsArIntent_andSettlementReducesBalance() throws Exception {
        mockMvc.perform(post("/api/v1/public/credits/tabs/" + PHONE + "/stk")
                        .header("X-Tenant-Id", TENANT)
                        .header("Idempotency-Key", "tab-ok-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":150}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(MpesaStkStatuses.PENDING))
                .andExpect(jsonPath("$.amount").value(150.00));

        MpesaStkIntent intent = mpesaStkIntentRepository.findAll().getFirst();
        assertThat(intent.getPurpose()).isEqualTo(MpesaStkIntentPurposes.AR);
        assertThat(intent.getAmount()).isEqualByComparingTo("150.00");
        assertThat(intent.getStkPhone()).isEqualTo(PHONE);

        // Simulate gateway success settlement path used by CREDIT_AR confirm.
        creditSaleDebtService.applyInboundArPayment(TENANT, account.getId(), intent.getAmount());
        creditsJournalService.postInboundMpesaTowardAr(
                TENANT, intent.getAmount(), intent.getId(), "M-Pesa STK tab payment");
        intent.setStatus(MpesaStkStatuses.FULFILLED);
        mpesaStkIntentRepository.save(intent);

        var refreshed = creditAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(refreshed.getBalanceOwed()).isEqualByComparingTo("350.00");
        assertThat(creditTransactionRepository.findAll()).hasSize(1);

        mockMvc.perform(get("/api/v1/public/credits/tabs/" + PHONE + "/stk/" + intent.getId())
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(MpesaStkStatuses.FULFILLED))
                .andExpect(jsonPath("$.balanceOwed").value(350.00));
    }

    @Test
    void stk_acceptsAlternatePhone_andSyncsOnPayment() throws Exception {
        String alt = "0799000111";
        mockMvc.perform(post("/api/v1/public/credits/tabs/" + PHONE + "/stk")
                        .header("X-Tenant-Id", TENANT)
                        .header("Idempotency-Key", "tab-alt-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":50,\"phone\":\"" + alt + "\"}"))
                .andExpect(status().isCreated());

        MpesaStkIntent intent = mpesaStkIntentRepository.findAll().getFirst();
        assertThat(intent.getStkPhone()).isEqualTo(alt);

        customerPhoneOnPaymentService.syncPrimaryPhoneAfterPayment(
                TENANT, account.getCustomerId(), alt);

        var phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(account.getCustomerId());
        assertThat(phones.stream().anyMatch(p -> alt.equals(p.getPhone()) && p.isPrimary())).isTrue();
        assertThat(phones.stream().filter(p -> PHONE.equals(p.getPhone())).findFirst())
                .get()
                .extracting(zelisline.ub.credits.domain.CustomerPhone::isPrimary)
                .isEqualTo(false);
    }

    @Test
    void manualPayment_createsSubmittedClaimForAdminReview() throws Exception {
        mockMvc.perform(post("/api/v1/public/credits/tabs/" + PHONE + "/payment-claims")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":200,\"reference\":\"QGH1ABC234\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(CreditClaimStatuses.SUBMITTED))
                .andExpect(jsonPath("$.claimId").isNotEmpty());

        var claim = publicPaymentClaimRepository.findAll().getFirst();
        assertThat(claim.getSource()).isEqualTo(CreditClaimSources.TAB_PORTAL);
        assertThat(claim.getStatus()).isEqualTo(CreditClaimStatuses.SUBMITTED);
        assertThat(claim.getSubmittedAmount()).isEqualByComparingTo("200.00");
        assertThat(claim.getSubmittedReference()).isEqualTo("QGH1ABC234");
        assertThat(claim.getProposedChannel()).isEqualTo("mpesa");

        var refreshed = creditAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(refreshed.getBalanceOwed()).isEqualByComparingTo("500.00");
    }

    @Test
    void manualPayment_amountExceedingBalance_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/public/credits/tabs/" + PHONE + "/payment-claims")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":600}"))
                .andExpect(status().isBadRequest());
    }

    private void seedLedger(String code, String name, String type) {
        LedgerAccount row = new LedgerAccount();
        row.setBusinessId(TENANT);
        row.setCode(code);
        row.setName(name);
        row.setAccountType(type);
        ledgerAccountRepository.save(row);
    }
}
