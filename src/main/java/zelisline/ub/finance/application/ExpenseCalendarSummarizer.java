package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.time.LocalDate;

import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.api.dto.ExpenseCalendarMonthResponse;

public final class ExpenseCalendarSummarizer {

    public static final String STATUS_FUTURE = "future";
    public static final String STATUS_EMPTY = "empty";
    public static final String STATUS_POSTED = "posted";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_FAILED = "failed";

    private ExpenseCalendarSummarizer() {
    }

    public static ExpenseCalendarMonthResponse summarize(
            int year,
            int month,
            int dueCount,
            int postedCount,
            int failedCount,
            int skippedCount,
            BigDecimal commitment,
            BigDecimal postedTotal,
            LocalDate today
    ) {
        String status = deriveStatus(
                year,
                month,
                dueCount,
                postedCount,
                failedCount,
                skippedCount,
                today
        );
        return new ExpenseCalendarMonthResponse(
                month,
                status,
                dueCount,
                postedCount,
                failedCount,
                skippedCount,
                commitment,
                postedTotal
        );
    }

    static String deriveStatus(
            int year,
            int month,
            int dueCount,
            int postedCount,
            int failedCount,
            int skippedCount,
            LocalDate today
    ) {
        if (dueCount == 0) {
            return STATUS_EMPTY;
        }
        if (isFutureMonth(year, month, today)) {
            return STATUS_FUTURE;
        }
        if (failedCount > 0) {
            return STATUS_FAILED;
        }
        int resolved = postedCount + skippedCount;
        if (resolved >= dueCount) {
            return STATUS_POSTED;
        }
        return STATUS_PENDING;
    }

    private static boolean isFutureMonth(int year, int month, LocalDate today) {
        if (year > today.getYear()) {
            return true;
        }
        return year == today.getYear() && month > today.getMonthValue();
    }
}
