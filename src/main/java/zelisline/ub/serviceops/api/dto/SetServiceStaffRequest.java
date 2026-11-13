package zelisline.ub.serviceops.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Replaces the list of staff who may perform a service. An empty list means anyone. */
public record SetServiceStaffRequest(
        @NotNull @Size(max = 200) List<@NotBlank @Size(max = 36) String> staffProfileIds
) {
}
