package zelisline.ub.opsalerts.api.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateOpsAlertSettingsRequest(
        @NotNull Boolean enabled,
        @NotNull Boolean alertWebOrder,
        @NotNull Boolean alertShift,
        @NotNull Boolean alertSupply,
        @NotNull Boolean alertCreditPayment,
        @NotNull Boolean alertRestockDigest
) {
}
