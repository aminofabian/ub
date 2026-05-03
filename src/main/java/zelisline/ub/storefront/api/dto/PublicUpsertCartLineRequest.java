package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicUpsertCartLineRequest(
        @NotBlank String itemId,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal quantity
) {
}
