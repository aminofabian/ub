package zelisline.ub.inventory.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class StockTakeRestockRequests {

    private StockTakeRestockRequests() {
    }

    public record PostDailyAuditRestockRequest(
            @NotBlank String lineId,
            @NotBlank String supplierId,
            @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal suggestedQty,
            @Size(max = 2000) String note
    ) {}

    public record PatchStockTakeRestockRequest(
            String supplierId,
            @DecimalMin(value = "0.0001", inclusive = true) BigDecimal suggestedQty,
            @DecimalMin(value = "0", inclusive = true) BigDecimal buyingPrice,
            @Size(max = 2000) String notes
    ) {}

    public record RejectStockTakeRestockRequest(
            @NotBlank @Size(max = 2000) String reason
    ) {}

    public record GenerateRestockOrderRequest(
            List<String> supplierIds,
            List<String> itemIds,
            @Size(max = 2000) String adminNotes,
            Boolean createPathAPurchaseOrders,
            Boolean sendPurchaseOrders
    ) {}

    public record ConvertRestockOrderRequest(
            @Size(max = 2000) String adminNotes,
            Boolean sendPurchaseOrder
    ) {}
}
