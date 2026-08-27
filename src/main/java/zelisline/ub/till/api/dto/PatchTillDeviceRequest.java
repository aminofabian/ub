package zelisline.ub.till.api.dto;

import jakarta.validation.constraints.Size;

public record PatchTillDeviceRequest(
        @Size(max = 16) String cashierTemplate
) {
}
