package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeliveryAreaDto(
        @NotBlank @Size(max = 36) String id,
        @NotBlank @Size(max = 80) String name,
        boolean active
) {
}
