package zelisline.ub.payments.api.dto;

import jakarta.validation.constraints.Size;

public record SupplierPayoutSettingsRequest(
        Boolean enabled,
        @Size(max = 36) String paymentGatewayConfigId
) {
}
