package zelisline.ub.trade.api.dto;

/** A shop this supplier is connected to, by the name it trades under. */
public record ConnectedShopResponse(String businessId, String name) {
}
