package zelisline.ub.credits.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import zelisline.ub.credits.application.OverdueDebtReminderService.ReminderSweepReport;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditReminderRecord;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.BusinessCreditSettings;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.BusinessCreditSettingsRepository;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditReminderRecordRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@Import(OverdueDebtReminderServiceIT.FixedClockConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.credits.reminders.enabled=false",
        "app.credits.reminders.interval-days=3",
        "app.credits.reminders.min-balance=1.00",
        "app.credits.reminders.max-count=5",
        "app.credits.reminders.zone=UTC"
})
class OverdueDebtReminderServiceIT {

    private static final String TENANT = "ddddffff-aaaa-bbbb-cccc-000000000001";
    private static final Instant NOW = Instant.parse("2026-05-04T08:00:00Z");

    @Autowired
    private OverdueDebtReminderService overdueDebtReminderService;
    @Autowired
    private CreditAccountRepository creditAccountRepository;
    @Autowired
    private CreditReminderRecordRepository creditReminderRecordRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private CustomerPhoneRepository customerPhoneRepository;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private BusinessCreditSettingsRepository businessCreditSettingsRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @MockitoBean
    private CustomerMessageDispatcher customerMessageDispatcher;

    @BeforeEach
    void seed() {
        creditReminderRecordRepository.deleteAll();
        customerPhoneRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        businessCreditSettingsRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Reminder Co");
        b.setSlug("reminder-co");
        b.setCurrency("KES");
        businessRepository.save(b);

        BusinessCreditSettings settings = new BusinessCreditSettings();
        settings.setBusinessId(TENANT);
        settings.setCreditSaleReminderEnabled(true);
        settings.setCreditSaleReminderPaymentUrl("https://palmart.co.ke/shop/account");
        settings.setSmsProvider("none");
        businessCreditSettingsRepository.save(settings);

        when(customerMessageDispatcher.deliver(any(), anyString(), anyString()))
                .thenReturn(new CustomerMessageDispatcher.DeliveryResult(
                        new zelisline.ub.messaging.infrastructure.RapidApiWhatsAppLookupClient.LookupResult(
                                true, false, "test"),
                        "sms_stub",
                        "sent",
                        "test"));
    }

    @Test
    void sweep_sendsToEligibleAccountsEveryThreeDays() {
        // Last reminder 4 days ago → eligible.
        seedAccount("alice", new BigDecimal("100.00"), NOW.minusSeconds(4 * 24 * 3600), 1, false, "0700000001");
        // Last reminder 2 days ago → not eligible yet.
        seedAccount("bob", new BigDecimal("50.00"), NOW.minusSeconds(2 * 24 * 3600), 1, false, "0700000002");
        // Never reminded → eligible.
        seedAccount("carol", new BigDecimal("75.00"), null, 0, false, "0700000003");

        ReminderSweepReport first = overdueDebtReminderService.sweep();
        assertThat(first.candidates()).isEqualTo(2);
        assertThat(first.sent()).isEqualTo(2);
        assertThat(first.alreadySent()).isZero();
    }

    @Test
    void sweep_isIdempotentForSameDay() {
        seedAccount("alice", new BigDecimal("100.00"), null, 0, false, "0700000001");

        ReminderSweepReport first = overdueDebtReminderService.sweep();
        assertThat(first.sent()).isEqualTo(1);

        ReminderSweepReport second = overdueDebtReminderService.sweep();
        assertThat(second.candidates()).isZero();
        assertThat(second.sent()).isZero();

        assertThat(creditReminderRecordRepository.count()).isEqualTo(1);
    }

    @Test
    void sweep_skipsOptedOutAccounts() {
        seedAccount("muted", new BigDecimal("999.00"), null, 0, true, "0700000001");
        ReminderSweepReport r = overdueDebtReminderService.sweep();
        assertThat(r.candidates()).isZero();
        assertThat(creditReminderRecordRepository.count()).isZero();
    }

    @Test
    void sweep_skipsAccountsBelowMinBalance() {
        seedAccount("dust", new BigDecimal("0.50"), null, 0, false, "0700000001");
        ReminderSweepReport r = overdueDebtReminderService.sweep();
        assertThat(r.candidates()).isZero();
        assertThat(creditReminderRecordRepository.count()).isZero();
    }

    @Test
    void sweep_skipsAccountsAtMaxCount() {
        seedAccount("capped", new BigDecimal("100.00"), NOW.minusSeconds(10 * 24 * 3600), 5, false, "0700000001");
        ReminderSweepReport r = overdueDebtReminderService.sweep();
        assertThat(r.candidates()).isZero();
        assertThat(creditReminderRecordRepository.count()).isZero();
    }

    @Test
    void sweep_incrementsReminderCountAndUpdatesTimestamp() {
        CreditAccount account = seedAccount("alice", new BigDecimal("100.00"), null, 0, false, "0700000001");
        overdueDebtReminderService.sweep();

        CreditAccount updated = creditAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(updated.getBalanceReminderCount()).isEqualTo(1);
        assertThat(updated.getLastBalanceReminderAt()).isEqualTo(NOW);
    }

    private CreditAccount seedAccount(
            String name,
            BigDecimal balanceOwed,
            Instant lastReminderAt,
            int reminderCount,
            boolean optedOut,
            String phone
    ) {
        Customer c = new Customer();
        c.setBusinessId(TENANT);
        c.setName(name);
        customerRepository.save(c);

        CustomerPhone cp = new CustomerPhone();
        cp.setBusinessId(TENANT);
        cp.setCustomerId(c.getId());
        cp.setPhone(phone);
        cp.setPrimary(true);
        customerPhoneRepository.save(cp);

        CreditAccount a = new CreditAccount();
        a.setBusinessId(TENANT);
        a.setCustomerId(c.getId());
        a.setBalanceOwed(balanceOwed);
        a.setLastBalanceReminderAt(lastReminderAt);
        a.setBalanceReminderCount(reminderCount);
        a.setRemindersOptOut(optedOut);
        creditAccountRepository.save(a);
        return a;
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
