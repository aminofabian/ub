package zelisline.ub.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.payroll.api.dto.PayrollRunRowResponse;

class PayrollCalendarSummarizerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 29);

    @Test
    void paidWhenEveryonePaid() {
        var month = PayrollCalendarSummarizer.summarize(
                2026,
                7,
                List.of(
                        row(true, "10000", "active"),
                        row(true, "12000", "active")
                ),
                new BigDecimal("22000.00"),
                TODAY
        );

        assertThat(month.status()).isEqualTo(PayrollCalendarSummarizer.STATUS_PAID);
        assertThat(month.paidCount()).isEqualTo(2);
        assertThat(month.pendingCount()).isZero();
    }

    @Test
    void pendingWhenSomeUnpaid() {
        var month = PayrollCalendarSummarizer.summarize(
                2026,
                7,
                List.of(row(true, "10000", "active"), row(false, "12000", "active")),
                new BigDecimal("10000.00"),
                TODAY
        );

        assertThat(month.status()).isEqualTo(PayrollCalendarSummarizer.STATUS_PENDING);
        assertThat(month.pendingCount()).isEqualTo(1);
    }

    @Test
    void missingSalaryTakesPriority() {
        var month = PayrollCalendarSummarizer.summarize(
                2026,
                7,
                List.of(row(false, "0", "active"), row(false, "12000", "active")),
                BigDecimal.ZERO,
                TODAY
        );

        assertThat(month.status()).isEqualTo(PayrollCalendarSummarizer.STATUS_MISSING_SALARY);
    }

    @Test
    void futureMonthWhenNotYetDue() {
        var month = PayrollCalendarSummarizer.summarize(
                2026,
                12,
                List.of(row(false, "10000", "active")),
                BigDecimal.ZERO,
                TODAY
        );

        assertThat(month.status()).isEqualTo(PayrollCalendarSummarizer.STATUS_FUTURE);
    }

    @Test
    void onLeaveExcludedFromPending() {
        var month = PayrollCalendarSummarizer.summarize(
                2026,
                7,
                List.of(row(true, "10000", "active"), row(false, "12000", "on_leave")),
                new BigDecimal("10000.00"),
                TODAY
        );

        assertThat(month.status()).isEqualTo(PayrollCalendarSummarizer.STATUS_PAID);
        assertThat(month.onLeaveCount()).isEqualTo(1);
        assertThat(month.pendingCount()).isZero();
    }

    private static PayrollRunRowResponse row(boolean paid, String base, String status) {
        BigDecimal amount = new BigDecimal(base);
        return new PayrollRunRowResponse(
                "user-1",
                "profile-1",
                "Jane",
                "Clerk",
                status,
                "Main",
                "branch-1",
                amount,
                BigDecimal.ZERO,
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                amount,
                paid,
                paid ? "payslip-1" : null,
                null
        );
    }
}
