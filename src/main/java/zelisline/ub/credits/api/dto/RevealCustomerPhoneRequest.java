package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RevealCustomerPhoneRequest(
        @NotBlank @Size(min = 3, max = 8) String missingDigits
) {
}
