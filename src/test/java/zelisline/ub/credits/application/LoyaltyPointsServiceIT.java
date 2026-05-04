package zelisline.ub.credits.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.credits.domain.BusinessCreditSettings;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.BusinessCreditSettingsRepository;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.LoyaltyTransactionRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class LoyaltyPointsServiceIT {

    private static final String TENANT = "11118888-aaaa-bbbb-cccc-000000000001";

    @Autowired
    private LoyaltyPointsService loyaltyPointsService;
    @Autowired
    private BusinessCreditSettingsService businessCreditSettingsService;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private CreditAccountRepository creditAccountRepository;
    @Autowired
    private BusinessCreditSettingsRepository businessCreditSettingsRepository;
    @Autowired
    private LoyaltyTransactionRepository loyaltyTransactionRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private JournalLineRepository journalLineRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private LedgerBootstrapService ledgerBootstrapService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private Customer customer;
    private CreditAccount account;

    @BeforeEach
    void seed() {
        loyaltyTransactionRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        businessCreditSettingsRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Loyal Co");
        b.setSlug("loyal-co");
        businessRepository.save(b);

        ledgerBootstrapService.ensureStandardAccounts(TENANT);

        customer = new Customer();
        customer.setBusinessId(TENANT);
        customer.setName("Lily");
        customerRepository.save(customer);

        account = new CreditAccount();
        account.setBusinessId(TENANT);
        account.setCustomerId(customer.getId());
        account.setLoyaltyPoints(0);
        creditAccountRepository.save(account);

        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(TENANT);
        s.setLoyaltyPointsPerKes(new BigDecimal("1"));
        s.setLoyaltyKesPerPoint(new BigDecimal("0.10"));
        s.setLoyaltyMaxRedeemBps(5000);
        businessCreditSettingsRepository.save(s);
    }

    @Test
    void earn_postsAccrualJournalAndIncrementsPoints() {
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(TENANT);
        loyaltyPointsService.applyAfterCompletedSale(
                TENANT, customer.getId(), saleId(),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                s
        );

        var refreshed = creditAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(refreshed.getLoyaltyPoints()).isEqualTo(100);

        // GL: Dr 5310 / Cr 2196 for 100 * 0.10 = 10.00.
        assertGlSum(LedgerAccountCodes.LOYALTY_MARKETING_EXPENSE, true, "10.00");
        assertGlSum(LedgerAccountCodes.LOYALTY_REDEMPTION_LIABILITY, false, "10.00");

        long accruals = journalEntryRepository.findAll().stream()
                .filter(j -> SalesConstants.JOURNAL_SOURCE_LOYALTY_EARN_ACCRUAL.equals(j.getSourceType()))
                .count();
        assertThat(accruals).isEqualTo(1);
    }

    @Test
    void redeem_nonDivisibleAmount_isRejected() {
        seedPoints(100);
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(TENANT);

        // 0.05 is not a multiple of kes-per-point (0.10).
        assertThatThrownBy(() -> loyaltyPointsService.applyAfterCompletedSale(
                TENANT, customer.getId(), saleId(),
                new BigDecimal("0.05"),
                new BigDecimal("0.05"),
                s))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("multiple of");

        assertThat(creditAccountRepository.findById(account.getId()).orElseThrow().getLoyaltyPoints())
                .isEqualTo(100);
    }

    @Test
    void redeem_aboveCap_isRejected() {
        seedPoints(1_000);
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(TENANT);

        // basket 100, cap 50% = 50; redeem 60 > cap.
        assertThatThrownBy(() -> loyaltyPointsService.applyAfterCompletedSale(
                TENANT, customer.getId(), saleId(),
                new BigDecimal("100.00"),
                new BigDecimal("60.00"),
                s))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void redeem_underCap_subtractsExactPoints() {
        seedPoints(500);
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(TENANT);

        // Redeem 30 KES at 0.10 KES/pt = 300 pts. Earn 70 pts on the residual basket.
        loyaltyPointsService.applyAfterCompletedSale(
                TENANT, customer.getId(), saleId(),
                new BigDecimal("70.00"),
                new BigDecimal("30.00"),
                s);

        var refreshed = creditAccountRepository.findById(account.getId()).orElseThrow();
        // 500 - 300 + 70.
        assertThat(refreshed.getLoyaltyPoints()).isEqualTo(270);
    }

    @Test
    void redeem_insufficientPoints_isRejected() {
        seedPoints(50);
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(TENANT);

        assertThatThrownBy(() -> loyaltyPointsService.applyAfterCompletedSale(
                TENANT, customer.getId(), saleId(),
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                s))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Insufficient");
    }

    @Test
    void void_reversesEarnAccrual() {
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(TENANT);
        String saleId = saleId();
        loyaltyPointsService.applyAfterCompletedSale(
                TENANT, customer.getId(), saleId,
                new BigDecimal("50.00"), BigDecimal.ZERO, s);

        loyaltyPointsService.reverseLoyaltyForVoidedSale(TENANT, saleId, customer.getId(), s);

        var refreshed = creditAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(refreshed.getLoyaltyPoints()).isZero();

        // Net Dr / Cr on the liability and expense should be 0 across the two entries.
        assertGlNet(LedgerAccountCodes.LOYALTY_MARKETING_EXPENSE, "0.00");
        assertGlNet(LedgerAccountCodes.LOYALTY_REDEMPTION_LIABILITY, "0.00");
    }

    @Test
    void refund_prorate_reversesPartOfEarn() {
        BusinessCreditSettings s = businessCreditSettingsService.resolveForBusiness(TENANT);
        String saleId = saleId();
        loyaltyPointsService.applyAfterCompletedSale(
                TENANT, customer.getId(), saleId,
                new BigDecimal("100.00"), BigDecimal.ZERO, s);

        // Refund 50% of the basket.
        loyaltyPointsService.proportionallyAdjustAfterRefund(
                TENANT, saleId, customer.getId(),
                new BigDecimal("100.00"), new BigDecimal("50.00"), s);

        var refreshed = creditAccountRepository.findById(account.getId()).orElseThrow();
        // Earned 100, clawed back 50.
        assertThat(refreshed.getLoyaltyPoints()).isEqualTo(50);

        // Net is the residual 50 points * 0.10 = 5.00 still on liability.
        assertGlNet(LedgerAccountCodes.LOYALTY_MARKETING_EXPENSE, "5.00"); // debit balance
        assertGlNet(LedgerAccountCodes.LOYALTY_REDEMPTION_LIABILITY, "-5.00"); // credit balance
    }

    private void seedPoints(int points) {
        var a = creditAccountRepository.findById(account.getId()).orElseThrow();
        a.setLoyaltyPoints(points);
        creditAccountRepository.save(a);
    }

    private String saleId() {
        return UUID.randomUUID().toString();
    }

    private void assertGlSum(String code, boolean debit, String expected) {
        LedgerAccount acc = ledgerAccountRepository.findByBusinessIdAndCode(TENANT, code).orElseThrow();
        BigDecimal sum = BigDecimal.ZERO;
        for (JournalLine l : journalLineRepository.findAll()) {
            if (acc.getId().equals(l.getLedgerAccountId())) {
                sum = sum.add(debit ? l.getDebit() : l.getCredit());
            }
        }
        assertThat(sum).isEqualByComparingTo(expected);
    }

    /** Net = debit - credit on the account across all journal lines. */
    private void assertGlNet(String code, String expected) {
        LedgerAccount acc = ledgerAccountRepository.findByBusinessIdAndCode(TENANT, code).orElseThrow();
        BigDecimal net = BigDecimal.ZERO;
        for (JournalLine l : journalLineRepository.findAll()) {
            if (acc.getId().equals(l.getLedgerAccountId())) {
                net = net.add(l.getDebit()).subtract(l.getCredit());
            }
        }
        assertThat(net).isEqualByComparingTo(expected);
    }
}
