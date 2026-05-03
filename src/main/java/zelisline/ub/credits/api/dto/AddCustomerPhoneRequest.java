package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddCustomerPhoneRequest(
        @NotBlank @Size(max = 50) String phone,
        Boolean primary
) {
}
