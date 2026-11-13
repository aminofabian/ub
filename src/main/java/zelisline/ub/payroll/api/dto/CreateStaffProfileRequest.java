package zelisline.ub.payroll.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Adds a staff member who has no login — a name is enough. */
public record CreateStaffProfileRequest(
        @NotBlank @Size(max = 255) String displayName,
        @Size(max = 128) String title,
        @Size(max = 50) String phone,
        LocalDate startDate,
        Boolean bookable
) {
}
