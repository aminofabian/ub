package zelisline.ub.till.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterTillDeviceRequest(
        @NotBlank @Size(max = 36) String branchId,
        /** Client till id; if blank, server reads X-Till-Device-Id. */
        @Size(max = 64) String deviceKey,
        @Size(max = 80) String label,
        /** shelf or ledger; omitted keeps existing (or shelf on first register). */
        @Size(max = 16) String cashierTemplate
) {
}
