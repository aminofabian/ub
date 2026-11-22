package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a buyer sees of the order they placed, opened by its id. No phone number or email comes
 * back: the id is all a caller has shown, so nothing personal is given for it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SalesOrderTrackingResponse(
        String orderId,
        String orderCode,
        String status,
        String currency,
        BigDecimal total,
        Instant createdAt,
        List<Line> lines
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(
            String itemName,
            BigDecimal quantity,
            BigDecimal acceptedQty,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String status
    ) {
    }
}
