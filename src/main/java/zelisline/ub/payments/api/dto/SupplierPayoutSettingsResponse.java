package zelisline.ub.payments.api.dto;

import java.util.List;

public record SupplierPayoutSettingsResponse(
        boolean enabled,
        String paymentGatewayConfigId,
        String gatewayType,
        String gatewayLabel,
        boolean gatewayReady,
        boolean autoPayEnabled,
        List<SupplierPayoutGatewayOption> selectableGateways
) {
}
