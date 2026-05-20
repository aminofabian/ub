package zelisline.ub.payments.api.dto;

public record SupplierPayoutGatewayOption(
        String configId,
        String gatewayType,
        String label,
        String status
) {
}
