package zelisline.ub.storeroom.api.dto;

import java.util.List;

/** What the store room wrote when inheriting a purchase order. */
public record InheritOrderApplyResponse(
        String purchaseOrderId,
        String poNumber,
        int createdStoreItems,
        int movements,
        List<StoreRoomMovementResponse> recorded
) {
}
