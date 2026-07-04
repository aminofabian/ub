package zelisline.ub.inventory.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class DailyStockAuditRequests {

    private DailyStockAuditRequests() {
    }

    public record PostDailyAuditSessionRequest(
            @NotBlank String branchId,
            @NotBlank String sessionType,
            LocalDate auditDate
    ) {}

    public record PatchDailyAuditLineRequest(
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal countedQty,
            @Size(max = 500) String note
    ) {}

    public record PatchDailyAuditProgressRequest(
            @NotNull Integer currentLineIndex
    ) {}

    public record DailyAuditReviewActionRequest(
            @Size(max = 2000) String notes
    ) {}
}
