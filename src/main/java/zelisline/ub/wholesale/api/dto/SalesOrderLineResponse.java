package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SalesOrderLineResponse(
        String id,
        String itemId,
        String itemName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String status,
        BigDecimal acceptedQty
) {
}
