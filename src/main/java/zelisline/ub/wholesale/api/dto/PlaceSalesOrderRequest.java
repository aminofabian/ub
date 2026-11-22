package zelisline.ub.wholesale.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Who is ordering. The lines come from the cart. */
public record PlaceSalesOrderRequest(
        @NotBlank @Size(max = 255) String buyerName,
        @NotBlank @Size(max = 64) String buyerPhone,
        @Size(max = 255) String buyerEmail,
        @Size(max = 255) String buyerBusinessName,
        @Size(max = 2000) String notes
) {
}
