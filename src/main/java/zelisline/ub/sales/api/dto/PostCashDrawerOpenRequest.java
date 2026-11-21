package zelisline.ub.sales.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import zelisline.ub.sales.domain.CashDrawerOpenReason;
import zelisline.ub.sales.domain.CashDrawerOpenSource;

/** One drawer opening, as the till saw it. {@code openedAt} is the till's clock. */
public record PostCashDrawerOpenRequest(
        @NotBlank String clientEventId,
        @NotNull Instant openedAt,
        @NotNull CashDrawerOpenSource source,
        @NotNull CashDrawerOpenReason reason,
        @NotBlank String branchId,
        String shiftId,
        String saleId,
        Boolean sensorConfirmed
) {
}
