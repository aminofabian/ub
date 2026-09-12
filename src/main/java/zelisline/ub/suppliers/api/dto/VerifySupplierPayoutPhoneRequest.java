package zelisline.ub.suppliers.api.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifySupplierPayoutPhoneRequest(
        @NotBlank String code
) {
}
