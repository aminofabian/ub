package zelisline.ub.restaurant.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import zelisline.ub.restaurant.api.dto.OrderDtos.OrderCard;

/** Lodging rooms and the front desk (ADR 0028). A room is a service_resource of type ROOM. */
public final class RoomDtos {

    private RoomDtos() {
    }

    /** Create a room, or patch one: on an update every field is optional and only what is sent changes. */
    public record RoomRequest(
            @Size(max = 36) String branchId,
            @Size(min = 1, max = 80) String name,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000")
            @Digits(integer = 8, fraction = 2) BigDecimal nightlyRate,
            @Size(max = 60) String area,
            Boolean active
    ) {
    }

    public record RoomResponse(
            String id,
            String branchId,
            String name,
            BigDecimal nightlyRate,
            String area,
            boolean active,
            int sortOrder
    ) {
    }

    public record RoomListResponse(String branchId, List<RoomResponse> rooms) {
    }

    /** A room and the stays on it: the one in-house now, plus any booked for later. */
    public record RoomWithStays(RoomResponse room, List<OrderCard> stays) {
    }

    /**
     * The front desk of a branch: every room with its stays, plus any stay whose room is gone.
     * A stay is an open order on channel {@code LODGING}; its card is the same shape as a floor card.
     */
    public record FrontDeskResponse(
            String branchId, Instant asOf, List<RoomWithStays> rooms, List<OrderCard> otherStays) {
    }

    /** A create needs a branch, a name and a rate; an update does not. */
    public record CreateRoomRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000")
            @Digits(integer = 8, fraction = 2) BigDecimal nightlyRate,
            @Size(max = 60) String area
    ) {
    }
}
