package zelisline.ub.pharmacy.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DispensingCheckRequest(
        @NotNull @Size(max = 300) List<@NotBlank @Size(max = 36) String> itemIds
) {
}
