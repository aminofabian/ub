package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * Wholesale terms on one product: the least a shopper may order online, and lower prices from set
 * quantities. Sent whole; what is sent replaces what was there. A null minimum means none.
 */
public record ItemTradeTermsDto(
        @DecimalMin(value = "0", inclusive = false) BigDecimal minOrderQty,
        @Valid @Size(max = 6) List<ItemPriceBreakDto> priceBreaks
) {
}
