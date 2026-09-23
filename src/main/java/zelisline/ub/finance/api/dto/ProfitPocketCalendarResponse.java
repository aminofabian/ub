package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ProfitPocketCalendarResponse(
        String month,
        String branchId,
        LocalDate today,
        int currentStreak,
        int longestStreak,
        List<Day> days,
        Summary summary,
        List<Insight> insights,
        List<Summary> months
) {
    public record Day(
            LocalDate date,
            long saleCount,
            BigDecimal liveProfit,
            BigDecimal profitAmount,
            BigDecimal pocketedAmount,
            BigDecimal remainingProfit,
            BigDecimal pocketingPercentage,
            String status,
            String note,
            String skipReason,
            String source,
            boolean aboveProfit,
            boolean profitMoved,
            List<Entry> entries,
            List<Revision> revisions
    ) {
    }

    public record Entry(
            String id,
            BigDecimal amount,
            BigDecimal attributedAmount,
            LocalDate periodFrom,
            LocalDate periodTo,
            String destinationSummary,
            Instant createdAt,
            String note
    ) {
    }

    public record Revision(
            Instant at,
            BigDecimal pocketedAmount,
            String note
    ) {
    }

    public record Summary(
            String month,
            String label,
            BigDecimal totalProfit,
            BigDecimal totalPocketed,
            BigDecimal totalRetained,
            BigDecimal averageDailyPercentage,
            BigDecimal overallPercentage,
            int pocketingDays,
            int partialDays,
            int fullDays,
            int missedDays,
            int unreviewedDays,
            int skippedDays,
            int noProfitDays,
            int longestStreak,
            LocalDate highestPocketDate,
            BigDecimal highestPocketAmount,
            BigDecimal averagePocketedPerDay,
            BigDecimal profitNotPocketed
    ) {
    }

    public record Insight(
            String code,
            BigDecimal current,
            BigDecimal previous,
            BigDecimal delta
    ) {
    }
}
