package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EquityClearanceCorrectionResponse(
        boolean dryRun,
        int candidates,
        int corrected,
        int skipped,
        BigDecimal totalAmount,
        List<Line> lines
) {
    public record Line(
            String originalJournalEntryId,
            String correctionJournalEntryId,
            String branchId,
            LocalDate entryDate,
            String supplyBatchId,
            String batchNumber,
            String batchSourceType,
            BigDecimal amount
    ) {
    }
}
