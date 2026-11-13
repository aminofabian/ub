package zelisline.ub.serviceops.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Partial update: a {@code null} field is left unchanged. To clear the category, the
 * description or the commission override, set the matching {@code clear…} flag.
 *
 * <p>{@code version} is optional; when sent it must match the stored version or the update is
 * refused with 409, so two people editing the same service do not silently overwrite each
 * other.</p>
 */
public record PatchServiceRequest(
        @Size(max = 191) String name,
        @Size(max = 36) String categoryId,
        Boolean clearCategory,
        @Size(max = 1000) String description,
        Boolean clearDescription,
        @Min(1) @Max(1440) Integer durationMinutes,
        @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal price,
        Boolean priceIsVariable,
        @Size(max = 16) String commissionType,
        @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal commissionValue,
        Boolean clearCommission,
        Integer sortOrder,
        Boolean active,
        Long version
) {
}
