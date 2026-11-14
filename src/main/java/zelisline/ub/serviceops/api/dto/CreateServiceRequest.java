package zelisline.ub.serviceops.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateServiceRequest(
        @NotBlank @Size(max = 191) String name,
        @Size(max = 36) String categoryId,
        @Size(max = 1000) String description,
        @NotNull @Min(1) @Max(1440) Integer durationMinutes,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal price,
        Boolean priceIsVariable,
        @Size(max = 16) String commissionType,
        @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal commissionValue,
        Integer sortOrder,
        @Size(max = 200) List<@NotBlank @Size(max = 36) String> staffProfileIds,
        /** What the product for one service costs the shop, in whole shillings; null or 0 for none. */
        @DecimalMin("0") @Digits(integer = 9, fraction = 0) BigDecimal productCost
) {
}
