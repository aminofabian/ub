package zelisline.ub.storeroom.api.dto;

import jakarta.validation.constraints.Size;

/** Approve or reject a pending store-room movement. */
public record DecideStoreRoomMovementRequest(
        @Size(max = 255) String note
) {
}
