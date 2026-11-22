package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record IssueCreditNoteRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(max = 500) String reason
) {
}
