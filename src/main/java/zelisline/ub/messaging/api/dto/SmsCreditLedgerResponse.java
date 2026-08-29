package zelisline.ub.messaging.api.dto;

import java.time.Instant;

import zelisline.ub.messaging.domain.SmsCreditLedgerKind;

public record SmsCreditLedgerResponse(
        java.util.List<SmsCreditLedgerRow> rows
) {
    public record SmsCreditLedgerRow(
            String id,
            int delta,
            int balanceAfter,
            SmsCreditLedgerKind kind,
            String reason,
            String referenceId,
            Instant createdAt,
            String createdByUserId
    ) {
    }
}
