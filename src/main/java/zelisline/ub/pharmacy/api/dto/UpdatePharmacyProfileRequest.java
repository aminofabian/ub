package zelisline.ub.pharmacy.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdatePharmacyProfileRequest(
        @Size(max = 64) String premisesLicenceNo,
        LocalDate licenceExpiresOn,
        @Size(max = 36) String superintendentUserId,
        Boolean controlledSalesEnabled,
        @Min(7) @Max(730) Integer shortDatedDays,
        Boolean secondCheckEnabled,
        @Min(1) @Max(120) Integer orderLeadDays,
        @Min(1) @Max(365) Integer orderCoverDays
) {
}
