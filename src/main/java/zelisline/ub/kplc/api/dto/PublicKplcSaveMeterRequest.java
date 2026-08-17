package zelisline.ub.kplc.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicKplcSaveMeterRequest(
        @NotBlank(message = "meterNumber is required")
        @Size(max = 32)
        String meterNumber
) {
}
