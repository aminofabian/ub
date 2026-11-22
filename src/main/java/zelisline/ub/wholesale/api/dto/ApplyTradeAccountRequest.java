package zelisline.ub.wholesale.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A buyer asks a supplier's shop for an account. */
public record ApplyTradeAccountRequest(
        @NotBlank @Size(max = 255) String buyerName,
        @NotBlank @Size(max = 64) String buyerPhone,
        @Size(max = 255) String businessName,
        @Size(max = 1000) String note
) {
}
