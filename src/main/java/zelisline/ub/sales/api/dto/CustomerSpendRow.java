package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CustomerSpendRow(
        int rank,
        String customerId,
        Long customerNo,
        String name,
        String origin,
        String maskedHint,
        Boolean phoneVerified,
        long saleCount,
        int visitDays,
        BigDecimal spend,
        BigDecimal avgBasket,
        BigDecimal sharePct,
        LocalDate firstVisit,
        LocalDate lastVisit,
        Integer daysSinceLastVisit,
        int weekStreak,
        int longestWeekStreak,
        String cadence,
        String favoriteWeekday,
        String cohort
) {
}
