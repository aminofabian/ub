package zelisline.ub.payments.api.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

public record SupplierPayoutSettingsRequest(
        Boolean enabled,
        @Size(max = 36) String paymentGatewayConfigId,
        Boolean autoPayEnabled,
        /** Clock times as {@code HH:mm} (Africa/Nairobi). Max 8. */
        List<@Size(max = 5) String> autoPayTimes
) {
}
