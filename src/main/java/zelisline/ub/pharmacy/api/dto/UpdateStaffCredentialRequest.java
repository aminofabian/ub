package zelisline.ub.pharmacy.api.dto;

import jakarta.validation.constraints.Size;

public record UpdateStaffCredentialRequest(
        @Size(max = 64) String title,
        @Size(max = 64) String registrationNo
) {
}
