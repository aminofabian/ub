package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** What happened to each supplier's part of the basket. One failing does not stop the others. */
public record SubmitBasketResponse(List<Result> results) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(
            String supplierId,
            String supplierName,
            boolean placed,
            String orderId,
            String orderCode,
            BigDecimal total,
            String currency,
            /** The shop's address, to follow the order there. */
            String shopSlug,
            /** Why it was not placed, or which products were left out. */
            String message
    ) {
    }
}
