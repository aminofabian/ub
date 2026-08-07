package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public final class OrderPadDtos {

    private OrderPadDtos() {
    }

    public record OrderPadItemResponse(
            String id,
            String businessId,
            String branchId,
            String itemId,
            String itemName,
            BigDecimal quantity,
            String note,
            boolean ordered,
            String orderedById,
            String orderedByName,
            Instant orderedAt,
            String createdById,
            String createdByName,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreateOrderPadItemRequest(
            @NotBlank String branchId,
            String itemId,
            @Size(max = 500) String itemName,
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @Size(max = 2000) String note
    ) {}

    public record CreateOrderPadBatchRequest(
            @NotBlank String branchId,
            @NotEmpty @Valid List<OrderPadLineInput> lines
    ) {}

    public record OrderPadLineInput(
            String itemId,
            @Size(max = 500) String itemName,
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @Size(max = 2000) String note
    ) {}

    public record SetOrderedRequest(boolean ordered) {}
}
