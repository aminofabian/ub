package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One product's price for one buyer. {@code itemName} is filled in on the way out only. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeAccountPriceDto(
        @NotBlank @Size(max = 36) String itemId,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
        String itemName
) {
}
