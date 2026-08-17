package zelisline.ub.kplc.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import zelisline.ub.kplc.api.dto.PublicKplcMonthSpendResponse;
import zelisline.ub.kplc.api.dto.PublicKplcSpendStatsResponse;
import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;

final class KplcSpendStats {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private KplcSpendStats() {
    }

    static PublicKplcSpendStatsResponse from(List<PublicKplcTokenResponse> tokens) {
        return from(tokens, Instant.now());
    }

    static PublicKplcSpendStatsResponse from(List<PublicKplcTokenResponse> tokens, Instant now) {
        YearMonth thisMonth = YearMonth.from(now.atZone(NAIROBI));
        Map<YearMonth, MonthAcc> months = new LinkedHashMap<>();
        BigDecimal allAmount = BigDecimal.ZERO;
        int allCount = 0;
        for (PublicKplcTokenResponse token : tokens) {
            if (token == null || token.purchasedAt() == null) {
                continue;
            }
            YearMonth ym = YearMonth.from(token.purchasedAt().atZone(NAIROBI));
            MonthAcc acc = months.computeIfAbsent(ym, key -> new MonthAcc());
            acc.add(token.amount(), token.units());
            allAmount = allAmount.add(nz(token.amount()));
            allCount++;
        }
        List<PublicKplcMonthSpendResponse> rows = new ArrayList<>();
        months.entrySet().stream()
                .sorted(Map.Entry.<YearMonth, MonthAcc>comparingByKey(Comparator.reverseOrder()))
                .forEach(entry -> rows.add(entry.getValue().toRow(entry.getKey())));
        MonthAcc current = months.getOrDefault(thisMonth, new MonthAcc());
        return new PublicKplcSpendStatsResponse(
                current.amount,
                current.units,
                current.count,
                allAmount,
                allCount,
                List.copyOf(rows));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class MonthAcc {
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal units = BigDecimal.ZERO;
        private int count;

        void add(BigDecimal nextAmount, BigDecimal nextUnits) {
            amount = amount.add(nz(nextAmount));
            units = units.add(nz(nextUnits));
            count++;
        }

        PublicKplcMonthSpendResponse toRow(YearMonth ym) {
            return new PublicKplcMonthSpendResponse(
                    ym.toString(),
                    ym.atDay(1).format(MONTH_LABEL),
                    amount,
                    units,
                    count);
        }
    }
}
