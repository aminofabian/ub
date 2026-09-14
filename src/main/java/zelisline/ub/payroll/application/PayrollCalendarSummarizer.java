package zelisline.ub.payroll.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import zelisline.ub.payroll.api.dto.PayrollCalendarMonthResponse;
import zelisline.ub.payroll.api.dto.PayrollRunRowResponse;
import zelisline.ub.payroll.domain.JoinPayMode;

/**
 * Derives calendar month status from a payroll run preview.
 */
public final class PayrollCalendarSummarizer {

    public static final String STATUS_FUTURE = "future";
    public static final String STATUS_EMPTY = "empty";
    public static final String STATUS_MISSING_SALARY = "missing_salary";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PAID = "paid";

    private PayrollCalendarSummarizer() {
    }

    public static PayrollCalendarMonthResponse summarize(
            int year,
            int month,
            List<PayrollRunRowResponse> rows,
            BigDecimal totalNetPaid,
            LocalDate today
    ) {
        int headcount = rows.size();
        int paidCount = (int) rows.stream().filter(PayrollRunRowResponse::alreadyPaid).count();
        boolean released = PayrollPeriod.isReleased(year, month, today);
        // Rows locked before the 25th are zero by design, and deferred join
        // months stay zero deliberately — neither is a "missing salary".
        int missingSalaryCount = (int) rows.stream()
                .filter(r -> r.baseSalary().signum() <= 0)
                .filter(r -> released && !isDeferredJoinMonth(r, year, month))
                .count();
        int onLeaveCount = (int) rows.stream()
                .filter(r -> "on_leave".equalsIgnoreCase(r.employmentStatus()))
                .count();
        int pendingCount = (int) rows.stream()
                .filter(r ->
                        !r.alreadyPaid()
                                && !"on_leave".equalsIgnoreCase(r.employmentStatus())
                                && !isDeferredJoinMonth(r, year, month)
                                && (r.baseSalary().signum() > 0 || !r.salaryReleased())
                )
                .count();

        String status = deriveStatus(year, month, headcount, missingSalaryCount, pendingCount, today);

        return new PayrollCalendarMonthResponse(
                month,
                status,
                headcount,
                paidCount,
                pendingCount,
                missingSalaryCount,
                onLeaveCount,
                totalNetPaid
        );
    }

    private static boolean isDeferredJoinMonth(PayrollRunRowResponse row, int year, int month) {
        if (row.startDate() == null || !JoinPayMode.DEFERRED.equals(row.joinPayMode())) {
            return false;
        }
        LocalDate start = row.startDate();
        return start.getYear() == year && start.getMonthValue() == month;
    }

    static String deriveStatus(
            int year,
            int month,
            int headcount,
            int missingSalaryCount,
            int pendingCount,
            LocalDate today
    ) {
        if (headcount == 0) {
            return STATUS_EMPTY;
        }
        if (missingSalaryCount > 0) {
            return STATUS_MISSING_SALARY;
        }
        if (isFutureMonth(year, month, today)) {
            return STATUS_FUTURE;
        }
        if (pendingCount > 0) {
            return STATUS_PENDING;
        }
        return STATUS_PAID;
    }

    private static boolean isFutureMonth(int year, int month, LocalDate today) {
        if (year > today.getYear()) {
            return true;
        }
        return year == today.getYear() && month > today.getMonthValue();
    }
}
