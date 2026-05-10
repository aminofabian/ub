package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateDrawoutRequest(
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount,
        @NotBlank String category,
        @NotBlank @Size(max = 300) String description,
        @NotBlank @Size(max = 255) String recipientName,
        @Size(max = 100) String recipientContact,
        @Size(max = 255) String reference,
        String recurringItemId
) {
}
