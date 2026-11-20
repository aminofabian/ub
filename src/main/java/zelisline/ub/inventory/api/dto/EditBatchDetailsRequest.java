package zelisline.ub.inventory.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Size;

/** Corrects the lot number and/or expiry date recorded on a batch. A null field is left unchanged. */
public record EditBatchDetailsRequest(
        @Size(max = 64) String lotNumber,
        LocalDate expiryDate
) {
}
