package zelisline.ub.trade.api.dto;

/** The purchase order this shop made from a supplier's order. */
public record TradeOrderPurchaseOrderResponse(String purchaseOrderId, String poNumber) {
}
