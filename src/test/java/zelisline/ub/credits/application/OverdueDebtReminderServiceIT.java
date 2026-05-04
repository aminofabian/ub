package zelisline.ub.credits.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditReminderRecordRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@Import(OverdueDebtReminderServiceIT.FixedClockConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.credits.reminders.enabled=false",
        "app.credits.reminders.overdue-days=30",
        "app.credits.reminders.min-balance=1.00",
        "app.credits.reminders.zone=UTC"
})
class OverdueDebtReminderServiceIT {

    private static final String TENANT = "ddddffff-aaaa-bbbb-cccc-000000000001";

    @Autowired
    private OverdueDebtReminderService overdueDebtReminderService;
    @Autowired
    private CreditAccountRepository creditAccountRepository;
    @Autowired
    private CreditReminderRecordRepository creditReminderRecordRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private BusinessRepository businessRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void seed() {
        creditReminderRecordRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Reminder Co");
        b.setSlug("reminder-co");
        businessRepository.save(b);
    }

    @Test
    void sweep_writesOneRecordPerOverdueAccount_andIsIdempotentForSameWeek() {
        seedAccount("alice", new BigDecimal("100.00"), Instant.parse("2026-01-01T00:00:00Z"), false);
        seedAccount("bob", new BigDecimal("50.00"), Instant.parse("2026-01-15T00:00:00Z"), false);
        // current account; should be skipped (last activity is the day before "now").
        seedAccount("recent", new BigDecimal("20.00"), Instant.parse("2026-05-03T00:00:00Z"), false);
        // opted-out; should be skipped.
        seedAccount("muted", new BigDecimal("999.00"), Instant.parse("2026-01-01T00:00:00Z"), true);

        ReminderSweepReport first = overdueDebtReminderService.sweep();
        assertThat(first.candidates()).isEqualTo(2);
        assertThat(first.sent()).isEqualTo(2);
        assertThat(first.alreadySent()).isZero();

        ReminderSweepReport second = overdueDebtReminderService.sweep();
        assertThat(second.candidates()).isEqualTo(2);
        assertThat(second.sent()).isZero();
        assertThat(second.alreadySent()).isEqualTo(2);

        assertThat(creditReminderRecordRepository.count()).isEqualTo(2);
    }

    @Test
    void sweep_skipsAccountsBelowMinBalance() {
        seedAccount("dust", new BigDecimal("0.50"), Instant.parse("2026-01-01T00:00:00Z"), false);
        ReminderSweepReport r = overdueDebtReminderService.sweep();
        assertThat(r.candidates()).isZero();
        assertThat(creditReminderRecordRepository.count()).isZero();
    }

    private void seedAccount(String name, BigDecimal balanceOwed, Instant lastActivity, boolean optedOut) {
        Customer c = new Customer();
        c.setBusinessId(TENANT);
        c.setName(name);
        customerRepository.save(c);

        CreditAccount a = new CreditAccount();
        a.setBusinessId(TENANT);
        a.setCustomerId(c.getId());
        a.setBalanceOwed(balanceOwed);
        a.setLastActivityAt(lastActivity);
        a.setRemindersOptOut(optedOut);
        creditAccountRepository.save(a);
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(Instant.parse("2026-05-04T08:00:00Z"), ZoneOffset.UTC);
        }
    }
}
