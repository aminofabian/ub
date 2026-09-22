package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ProfitPocketResponse(
        String profitPocketId,
        String journalEntryId,
        BigDecimal amount,
        LocalDate periodFrom,
        LocalDate periodTo,
        String destinationSummary,
        Instant createdAt,
        /** pending | success | failed | skipped | null */
        String sendMoneyStatus,
        String kopokopoSendMoneyId,
        String sendMoneyMessage
) {
}
