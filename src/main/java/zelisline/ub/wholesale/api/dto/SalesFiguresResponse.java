package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A supplier's trade figures over a number of days (P4): what was asked for, what it agreed to
 * supply, what it billed and what is still owed, with its biggest buyers and products.
 */
public record SalesFiguresResponse(
        int days,
        String currency,
        int orders,
        int waitingForAnAnswer,
        BigDecimal askedValue,
        BigDecimal acceptedValue,
        /** Accepted as a share of what was asked for, 0 to 100. Null when nothing was answered. */
        BigDecimal fillRatePercent,
        BigDecimal invoiced,
        BigDecimal stillOwed,
        List<Row> topBuyers,
        List<Row> topProducts,
        /** Answered orders the median below is taken from. Null unless the supplier engagement flag is on. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer answeredSample,
        /** Median working hours from placed to answered, from five answered orders. Null otherwise. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal medianAnswerWorkingHours
) {
    public record Row(String name, BigDecimal value, BigDecimal quantity) {
    }
}
