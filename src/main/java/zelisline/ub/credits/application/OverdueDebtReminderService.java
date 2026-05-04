package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditReminderRecord;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditReminderRecordRepository;

/**
 * Phase 5 Slice 6 — overdue tab reminder pipeline. The actual SMS/email send is intentionally a
 * stub (Phase 7 ships the real provider adapter) but everything else — query, idempotency,
 * audit, opt-out — is wired so the only swap point in Phase 7 is {@link #dispatch(CreditAccount)}.
 *
 * <p>Idempotency: each (account, ISO-week) pair gets at most one row in {@code credit_reminders}
 * thanks to the unique constraint, so re-runs of {@link #sweep()} within the same week are
 * silent no-ops.
 */
@Service
@RequiredArgsConstructor
public class OverdueDebtReminderService {

    private static final Logger log = LoggerFactory.getLogger(OverdueDebtReminderService.class);

    private final CreditAccountRepository creditAccountRepository;
    private final CreditReminderRecordRepository creditReminderRecordRepository;
    private final Clock clock;

    @Value("${app.credits.reminders.overdue-days:30}")
    private int overdueDays;

    @Value("${app.credits.reminders.min-balance:1.00}")
    private BigDecimal minBalance;

    @Value("${app.credits.reminders.zone:UTC}")
    private String zoneId;

    @Transactional
    public ReminderSweepReport sweep() {
        var staleBefore = clock.instant().minus(Duration.ofDays(Math.max(1, overdueDays)));
        var overdue = creditAccountRepository.findOverdueForReminder(minBalance, staleBefore);
        String week = currentWeekBucket();
        int sent = 0;
        int skipped = 0;
        for (CreditAccount acc : overdue) {
            if (creditReminderRecordRepository.existsByCreditAccountIdAndWeekBucket(acc.getId(), week)) {
                skipped++;
                continue;
            }
            DispatchOutcome outcome = dispatch(acc);
            CreditReminderRecord row = new CreditReminderRecord();
            row.setBusinessId(acc.getBusinessId());
            row.setCreditAccountId(acc.getId());
            row.setWeekBucket(week);
            row.setChannel(outcome.channel());
            row.setOutcome(outcome.status());
            row.setDetail(outcome.detail());
            creditReminderRecordRepository.save(row);
            sent++;
        }
        log.info("credits.reminder.sweep accounts={} sent={} skipped={} week={}",
                overdue.size(), sent, skipped, week);
        return new ReminderSweepReport(overdue.size(), sent, skipped, week);
    }

    /**
     * Hook for Phase 7 to plug a real SMS/email provider. The stub returns "stub" so audit rows
     * are still produced and tests can lock in idempotency without a live integration.
     */
    DispatchOutcome dispatch(CreditAccount account) {
        return new DispatchOutcome("stub", "logged", "Phase 7 will wire SMS/email provider");
    }

    private String currentWeekBucket() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(zoneId)));
        WeekFields wf = WeekFields.of(Locale.ROOT);
        int week = today.get(wf.weekOfWeekBasedYear());
        int weekYear = today.get(wf.weekBasedYear());
        return String.format(Locale.ROOT, "%04d-W%02d", weekYear, week);
    }

    public record DispatchOutcome(String channel, String status, String detail) {
    }

    public record ReminderSweepReport(int candidates, int sent, int alreadySent, String weekBucket) {
    }
}
