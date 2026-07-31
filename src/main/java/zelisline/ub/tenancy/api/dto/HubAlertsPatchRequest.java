package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Patch hub alert prefs (volume). Null fields leave current values. */
public record HubAlertsPatchRequest(
        @Min(1) @Max(100) Integer volume
) {
}
