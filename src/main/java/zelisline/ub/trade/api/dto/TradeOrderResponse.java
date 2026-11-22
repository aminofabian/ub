package zelisline.ub.trade.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A trade order as either shop may see it. There is nothing here from the other side's books and
 * nothing about a person. {@code lines} and {@code events} are absent in a list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeOrderResponse(
        String id,
        String orderCode,
        String supplierName,
        String orderState,
        String deliveryState,
        String moneyState,
        String currency,
        BigDecimal total,
        Instant createdAt,
        List<Line> lines,
        List<Event> events,
        /** True once the buying shop has made its purchase order. The order's own id stays in that shop. */
        boolean hasPurchaseOrder,
        /** Money the buying shop is holding in escrow for this order, when it holds any. */
        Escrow escrow
) {
    public record Escrow(String status, BigDecimal amount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(
            String productName,
            String barcode,
            String packUnit,
            BigDecimal packSize,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            int lineNo,
            /** What the buying shop says arrived. Absent until it has said. */
            BigDecimal receivedQty
    ) {
    }

    public record Event(int eventNo, String kind, String actorSide, Instant createdAt) {
    }
}
