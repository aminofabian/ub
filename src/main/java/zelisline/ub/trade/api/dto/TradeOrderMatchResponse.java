package zelisline.ub.trade.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One line of a supplier's order and the product in this shop it was matched to, if any. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeOrderMatchResponse(
        int lineNo,
        String productName,
        String barcode,
        /** This shop's product with the same barcode. Absent when there is none: the shop picks one. */
        String itemId,
        String itemName
) {
}
