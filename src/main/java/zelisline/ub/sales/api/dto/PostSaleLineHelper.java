package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Someone who shared a service line with its performer. An equal split when no share is given. */
public record PostSaleLineHelper(
        @NotBlank @Size(max = 36) String staffProfileId,
        BigDecimal sharePercent,
        /** What they are paid for this service, in whole shillings; null means by their plan. */
        BigDecimal amount
) {
    public PostSaleLineHelper(String staffProfileId, BigDecimal sharePercent) {
        this(staffProfileId, sharePercent, null);
    }
}
