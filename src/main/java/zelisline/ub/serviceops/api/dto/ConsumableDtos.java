package zelisline.ub.serviceops.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ConsumableDtos {

    private ConsumableDtos() {
    }

    /** A product used up each time the service is done. */
    public record ConsumableInput(
            @NotBlank @Size(max = 36) String itemId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity
    ) {
    }

    public record SetConsumablesRequest(@NotNull @Size(max = 20) @Valid List<ConsumableInput> items) {
    }

    public record ConsumableResponse(
            String itemId,
            String itemName,
            BigDecimal quantity,
            /** Cost of one unit at the most recent delivery; null when the product has never been received. */
            BigDecimal unitCost,
            BigDecimal cost
    ) {
    }

    public record ServiceConsumablesResponse(
            String serviceId,
            List<ConsumableResponse> items,
            /** What the products used cost for one visit, at the latest delivery costs. */
            BigDecimal costPerVisit
    ) {
    }
}
