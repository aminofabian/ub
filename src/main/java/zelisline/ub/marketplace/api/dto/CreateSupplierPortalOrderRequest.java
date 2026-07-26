package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateSupplierPortalOrderRequest(
        @NotBlank String localSupplierId,
        LocalDate expectedDate,
        String notes,
        @NotEmpty @Valid List<Line> lines
) {
    public record Line(
            @NotBlank String itemId,
            @NotNull @Positive BigDecimal qtyOrdered,
            @Positive BigDecimal unitEstimatedCost
    ) {
    }
}
