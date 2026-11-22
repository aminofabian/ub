package zelisline.ub.marketplace.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** A buyer asks one supplier for the same goods every so many days. */
public record StartStandingOrderRequest(
        @NotBlank @Size(max = 36) String buyerBusinessId,
        @NotBlank @Size(max = 255) String buyerName,
        @NotBlank @Size(max = 36) String supplierId,
        @Min(1) @Max(90) int everyDays,
        @NotEmpty @Size(max = 50) @Valid List<SubmitBasketRequest.Line> lines
) {
}
