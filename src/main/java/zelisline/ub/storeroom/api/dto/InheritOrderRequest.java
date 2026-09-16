package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Apply a purchase order as store-room put-ins.
 *
 * @param confirmDuplicate required when this PO was inherited before
 * @param lines            ticked lines; quantity is catalog display units (same as the PO)
 */
public record InheritOrderRequest(
        @NotBlank @Size(max = 36) String purchaseOrderId,
        Boolean confirmDuplicate,
        @NotEmpty @Valid List<Line> lines,
        @Size(max = 36) String branchId
) {
    public record Line(
            @NotBlank @Size(max = 36) String purchaseOrderLineId,
            @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity
    ) {
    }
}
