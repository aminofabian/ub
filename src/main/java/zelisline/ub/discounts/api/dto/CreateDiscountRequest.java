package zelisline.ub.discounts.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateDiscountRequest(
        @NotBlank String name,
        String description,
        @NotBlank String method,
        @NotNull @DecimalMin("0.0001") BigDecimal value,
        @NotBlank String scope,
        String branchId,
        @NotNull Instant startAt,
        Instant endAt,
        List<String> itemIds,
        List<String> categoryIds,
        List<String> supplierIds,
        Boolean includeAnyLinkedSupplier,
        List<String> excludedItemIds,
        Boolean publish
) {
}
