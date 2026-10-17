package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PostTaxRateRequest(
        @NotBlank String name,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal ratePercent,
        boolean inclusive,
        boolean active,
        /** KRA eTIMS tax code (A = exempt, B = 16%, C = 0%, D = non-VAT, E = 8%); optional. */
        @Size(max = 8) String etimsTaxCode
) {
}
